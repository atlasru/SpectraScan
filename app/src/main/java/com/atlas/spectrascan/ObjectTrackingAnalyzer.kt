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
    private val tracker = HybridTracker()
    private var lastResultAt = 0L

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    fun setProfile(profile: TrackingProfile) {
        tracker.profile = profile
    }

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

        val startedAt = SystemClock.elapsedRealtime()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val brightObservation = findBrightRegion(imageProxy, rotation)
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener(callbackExecutor) { detectedObjects ->
                val now = SystemClock.elapsedRealtime()
                val rawObservations = detectedObjects.map { detected ->
                    val box = detected.boundingBox
                    val bestLabel = detected.labels.maxByOrNull { it.confidence }
                    val label = bestLabel?.text
                        ?.takeIf { it.isNotBlank() }
                        ?.uppercase(Locale.US)
                        ?: "TARGET"

                    RawObservation(
                        sourceTrackingId = detected.trackingId,
                        label = label,
                        confidence = bestLabel?.confidence ?: 0f,
                        normalizedBox = RectF(
                            (box.left.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.top.toFloat() / orientedHeight).coerceIn(0f, 1f),
                            (box.right.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.bottom.toFloat() / orientedHeight).coerceIn(0f, 1f)
                        ),
                        fromBrightnessTracker = false
                    )
                }.toMutableList()

                if (brightObservation != null && rawObservations.none {
                        intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.28f
                    }
                ) {
                    rawObservations += brightObservation
                }

                dispatchFrame(
                    targets = tracker.update(rawObservations, now),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = now,
                    brightTrackerActive = brightObservation != null
                )
            }
            .addOnFailureListener(callbackExecutor) {
                val now = SystemClock.elapsedRealtime()
                val fallback = brightObservation?.let(::listOf).orEmpty()
                dispatchFrame(
                    targets = tracker.update(fallback, now),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = now,
                    brightTrackerActive = brightObservation != null
                )
            }
            .addOnCompleteListener(callbackExecutor) {
                busy.set(false)
                imageProxy.close()
            }
    }

    private fun dispatchFrame(
        targets: List<DetectionTarget>,
        orientedWidth: Int,
        orientedHeight: Int,
        startedAt: Long,
        now: Long,
        brightTrackerActive: Boolean
    ) {
        val frameDelta = if (lastResultAt == 0L) 0L else now - lastResultAt
        lastResultAt = now
        val fps = if (frameDelta <= 0L) 0 else {
            (1000L / max(1L, frameDelta)).toInt().coerceIn(0, 60)
        }

        onFrame(
            DetectionFrame(
                targets = targets,
                imageWidth = orientedWidth,
                imageHeight = orientedHeight,
                inferenceFps = fps,
                inferenceMs = now - startedAt,
                brightTrackerActive = brightTrackerActive
            )
        )
    }

    private fun findBrightRegion(imageProxy: ImageProxy, rotation: Int): RawObservation? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = 4

        var sum = 0L
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xFF
                    samples++
                }
                x += step
            }
            y += step
        }
        if (samples == 0) return null

        val mean = sum.toFloat() / samples
        if (mean > 105f) return null
        val threshold = maxOf(178f, mean + 68f).toInt()

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var brightCount = 0
        y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit() && (buffer.get(index).toInt() and 0xFF) >= threshold) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    brightCount++
                }
                x += step
            }
            y += step
        }

        val ratio = brightCount.toFloat() / samples.toFloat()
        if (maxX <= minX || maxY <= minY || ratio !in 0.0015f..0.22f) return null

        val rawRect = RectF(
            (minX.toFloat() / width - 0.025f).coerceIn(0f, 1f),
            (minY.toFloat() / height - 0.025f).coerceIn(0f, 1f),
            (maxX.toFloat() / width + 0.025f).coerceIn(0f, 1f),
            (maxY.toFloat() / height + 0.025f).coerceIn(0f, 1f)
        )
        val orientedRect = rotateNormalizedRect(rawRect, rotation)
        if (orientedRect.width() < 0.025f || orientedRect.height() < 0.025f) return null

        return RawObservation(
            sourceTrackingId = null,
            label = "BRIGHT OBJECT",
            confidence = (0.35f + ratio * 3.2f).coerceIn(0.35f, 0.88f),
            normalizedBox = orientedRect,
            fromBrightnessTracker = true
        )
    }

    private fun rotateNormalizedRect(rect: RectF, rotation: Int): RectF = when (rotation) {
        90 -> RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
        180 -> RectF(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
        270 -> RectF(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
        else -> RectF(rect)
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        tracker.reset()
        detector.close()
    }
}
