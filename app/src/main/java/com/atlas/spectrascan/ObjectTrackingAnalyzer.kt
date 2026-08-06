package com.atlas.spectrascan

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ObjectTrackingAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val busy = AtomicBoolean(false)
    private var lastResultAt = 0L

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            busy.set(false)
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener(callbackExecutor) { detectedObjects ->
                val now = SystemClock.elapsedRealtime()
                val frameDelta = if (lastResultAt == 0L) 0L else now - lastResultAt
                lastResultAt = now
                val fps = if (frameDelta <= 0L) 0 else (1000L / max(1L, frameDelta)).toInt().coerceIn(0, 60)

                val targets = detectedObjects.mapIndexed { index, detected ->
                    val box = detected.boundingBox
                    val bestLabel = detected.labels.maxByOrNull { it.confidence }
                    val label = bestLabel?.text
                        ?.takeIf { it.isNotBlank() }
                        ?.uppercase(Locale.US)
                        ?: "TARGET"
                    val confidence = bestLabel?.confidence ?: 0f

                    DetectionTarget(
                        trackingId = detected.trackingId ?: (10_000 + index),
                        label = label,
                        confidence = confidence,
                        normalizedBox = RectF(
                            (box.left.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.top.toFloat() / orientedHeight).coerceIn(0f, 1f),
                            (box.right.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.bottom.toFloat() / orientedHeight).coerceIn(0f, 1f)
                        )
                    )
                }

                onFrame(
                    DetectionFrame(
                        targets = targets,
                        imageWidth = orientedWidth,
                        imageHeight = orientedHeight,
                        inferenceFps = fps
                    )
                )
            }
            .addOnFailureListener(callbackExecutor) {
                onFrame(DetectionFrame(imageWidth = orientedWidth, imageHeight = orientedHeight))
            }
            .addOnCompleteListener(callbackExecutor) {
                busy.set(false)
                imageProxy.close()
            }
    }

    override fun close() {
        detector.close()
    }
}
