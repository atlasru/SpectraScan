package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.hypot

internal data class RawObservation(
    val sourceTrackingId: Int?, val label: String, val confidence: Float, val normalizedBox: RectF,
    val fromBrightnessTracker: Boolean = false, val fromMotionTracker: Boolean = false, val fromFlowTracker: Boolean = false,
    val maskCells: List<MaskCell> = emptyList(), val maskQuality: Float = 0f
)

internal class HybridTracker {
    @Volatile var profile: TrackingProfile = TrackingProfile.BALANCED

    private class AxisKalman(initialPosition: Float) {
        var position = initialPosition; private set
        var velocity = 0f; private set
        private var p00=.08f; private var p01=0f; private var p10=0f; private var p11=.20f
        fun predict(dtIn: Float) { val d=dtIn.coerceIn(.001f,.25f); position+=velocity*d; val q0=.0007f+d*.0015f; val q1=.004f+d*.008f; val n00=p00+d*(p01+p10)+d*d*p11+q0; val n01=p01+d*p11; val n10=p10+d*p11; val n11=p11+q1; p00=n00;p01=n01;p10=n10;p11=n11 }
        fun correct(m:Float, noise:Float) { val r=noise.coerceIn(.001f,.20f); val innovation=m-position; val s=p00+r;if(s<=0)return;val k0=p00/s;val k1=p10/s;val a=p00;val b=p01;val c=p10;val d=p11;position+=k0*innovation;velocity+=k1*innovation;p00=(1-k0)*a;p01=(1-k0)*b;p10=c-k1*a;p11=d-k1*b }
    }

    private data class Track(
        val stableId:Int, var sourceTrackingId:Int?, var label:String, var confidence:Float, var box:RectF,
        var velocityX:Float, var velocityY:Float, var lastSeenAt:Long, var lastSemanticAt:Long, var updatedAt:Long,
        var hits:Int, var semanticHits:Int, var consecutiveHits:Int, var confirmed:Boolean,
        var fromBrightnessTracker:Boolean, var fromMotionTracker:Boolean, var fromFlowTracker:Boolean,
        var maskCells:List<MaskCell>, var maskQuality:Float,
        val kalmanX:AxisKalman, val kalmanY:AxisKalman
    )
    private val tracks=linkedMapOf<Int,Track>(); private var nextStableId=1

    @Synchronized fun update(observations:List<RawObservation>, now:Long):List<DetectionTarget> {
        val available=tracks.keys.toMutableSet()
        observations.sortedBy { it.fromFlowTracker }.forEach { obs ->
            val match=findBestTrack(obs,available)
            if(match==null) {
                if(obs.fromFlowTracker) return@forEach
                val id=nextStableId++; val semantic=!obs.fromBrightnessTracker&&!obs.fromMotionTracker
                tracks[id]=Track(id,obs.sourceTrackingId,obs.label,obs.confidence,RectF(obs.normalizedBox),0f,0f,now,if(semantic)now else 0L,now,1,if(semantic)1 else 0,1,false,obs.fromBrightnessTracker,obs.fromMotionTracker,false,obs.maskCells,obs.maskQuality,AxisKalman(obs.normalizedBox.centerX()),AxisKalman(obs.normalizedBox.centerY()))
            } else { available.remove(match.stableId); updateObservedTrack(match,obs,now) }
        }
        available.forEach { id->tracks[id]?.let{it.consecutiveHits=0;predictMissingTrack(it,now)} }
        tracks.entries.removeAll { (_,t)-> val semantic=t.lastSemanticAt>0L; if(semantic) now-t.lastSemanticAt>SEMANTIC_HARD_TTL_MS else now-t.lastSeenAt>(if(t.confirmed)profile.holdMs else 500L) }
        return buildTargets(now)
    }

    @Synchronized fun snapshot(now:Long)=buildTargets(now)
    @Synchronized fun reset(){tracks.clear();nextStableId=1}

    private fun buildTargets(now:Long)=tracks.values.mapNotNull { t->
        val confirmation=if(t.fromMotionTracker||t.fromBrightnessTracker)t.consecutiveHits else t.semanticHits
        if(!t.confirmed&&confirmation>=requiredHits(t))t.confirmed=true
        if(!t.confirmed)return@mapNotNull null
        val semanticAge=if(t.lastSemanticAt>0)now-t.lastSemanticAt else 0L; val missing=now-t.lastSeenAt
        val status=when { t.lastSemanticAt>0 && semanticAge>SEMANTIC_UNCERTAIN_MS -> TrackStatus.PREDICTED; missing==0L && !t.fromFlowTracker -> TrackStatus.TRACKING; missing<=profile.predictionMs || t.fromFlowTracker -> TrackStatus.PREDICTED; else -> TrackStatus.LOST }
        val semanticDecay=if(t.lastSemanticAt>0)(1f-semanticAge.toFloat()/SEMANTIC_HARD_TTL_MS).coerceIn(.12f,1f) else 1f
        val missingDecay=if(missing==0L)1f else (1f-missing.toFloat()/profile.holdMs).coerceIn(.15f,1f)
        DetectionTarget(t.stableId,t.label,t.confidence*minOf(semanticDecay,missingDecay),RectF(t.box),status,missing,t.velocityX,t.velocityY,t.fromBrightnessTracker,t.fromMotionTracker,t.fromFlowTracker,t.maskCells,t.maskQuality)
    }.sortedBy{it.trackingId}

    private fun requiredHits(t:Track)=when{t.fromMotionTracker->2;t.fromBrightnessTracker->2;t.label in FAST_CONFIRM_LABELS->2;else->3}

    private fun findBestTrack(o:RawObservation,ids:Set<Int>):Track? {
        if(o.sourceTrackingId!=null){tracks[o.sourceTrackingId]?.takeIf{it.stableId in ids}?.let{return it};tracks.values.firstOrNull{it.stableId in ids&&it.sourceTrackingId==o.sourceTrackingId}?.let{return it}}
        var best:Track?=null;var bestScore=Float.NEGATIVE_INFINITY
        tracks.values.forEach{t->if(t.stableId !in ids)return@forEach;val iou=iou(t.box,o.normalizedBox);val dist=centerDistance(t.box,o.normalizedBox);val sameLabel=t.label==o.label;val semantic=!o.fromFlowTracker&&!o.fromMotionTracker&&!o.fromBrightnessTracker;if(semantic&&(!sameLabel||(iou<.12f&&dist>.16f)))return@forEach;if(!semantic&&iou<.08f&&dist>.18f)return@forEach;var s=iou*2.4f-dist*1.5f;if(sameLabel)s+=.40f;if(t.fromMotionTracker==o.fromMotionTracker)s+=.08f;if(s>bestScore){bestScore=s;best=t}}
        return best
    }

    private fun updateObservedTrack(t:Track,o:RawObservation,now:Long){
        val dt=((now-t.updatedAt).coerceAtLeast(1)/1000f).coerceAtMost(.25f);t.kalmanX.predict(dt);t.kalmanY.predict(dt)
        val noise=when{ o.fromMotionTracker->.05f;o.fromBrightnessTracker->.045f;o.fromFlowTracker->.065f;else->.010f }
        t.kalmanX.correct(o.normalizedBox.centerX(),noise);t.kalmanY.correct(o.normalizedBox.centerY(),noise);t.velocityX=t.kalmanX.velocity.coerceIn(-1.5f,1.5f);t.velocityY=t.kalmanY.velocity.coerceIn(-1.5f,1.5f)
        val semantic=!o.fromFlowTracker&&!o.fromMotionTracker&&!o.fromBrightnessTracker;val smooth=if(o.fromFlowTracker)minOf(profile.smoothing,.30f) else profile.smoothing
        val oldW=t.box.width();val oldH=t.box.height();val width=(oldW+(o.normalizedBox.width()-oldW)*smooth).coerceIn(oldW*.72f,oldW*1.38f);val height=(oldH+(o.normalizedBox.height()-oldH)*smooth).coerceIn(oldH*.72f,oldH*1.38f);t.box=centered(t.kalmanX.position,t.kalmanY.position,width,height)
        if(semantic){t.label=o.label;t.confidence=maxOf(t.confidence*.65f,o.confidence);t.lastSemanticAt=now;t.semanticHits++;t.fromFlowTracker=false;t.sourceTrackingId=o.sourceTrackingId?:t.sourceTrackingId;if(o.maskCells.isNotEmpty()){t.maskCells=o.maskCells;t.maskQuality=o.maskQuality}}
        else if(o.fromFlowTracker){t.confidence=minOf(t.confidence,o.confidence);t.fromFlowTracker=true}else{if(!o.fromMotionTracker||t.label=="MOTION")t.label=o.label;t.confidence=maxOf(t.confidence*.72f,o.confidence);t.fromFlowTracker=false}
        t.lastSeenAt=now;t.updatedAt=now;t.hits++;t.consecutiveHits++;t.fromBrightnessTracker=o.fromBrightnessTracker;t.fromMotionTracker=o.fromMotionTracker
    }

    private fun predictMissingTrack(t:Track,now:Long){val dt=((now-t.updatedAt).coerceAtLeast(1)/1000f).coerceAtMost(.20f);val allowed=if(t.lastSemanticAt>0)SEMANTIC_HARD_TTL_MS else profile.holdMs;if(now-t.lastSeenAt<=allowed){t.kalmanX.predict(dt);t.kalmanY.predict(dt);t.velocityX=t.kalmanX.velocity;t.velocityY=t.kalmanY.velocity;t.box=centered(t.kalmanX.position,t.kalmanY.position,t.box.width(),t.box.height());t.updatedAt=now}}
    private fun centered(cx:Float,cy:Float,w0:Float,h0:Float):RectF{val w=w0.coerceIn(.008f,.98f);val h=h0.coerceIn(.008f,.98f);val l=(cx-w/2).coerceIn(0f,1f-w);val top=(cy-h/2).coerceIn(0f,1f-h);return RectF(l,top,l+w,top+h)}
    private fun iou(a:RectF,b:RectF):Float{val l=maxOf(a.left,b.left);val t=maxOf(a.top,b.top);val r=minOf(a.right,b.right);val bot=minOf(a.bottom,b.bottom);if(r<=l||bot<=t)return 0f;val x=(r-l)*(bot-t);val u=a.width()*a.height()+b.width()*b.height()-x;return if(u<=0)0f else x/u}
    private fun centerDistance(a:RectF,b:RectF)=hypot(a.centerX()-b.centerX(),a.centerY()-b.centerY())
    private companion object{const val SEMANTIC_UNCERTAIN_MS=650L;const val SEMANTIC_HARD_TTL_MS=1_350L;val FAST_CONFIRM_LABELS=setOf("PERSON","CELL PHONE","TV","LAPTOP","REMOTE","CLOCK","CAT","DOG","BIRD","HORSE","SHEEP","COW","ELEPHANT","BEAR","ZEBRA","GIRAFFE","CAR","MOTORCYCLE","AIRPLANE","BUS","TRAIN","TRUCK","BOAT")}
}
