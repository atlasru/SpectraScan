package com.atlas.spectrascan

import android.graphics.RectF

enum class TrackStatus { ACQUIRING, TRACKING, PREDICTED, LOST }

/**
 * User-facing performance modes. Enum names are kept for compatibility with
 * older builds, while their titles now describe the actual device workload.
 */
enum class TrackingProfile(val title:String,val smoothing:Float,val holdMs:Long,val predictionMs:Long){
    SMOOTH("LOW BATTERY",0.24f,1_500L,720L),
    BALANCED("BALANCED",0.46f,950L,500L),
    RESPONSIVE("FULL PERFORMANCE",0.76f,560L,280L);
    fun next():TrackingProfile=entries[(ordinal+1)%entries.size]
}

enum class TargetFilter(val title:String){
    ALL("ALL"),PEOPLE("PEOPLE"),ANIMALS("ANIMALS"),OBJECTS("OBJECTS"),SCREENS("SCREENS"),SKY("SKY"),MOTION("MOTION");
    fun next():TargetFilter=entries[(ordinal+1)%(entries.size-1)]
}

data class MaskCell(val x:Float,val y:Float,val width:Float,val height:Float,val confidence:Float=1f)

data class DetectionTarget(
    val trackingId:Int,val label:String,val confidence:Float,val normalizedBox:RectF,
    val status:TrackStatus=TrackStatus.ACQUIRING,val missingForMs:Long=0L,val velocityX:Float=0f,val velocityY:Float=0f,
    val fromBrightnessTracker:Boolean=false,val fromMotionTracker:Boolean=false,val fromFlowTracker:Boolean=false,
    val maskCells:List<MaskCell> = emptyList(), val maskQuality:Float = 0f
)

data class DetectionFrame(
    val targets:List<DetectionTarget> = emptyList(),val imageWidth:Int=1,val imageHeight:Int=1,val inferenceFps:Int=0,val inferenceMs:Long=0L,
    val brightTrackerActive:Boolean=false,val motionTrackerActive:Boolean=false,val hybridFlowActive:Boolean=false,val targetFilter:TargetFilter=TargetFilter.ALL,
    val rejectedCandidates:Int=0,val meanLuma:Float=255f,val lowLight:Boolean=false,val nightVisionSuggested:Boolean=false,val detectionThrottled:Boolean=false,
    val frameAtMs:Long=0L
)
