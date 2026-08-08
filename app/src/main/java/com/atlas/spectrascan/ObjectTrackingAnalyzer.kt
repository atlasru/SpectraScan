package com.atlas.spectrascan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

class ObjectTrackingAnalyzer(private val callbackExecutor: Executor, private val onFrame:(DetectionFrame)->Unit):ImageAnalysis.Analyzer,AutoCloseable {
    private val busy=AtomicBoolean(false)
    private val tracker=HybridTracker()
    private val motionDetector=MotionFlowDetector()
    private val semanticFlow=SemanticFlowTracker()
    private val sparseFlow=SparseFeatureTracker()
    private val localMotion=LocalTargetMotionTracker()
    private val presentationSmoother=PresentationTargetSmoother()
    private val yoloDetector=lazy{YoloDetector(SpectraScanApplication.appContext)}
    private var lastResultAt=0L;private var lastYoloAt=0L;private var previousYoloAt=0L;private var lastYoloMs=0L;private var lastYoloFps=0;private var lastTargets:List<DetectionTarget> = emptyList();private var lastZoomGeneration=0L
    @Volatile private var targetFilter=TargetFilter.ALL;@Volatile private var digitalGain=1f;@Volatile private var motionDetectionEnabled=false
    @Volatile private var skyWatchEnabled=false;@Volatile private var stationaryCamera=false;@Volatile private var powerProfile=TrackingProfile.BALANCED

    fun setProfile(profile:TrackingProfile){powerProfile=profile;tracker.profile=profile;presentationSmoother.setProfile(profile)}
    fun setTargetFilter(filter:TargetFilter){
        if(targetFilter!=filter){
            targetFilter=filter;skyWatchEnabled=filter==TargetFilter.SKY;stationaryCamera=skyWatchEnabled;motionDetector.setSkyWatch(skyWatchEnabled,stationaryCamera)
            tracker.reset();motionDetector.reset();semanticFlow.reset();sparseFlow.reset();localMotion.reset();presentationSmoother.reset();lastTargets=emptyList();lastYoloAt=0
        }
    }
    fun setDigitalGain(gain:Float){digitalGain=gain.coerceIn(1f,2.4f)}
    fun setMotionDetectionEnabled(enabled:Boolean){if(motionDetectionEnabled!=enabled){motionDetectionEnabled=enabled;motionDetector.reset()}}
    fun setSkyWatch(enabled:Boolean,stationary:Boolean){
        if(skyWatchEnabled!=enabled||stationaryCamera!=stationary){
            skyWatchEnabled=enabled;stationaryCamera=enabled&&stationary;motionDetector.setSkyWatch(enabled,stationaryCamera)
            tracker.reset();semanticFlow.reset();sparseFlow.reset();localMotion.reset();presentationSmoother.reset();lastTargets=emptyList();lastYoloAt=0
        }
    }

    override fun analyze(imageProxy:ImageProxy){
        if(!busy.compareAndSet(false,true)){imageProxy.close();return}
        val rotation=imageProxy.imageInfo.rotationDegrees
        val orientedWidth=if(rotation==90||rotation==270)imageProxy.height else imageProxy.width
        val orientedHeight=if(rotation==90||rotation==270)imageProxy.width else imageProxy.height
        val activeFilter=targetFilter
        try{
            val meanLuma=calculateMeanLuma(imageProxy);val lowLight=meanLuma<58;val nightVisionSuggested=meanLuma<40;val now=SystemClock.elapsedRealtime()
            val zoomGeneration=ZoomBoostSignal.generation();val zoomGeometryChanged=zoomGeneration!=lastZoomGeneration
            if(zoomGeometryChanged){lastZoomGeneration=zoomGeneration;semanticFlow.reset();sparseFlow.reset();localMotion.reset();presentationSmoother.reset();lastYoloAt=0L}
            val zoomBoost=ZoomBoostSignal.isActive(now)
            val useMotion=motionDetectionEnabled||skyWatchEnabled
            val motionResult=if(useMotion)motionDetector.analyze(imageProxy,rotation) else MotionFlowDetector.Result(emptyList(),0f,0f,false)

            // Lightweight trackers run on every analyzer frame that is not occupied by YOLO.
            // They always follow the most recent semantic anchor, because every YOLO pass
            // below forcibly reseeds LK + local target motion + template flow.
            val sparseResult=if(zoomBoost) SparseFeatureTracker.Result(emptyList(),false,0f,false) else sparseFlow.track(imageProxy,rotation,now)
            val localResult=if(zoomBoost) LocalTargetMotionTracker.Result(emptyList(),false,false) else localMotion.track(imageProxy,rotation,now)
            val fusedFlow=fuseTargetMeasurements(sparseResult.observations,localResult.observations)
            val fusedActive=fusedFlow.isNotEmpty()
            val fusedScore=when{
                sparseResult.active&&localResult.active->(sparseResult.averageScore*.78f+.18f).coerceAtMost(.98f)
                sparseResult.active->sparseResult.averageScore
                localResult.active->.58f
                else->0f
            }
            val fusedNeedsRecheck=sparseResult.needsYoloRecheck||localResult.needsYoloRecheck
            val flowResult=when{
                zoomBoost->SemanticFlowTracker.Result(emptyList(),false,0f,false)
                fusedActive||fusedNeedsRecheck->SemanticFlowTracker.Result(fusedFlow,fusedActive,fusedScore,fusedNeedsRecheck)
                powerProfile!=TrackingProfile.RESPONSIVE->semanticFlow.track(imageProxy,rotation,now)
                else->SemanticFlowTracker.Result(emptyList(),false,0f,false)
            }

            if(activeFilter==TargetFilter.MOTION&&!skyWatchEnabled){
                val targets=tracker.update(motionResult.observations,now);lastTargets=targets
                dispatchFrame(targets,orientedWidth,orientedHeight,now,false,useMotion&&motionResult.active,false,activeFilter,0,meanLuma,lowLight,nightVisionSuggested,true);return
            }

            val yoloInterval=adaptiveYoloInterval(meanLuma,flowResult,lastTargets,useMotion&&motionResult.active,zoomBoost)
            val responsiveForce=powerProfile==TrackingProfile.RESPONSIVE&&!flowResult.active
            val yoloDue=zoomBoost||responsiveForce||lastYoloAt==0L||flowResult.needsYoloRecheck||now-lastYoloAt>=yoloInterval
            if(!yoloDue){
                val observations=mergeLightweightObservations(flowResult.observations,if(useMotion)motionResult.observations else emptyList())
                val targets=tracker.update(observations,now);lastTargets=targets
                dispatchFrame(targets,orientedWidth,orientedHeight,now,false,useMotion&&motionResult.active,flowResult.active,activeFilter,0,meanLuma,lowLight,nightVisionSuggested,true);return
            }

            previousYoloAt=lastYoloAt;lastYoloAt=now
            val brightObservation=if(!skyWatchEnabled&&(activeFilter==TargetFilter.ALL||activeFilter==TargetFilter.SCREENS))findBrightRegion(imageProxy,rotation,meanLuma) else null
            val cameraBitmap=imageProxy.toBitmap();val orientedBitmap=rotateBitmap(cameraBitmap,rotation)
            val automaticGain=when{meanLuma<28->1.75f;meanLuma<45->1.45f;meanLuma<70->1.20f;else->1f};val effectiveGain=maxOf(digitalGain,automaticGain)
            val yoloStarted=SystemClock.elapsedRealtime()
            val detectionResult=try{yoloDetector.value.detect(orientedBitmap,activeFilter,effectiveGain)}finally{
                if(orientedBitmap!==cameraBitmap&&!orientedBitmap.isRecycled)orientedBitmap.recycle();if(!cameraBitmap.isRecycled)cameraBitmap.recycle()
            }
            lastYoloMs=SystemClock.elapsedRealtime()-yoloStarted
            lastYoloFps=if(previousYoloAt<=0)0 else (1000L/max(1L,lastYoloAt-previousYoloAt)).toInt().coerceIn(0,30)
            val(detections,rejected)=detectionResult
            val observations=detections.map{RawObservation(null,it.label,it.confidence,it.normalizedBox,maskCells=it.maskCells,maskQuality=it.maskQuality)}.toMutableList()
            if(useMotion)motionResult.observations.forEach{m->if(observations.none{intersectionOverUnion(it.normalizedBox,m.normalizedBox)>.16f})observations+=m}
            if(brightObservation!=null&&observations.none{intersectionOverUnion(it.normalizedBox,brightObservation.normalizedBox)>.30f})observations+=brightObservation
            val afterYolo=SystemClock.elapsedRealtime();val targets=tracker.update(observations,afterYolo);lastTargets=targets
            if(!ZoomBoostSignal.isActive(afterYolo)){
                // Fresh YOLO geometry is the authoritative anchor for every secondary tracker.
                sparseFlow.seed(imageProxy,rotation,targets,afterYolo)
                localMotion.seed(imageProxy,rotation,targets,afterYolo)
                semanticFlow.seed(imageProxy,rotation,targets,afterYolo)
            }
            dispatchFrame(targets,orientedWidth,orientedHeight,afterYolo,brightObservation!=null,useMotion&&motionResult.active,flowResult.active,activeFilter,rejected,meanLuma,lowLight,nightVisionSuggested,false)
        }catch(_:Throwable){
            val now=SystemClock.elapsedRealtime();val targets=tracker.update(emptyList(),now);lastTargets=targets
            dispatchFrame(targets,orientedWidth,orientedHeight,now,false,false,false,activeFilter,0,255f,false,false,true)
        }finally{busy.set(false);imageProxy.close()}
    }

    private fun fuseTargetMeasurements(lk:List<RawObservation>,local:List<RawObservation>):List<RawObservation>{
        if(lk.isEmpty())return local
        if(local.isEmpty())return lk
        val localById=local.associateBy{it.sourceTrackingId}
        val used=mutableSetOf<Int?>()
        val out=lk.map{a->
            val b=localById[a.sourceTrackingId]
            if(b==null)return@map a
            used+=b.sourceTrackingId
            val dist=hypot(a.normalizedBox.centerX()-b.normalizedBox.centerX(),a.normalizedBox.centerY()-b.normalizedBox.centerY())
            if(dist>.075f)return@map a
            val wa=.72f;val wb=.28f
            val box=RectF(
                a.normalizedBox.left*wa+b.normalizedBox.left*wb,
                a.normalizedBox.top*wa+b.normalizedBox.top*wb,
                a.normalizedBox.right*wa+b.normalizedBox.right*wb,
                a.normalizedBox.bottom*wa+b.normalizedBox.bottom*wb
            )
            a.copy(confidence=maxOf(a.confidence,b.confidence*.82f),normalizedBox=box)
        }.toMutableList()
        local.forEach{b->if(b.sourceTrackingId !in used&&lk.none{it.sourceTrackingId==b.sourceTrackingId})out+=b}
        return out
    }

    private fun adaptiveYoloInterval(meanLuma:Float,flow:SemanticFlowTracker.Result,targets:List<DetectionTarget>,motionActive:Boolean,zoomBoost:Boolean):Long{
        if(zoomBoost)return 0L
        if(powerProfile==TrackingProfile.RESPONSIVE){
            // Target ~5-6 semantic anchors/sec. If inference itself takes >180 ms,
            // hardware throughput becomes the limit and the next frame runs ASAP.
            if(flow.needsYoloRecheck)return 0L
            return if(meanLuma<24)220L else 180L
        }
        if(powerProfile==TrackingProfile.SMOOTH){
            if(flow.needsYoloRecheck)return if(meanLuma<35)700L else 420L
            if(skyWatchEnabled){val base=when{motionActive->520L;targets.any{it.label=="UNKNOWN"}->620L;else->1_050L};val low=when{meanLuma<30->1_300L;meanLuma<55->900L;else->0L};return maxOf(base,low)}
            val base=when{targets.isEmpty()->1_000L;targets.any{it.status==TrackStatus.PREDICTED||it.status==TrackStatus.LOST}->520L;flow.active&&flow.averageScore>=.78f->1_150L;else->760L}
            val low=when{meanLuma<25->1_350L;meanLuma<45->950L;meanLuma<65->720L;else->0L};return maxOf(base,low)
        }
        // BALANCED still uses frequent YOLO anchors so LK/local-motion cannot drift far.
        if(flow.needsYoloRecheck)return if(meanLuma<30)360L else 170L
        if(skyWatchEnabled){val base=when{motionActive->240L;targets.any{it.label=="UNKNOWN"}->280L;stationaryCamera->520L;else->420L};val low=when{meanLuma<22->720L;meanLuma<38->500L;meanLuma<58->320L;else->0L};return maxOf(base,low)}
        val interval=when{targets.isEmpty()->if(motionActive)260L else 340L;targets.any{it.status==TrackStatus.PREDICTED||it.status==TrackStatus.LOST}->180L;flow.active&&flow.averageScore>=.82f->360L;flow.active&&flow.averageScore>=.72f->300L;else->240L}
        val lowFloor=when{meanLuma<22->580L;meanLuma<38->400L;meanLuma<58->260L;else->0L};return maxOf(interval,lowFloor)
    }

    private fun mergeLightweightObservations(flow:List<RawObservation>,motion:List<RawObservation>):List<RawObservation>{if(flow.isEmpty())return motion;if(motion.isEmpty())return flow;val merged=flow.toMutableList();motion.forEach{c->if(merged.none{intersectionOverUnion(it.normalizedBox,c.normalizedBox)>.16f})merged+=c};return merged}
    private fun dispatchFrame(targets:List<DetectionTarget>,w:Int,h:Int,now:Long,bright:Boolean,motion:Boolean,flow:Boolean,filter:TargetFilter,rejected:Int,luma:Float,low:Boolean,nv:Boolean,throttled:Boolean){lastResultAt=now;val presented=presentationSmoother.apply(targets,now);val frame=DetectionFrame(presented,w,h,lastYoloFps,lastYoloMs,bright,motion,flow,filter,rejected,luma,low,nv,throttled,now);callbackExecutor.execute{onFrame(frame)}}
    private fun rotateBitmap(source:Bitmap,rotation:Int):Bitmap{if(rotation==0)return source;val m=Matrix().apply{postRotate(rotation.toFloat())};return Bitmap.createBitmap(source,0,0,source.width,source.height,m,true)}
    private fun calculateMeanLuma(image:ImageProxy):Float{val p=image.planes.firstOrNull()?:return 255f;val b=p.buffer.duplicate();var sum=0L;var n=0;var y=0;while(y<image.height){var x=0;while(x<image.width){val i=y*p.rowStride+x*p.pixelStride;if(i<b.limit()){sum+=b.get(i).toInt()and 255;n++};x+=8};y+=8};return if(n==0)255f else sum.toFloat()/n}
    private fun findBrightRegion(image:ImageProxy,rotation:Int,mean:Float):RawObservation?{val p=image.planes.firstOrNull()?:return null;val b=p.buffer.duplicate();val width=image.width;val height=image.height;if(mean>110)return null;val threshold=maxOf(182f,mean+72).toInt();var minX=width;var minY=height;var maxX=-1;var maxY=-1;var count=0;var samples=0;var y=0;while(y<height){var x=0;while(x<width){val i=y*p.rowStride+x*p.pixelStride;if(i<b.limit()){samples++;if((b.get(i).toInt()and 255)>=threshold){minX=minOf(minX,x);minY=minOf(minY,y);maxX=maxOf(maxX,x);maxY=maxOf(maxY,y);count++}};x+=4};y+=4};if(samples==0)return null;val ratio=count.toFloat()/samples;if(maxX<=minX||maxY<=minY||ratio !in .0015f.. .10f)return null;val raw=RectF((minX.toFloat()/width-.02f).coerceIn(0f,1f),(minY.toFloat()/height-.02f).coerceIn(0f,1f),(maxX.toFloat()/width+.02f).coerceIn(0f,1f),(maxY.toFloat()/height+.02f).coerceIn(0f,1f));val r=rotateNormalizedRect(raw,rotation);val area=r.width()*r.height();if(r.width() !in .02f.. .55f||r.height() !in .02f.. .55f||area !in .0008f.. .16f)return null;return RawObservation(null,"BRIGHT OBJECT",(.48f+ratio*3.5f).coerceIn(.48f,.90f),r,fromBrightnessTracker=true)}
    private fun rotateNormalizedRect(r:RectF,rotation:Int)=when(rotation){90->RectF(1-r.bottom,r.left,1-r.top,r.right);180->RectF(1-r.right,1-r.bottom,1-r.left,1-r.top);270->RectF(r.top,1-r.right,r.bottom,1-r.left);else->RectF(r)}
    private fun intersectionOverUnion(a:RectF,b:RectF):Float{val l=maxOf(a.left,b.left);val t=maxOf(a.top,b.top);val r=minOf(a.right,b.right);val bot=minOf(a.bottom,b.bottom);if(r<=l||bot<=t)return 0f;val x=(r-l)*(bot-t);val u=a.width()*a.height()+b.width()*b.height()-x;return if(u<=0)0f else x/u}
    override fun close(){tracker.reset();motionDetector.reset();semanticFlow.reset();sparseFlow.reset();localMotion.reset();presentationSmoother.reset();if(yoloDetector.isInitialized())yoloDetector.value.close()}
}
