package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lightweight residual-motion detector. SkyWatch adds a small-target stationary profile. */
internal class MotionFlowDetector {
    private val gridWidth = 128
    private val gridHeight = 96
    private var previous: ByteArray? = null
    @Volatile private var skyWatch = false
    @Volatile private var stationary = false

    data class Result(val observations: List<RawObservation>, val globalDx: Float, val globalDy: Float, val active: Boolean)

    fun reset() { previous = null }
    fun setSkyWatch(enabled: Boolean, stationaryCamera: Boolean) {
        if (skyWatch != enabled || stationary != stationaryCamera) reset()
        skyWatch = enabled
        stationary = enabled && stationaryCamera
    }

    fun analyze(image: ImageProxy, rotation: Int): Result {
        val current = downsampleLuma(image)
        val prev = previous; previous = current
        if (prev == null) return Result(emptyList(), 0f, 0f, false)

        val estimated = if (stationary) 0 to 0 else estimateGlobalShift(prev, current)
        val shiftX = estimated.first; val shiftY = estimated.second
        val residual = IntArray(gridWidth * gridHeight)
        var residualSum = 0L; var compared = 0
        for (y in 2 until gridHeight - 2) for (x in 2 until gridWidth - 2) {
            val px=x-shiftX; val py=y-shiftY
            if(px !in 0 until gridWidth||py !in 0 until gridHeight)continue
            val now=current[y*gridWidth+x].toInt() and 255;val old=prev[py*gridWidth+px].toInt() and 255
            val d=abs(now-old);residual[y*gridWidth+x]=d;residualSum+=d;compared++
        }
        if(compared==0)return Result(emptyList(),0f,0f,false)
        val mean=residualSum.toFloat()/compared
        val threshold = if (skyWatch) max(if(stationary) 14f else 18f, mean*(if(stationary)1.75f else 2.0f)+6f) else max(24f,mean*2.35f+8f)
        val thresholdInt=threshold.toInt().coerceAtMost(if(skyWatch)58 else 72)
        val mask=BooleanArray(residual.size);var activeCells=0
        residual.indices.forEach{if(residual[it]>=thresholdInt){mask[it]=true;activeCells++}}
        val activeRatio=activeCells.toFloat()/residual.size
        val maxActive=if(stationary&&skyWatch)0.08f else if(skyWatch)0.12f else 0.16f
        if(activeRatio>maxActive)return Result(emptyList(),shiftX.toFloat()/gridWidth,shiftY.toFloat()/gridHeight,false)

        val visited=BooleanArray(mask.size);val components=mutableListOf<Component>()
        for(y in 1 until gridHeight-1)for(x in 1 until gridWidth-1){val i=y*gridWidth+x;if(!mask[i]||visited[i])continue;val c=flood(mask,visited,residual,x,y)
            val cellRange=if(skyWatch)1..70 else 2..95;if(c.cells in cellRange)components+=c}
        val maxTargets=if(skyWatch)10 else 6
        val observations=components.sortedByDescending{it.energy}.take(maxTargets).mapNotNull{componentToObservation(it,rotation,thresholdInt)}
        return Result(observations,shiftX.toFloat()/gridWidth,shiftY.toFloat()/gridHeight,observations.isNotEmpty())
    }

    private data class Component(var minX:Int,var minY:Int,var maxX:Int,var maxY:Int,var cells:Int,var energy:Int)
    private fun flood(mask:BooleanArray,visited:BooleanArray,residual:IntArray,sx:Int,sy:Int):Component{
        val qx=IntArray(gridWidth*gridHeight);val qy=IntArray(gridWidth*gridHeight);var head=0;var tail=0
        qx[tail]=sx;qy[tail]=sy;tail++;visited[sy*gridWidth+sx]=true;val c=Component(sx,sy,sx,sy,0,0)
        while(head<tail){val x=qx[head];val y=qy[head];head++;val i=y*gridWidth+x;c.minX=min(c.minX,x);c.minY=min(c.minY,y);c.maxX=max(c.maxX,x);c.maxY=max(c.maxY,y);c.cells++;c.energy+=residual[i]
            for(dy in -1..1)for(dx in -1..1){if(dx==0&&dy==0)continue;val nx=x+dx;val ny=y+dy;if(nx !in 1 until gridWidth-1||ny !in 1 until gridHeight-1)continue;val n=ny*gridWidth+nx;if(mask[n]&&!visited[n]){visited[n]=true;qx[tail]=nx;qy[tail]=ny;tail++}}}
        return c
    }

    private fun componentToObservation(c:Component,rotation:Int,threshold:Int):RawObservation?{
        val padX=(if(skyWatch)1.2f else 2f)/gridWidth;val padY=(if(skyWatch)1.2f else 2f)/gridHeight
        val raw=RectF((c.minX.toFloat()/gridWidth-padX).coerceIn(0f,1f),(c.minY.toFloat()/gridHeight-padY).coerceIn(0f,1f),((c.maxX+1f)/gridWidth+padX).coerceIn(0f,1f),((c.maxY+1f)/gridHeight+padY).coerceIn(0f,1f))
        val oriented=rotateNormalizedRect(raw,rotation);val area=oriented.width()*oriented.height()
        val areaRange=if(skyWatch)0.00006f..0.025f else 0.00035f..0.075f
        if(area !in areaRange)return null
        val maxDim=if(skyWatch)0.20f else 0.38f;if(oriented.width()>maxDim||oriented.height()>maxDim)return null
        val avg=c.energy.toFloat()/c.cells.coerceAtLeast(1)
        val conf=(if(skyWatch)0.38f else 0.42f)+(avg-threshold).coerceAtLeast(0f)/(if(skyWatch)100f else 120f)+c.cells.coerceAtMost(20)/120f
        return RawObservation(null,if(skyWatch)"UNKNOWN" else "MOTION",conf.coerceIn(if(skyWatch)0.38f else 0.42f,0.88f),oriented,fromMotionTracker=true)
    }

    private fun estimateGlobalShift(previous:ByteArray,current:ByteArray):Pair<Int,Int>{var bestDx=0;var bestDy=0;var best=Long.MAX_VALUE
        for(dy in -3..3)for(dx in -3..3){var score=0L;var count=0;var y=6;while(y<gridHeight-6){var x=6;while(x<gridWidth-6){val px=x-dx;val py=y-dy;if(px in 0 until gridWidth&&py in 0 until gridHeight){score+=abs((current[y*gridWidth+x].toInt()and 255)-(previous[py*gridWidth+px].toInt()and 255));count++};x+=4};y+=4};if(count>0&&score/count<best){best=score/count;bestDx=dx;bestDy=dy}}
        return bestDx to bestDy}
    private fun downsampleLuma(image:ImageProxy):ByteArray{val p=image.planes.first();val b=p.buffer.duplicate();val out=ByteArray(gridWidth*gridHeight)
        for(gy in 0 until gridHeight){val sy=((gy+.5f)*image.height/gridHeight).toInt().coerceIn(0,image.height-1);for(gx in 0 until gridWidth){val sx=((gx+.5f)*image.width/gridWidth).toInt().coerceIn(0,image.width-1);val i=sy*p.rowStride+sx*p.pixelStride;out[gy*gridWidth+gx]=if(i<b.limit())b.get(i)else 0}};return out}
    private fun rotateNormalizedRect(r:RectF,rotation:Int)=when(rotation){90->RectF(1-r.bottom,r.left,1-r.top,r.right);180->RectF(1-r.right,1-r.bottom,1-r.left,1-r.top);270->RectF(r.top,1-r.right,r.bottom,1-r.left);else->RectF(r)}
}
