package com.atlas.spectrascan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

internal data class YoloDetection(val classId:Int,val label:String,val confidence:Float,val normalizedBox:RectF)

internal class YoloDetector(context:Context):AutoCloseable{
    private val environment=OrtEnvironment.getEnvironment()
    private val sessionOptions=OrtSession.SessionOptions().apply{setIntraOpNumThreads(2);setInterOpNumThreads(1);setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)}
    private val session:OrtSession;private val inputName:String
    private val scaledBitmap=Bitmap.createBitmap(INPUT_SIZE,INPUT_SIZE,Bitmap.Config.ARGB_8888);private val scaledCanvas=Canvas(scaledBitmap);private val scalePaint=Paint(Paint.FILTER_BITMAP_FLAG);private val pixels=IntArray(INPUT_SIZE*INPUT_SIZE)
    private val inputBuffer=ByteBuffer.allocateDirect(INPUT_SIZE*INPUT_SIZE*3*Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
    init{val bytes=context.assets.open(MODEL_FILE).use{it.readBytes()};session=environment.createSession(bytes,sessionOptions);inputName=session.inputNames.first()}

    @Synchronized fun detect(bitmap:Bitmap,filter:TargetFilter,gain:Float=1f):Pair<List<YoloDetection>,Int>{
        if(filter==TargetFilter.MOTION)return emptyList<YoloDetection>() to 0

        // Preserve source aspect ratio. Stretching landscape/portrait frames directly
        // into 640x640 distorted object geometry and produced oversized boxes.
        val srcW=bitmap.width.toFloat().coerceAtLeast(1f);val srcH=bitmap.height.toFloat().coerceAtLeast(1f)
        val scale=min(INPUT_SIZE/srcW,INPUT_SIZE/srcH)
        val drawW=srcW*scale;val drawH=srcH*scale
        val padX=(INPUT_SIZE-drawW)/2f;val padY=(INPUT_SIZE-drawH)/2f
        scaledCanvas.drawColor(Color.rgb(114,114,114))
        scaledCanvas.drawBitmap(bitmap,null,RectF(padX,padY,padX+drawW,padY+drawH),scalePaint)
        scaledBitmap.getPixels(pixels,0,INPUT_SIZE,0,0,INPUT_SIZE,INPUT_SIZE)

        val effectiveGain=gain.coerceIn(1f,2.4f);inputBuffer.clear();for(channel in 0..2)for(pixel in pixels){val raw=when(channel){0->(pixel shr 16)and 255;1->(pixel shr 8)and 255;else->pixel and 255};inputBuffer.put((raw*effectiveGain).coerceAtMost(255f)/255f)};inputBuffer.flip()
        val tensor=OnnxTensor.createTensor(environment,inputBuffer,longArrayOf(1,3,INPUT_SIZE.toLong(),INPUT_SIZE.toLong()));var rejected=0;val candidates=mutableListOf<YoloDetection>()
        tensor.use{input->session.run(mapOf(inputName to input)).use{result->val batch=result[0].value as? Array<*> ?: return@use;val channels=batch.firstOrNull() as? Array<*> ?: return@use;if(channels.size<84)return@use
            val boxX=channels[0] as? FloatArray ?: return@use;val boxY=channels[1] as? FloatArray ?: return@use;val boxW=channels[2] as? FloatArray ?: return@use;val boxH=channels[3] as? FloatArray ?: return@use;val anchors=minOf(boxX.size,boxY.size,boxW.size,boxH.size)
            for(i in 0 until anchors){var bestClass=-1;var bestScore=0f;for(classId in COCO_LABELS.indices){val scores=channels[4+classId] as? FloatArray ?: continue;if(i>=scores.size)continue;val s=scores[i];if(s>bestScore){bestScore=s;bestClass=classId}}
                if(bestClass<0)continue;val label=COCO_LABELS[bestClass];if(bestScore<confidenceThreshold(label,filter)||!filterAccepts(label,filter)){if(bestScore>=.10f)rejected++;continue}
                val leftPx=(boxX[i]-boxW[i]/2f-padX)/scale;val topPx=(boxY[i]-boxH[i]/2f-padY)/scale
                val rightPx=(boxX[i]+boxW[i]/2f-padX)/scale;val bottomPx=(boxY[i]+boxH[i]/2f-padY)/scale
                if(rightPx<=0f||bottomPx<=0f||leftPx>=srcW||topPx>=srcH){rejected++;continue}
                val rect=RectF((leftPx/srcW).coerceIn(0f,1f),(topPx/srcH).coerceIn(0f,1f),(rightPx/srcW).coerceIn(0f,1f),(bottomPx/srcH).coerceIn(0f,1f))
                if(!geometryAccepts(rect,label,filter)){rejected++;continue};candidates+=YoloDetection(bestClass,label.uppercase(),bestScore,rect)}}}
        return nonMaxSuppression(candidates) to rejected
    }

    private fun confidenceThreshold(label:String,filter:TargetFilter):Float{if(filter==TargetFilter.SKY)return when(label){"airplane"->.16f;"bird","kite"->.18f;else->.26f};return when(label){"person"->.24f;"cell phone"->.18f;"tv","laptop","remote","clock"->.22f;"cat","dog","bird","horse","sheep","cow"->.24f;else->.30f}}
    private fun filterAccepts(label:String,filter:TargetFilter)=when(filter){TargetFilter.ALL->true;TargetFilter.PEOPLE->label=="person";TargetFilter.ANIMALS->label in ANIMAL_LABELS;TargetFilter.SCREENS->label in SCREEN_LABELS;TargetFilter.OBJECTS->label!="person"&&label !in ANIMAL_LABELS;TargetFilter.SKY->label in SKY_LABELS;TargetFilter.MOTION->false}
    private fun geometryAccepts(box:RectF,label:String,filter:TargetFilter):Boolean{val w=box.width();val h=box.height();val area=w*h;if(w<=0||h<=0)return false;val minArea=if(filter==TargetFilter.SKY)0.00008f else when(label.lowercase()){ "cell phone","remote","clock"->.00025f;else->.0007f};if(area<minArea)return false;if(filter==TargetFilter.SKY){if(w>.45f||h>.45f||area>.12f)return false}else if(label.equals("person",true)){if(w>.98f||h>.98f||area>.86f)return false}else if(w>.90f||h>.90f||area>.70f)return false;val aspect=w/max(h,.0001f);return aspect in .08f..12f}
    private fun nonMaxSuppression(input:List<YoloDetection>):List<YoloDetection>{val kept=mutableListOf<YoloDetection>();for(c in input.sortedByDescending{it.confidence}){if(kept.none{it.classId==c.classId&&iou(it.normalizedBox,c.normalizedBox)>NMS_IOU}){kept+=c;if(kept.size>=MAX_DETECTIONS)break}};return kept}
    private fun iou(a:RectF,b:RectF):Float{val l=maxOf(a.left,b.left);val t=maxOf(a.top,b.top);val r=minOf(a.right,b.right);val bot=minOf(a.bottom,b.bottom);if(r<=l||bot<=t)return 0f;val x=(r-l)*(bot-t);val u=a.width()*a.height()+b.width()*b.height()-x;return if(u<=0)0f else x/u}
    override fun close(){session.close();sessionOptions.close();scaledBitmap.recycle()}
    private companion object{const val MODEL_FILE="yolo11n.onnx";const val INPUT_SIZE=640;const val NMS_IOU=.45f;const val MAX_DETECTIONS=24
        val ANIMAL_LABELS=setOf("bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe");val SCREEN_LABELS=setOf("tv","laptop","cell phone","remote","clock");val SKY_LABELS=setOf("airplane","bird","kite")
        val COCO_LABELS=listOf("person","bicycle","car","motorcycle","airplane","bus","train","truck","boat","traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant","bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone","microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors","teddy bear","hair drier","toothbrush")}
}
