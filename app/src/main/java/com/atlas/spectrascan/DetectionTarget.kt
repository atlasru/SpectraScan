package com.atlas.spectrascan

import android.graphics.RectF

data class DetectionTarget(
    val trackingId: Int,
    val label: String,
    val confidence: Float,
    val normalizedBox: RectF
)

data class DetectionFrame(
    val targets: List<DetectionTarget> = emptyList(),
    val imageWidth: Int = 1,
    val imageHeight: Int = 1,
    val inferenceFps: Int = 0
)
