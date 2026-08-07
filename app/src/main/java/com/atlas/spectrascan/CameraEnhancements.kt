package com.atlas.spectrascan

import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import kotlin.math.abs

/**
 * Shared signal generated from the *actual* Camera2 crop region.
 *
 * This deliberately does not mean "zoom is enabled". It becomes active only
 * while the sensor crop is changing and stays active briefly after the change,
 * giving YOLO time to reacquire objects at the new field of view.
 */
internal object ZoomBoostSignal {
    private const val HOLD_MS = 900L
    private var lastCrop: Rect? = null

    @Volatile private var activeUntilMs: Long = 0L
    @Volatile private var generation: Long = 0L

    @Synchronized
    fun onCropRegion(region: Rect?) {
        if (region == null || region.width() <= 0 || region.height() <= 0) return
        val previous = lastCrop
        lastCrop = Rect(region)
        if (previous == null || previous.width() <= 0 || previous.height() <= 0) return

        val widthDelta = abs(region.width() - previous.width()).toFloat() / previous.width()
        val heightDelta = abs(region.height() - previous.height()).toFloat() / previous.height()
        val centerDeltaX = abs(region.centerX() - previous.centerX()).toFloat() / previous.width()
        val centerDeltaY = abs(region.centerY() - previous.centerY()).toFloat() / previous.height()

        // Ignore Camera2 integer rounding. A real pinch/camera zoom changes the crop
        // by considerably more than a few ten-thousandths of the sensor extent.
        if (maxOf(widthDelta, heightDelta, centerDeltaX, centerDeltaY) >= 0.0025f) {
            activeUntilMs = SystemClock.elapsedRealtime() + HOLD_MS
            generation++
        }
    }

    fun isActive(nowMs: Long = SystemClock.elapsedRealtime()): Boolean = nowMs < activeUntilMs
    fun generation(): Long = generation
}

@OptIn(ExperimentalCamera2Interop::class)
object CameraEnhancements {
    fun configurePreview(
        builder: Preview.Builder,
        cameraInfo: CameraInfo,
        sharpen: Boolean,
        denoise: Boolean,
        stabilization: Boolean
    ): Preview.Builder {
        if (stabilization && Preview.getPreviewCapabilities(cameraInfo).isStabilizationSupported) {
            builder.setPreviewStabilizationEnabled(true)
        }

        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            if (sharpen) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_OFF
        )
        extender.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (denoise) CaptureRequest.NOISE_REDUCTION_MODE_FAST else CaptureRequest.NOISE_REDUCTION_MODE_OFF
        )
        extender.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                ZoomBoostSignal.onCropRegion(result.get(CaptureResult.SCALER_CROP_REGION))
            }
        })
        return builder
    }

    fun configureAnalysis(
        builder: ImageAnalysis.Builder,
        sharpen: Boolean,
        denoise: Boolean
    ): ImageAnalysis.Builder {
        val extender = Camera2Interop.Extender(builder)
        extender.setCaptureRequestOption(
            CaptureRequest.EDGE_MODE,
            if (sharpen) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_OFF
        )
        extender.setCaptureRequestOption(
            CaptureRequest.NOISE_REDUCTION_MODE,
            if (denoise) CaptureRequest.NOISE_REDUCTION_MODE_FAST else CaptureRequest.NOISE_REDUCTION_MODE_OFF
        )
        return builder
    }
}
