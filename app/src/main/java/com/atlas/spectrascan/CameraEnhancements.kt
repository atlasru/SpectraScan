package com.atlas.spectrascan

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview

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
