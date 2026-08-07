package com.atlas.spectrascan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ObjectTrackingAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val busy = AtomicBoolean(false)
    private val tracker = HybridTracker()
    private val yoloDetector = lazy { YoloDetector(SpectraScanApplication.appContext) }
    private var lastResultAt = 0L

    @Volatile
    private var targetFilter: TargetFilter = TargetFilter.ALL

    fun setProfile(profile: TrackingProfile) {
        tracker.profile = profile
    }

    fun setTargetFilter(filter: TargetFilter) {
        if (targetFilter != filter) {
            targetFilter = filter
            tracker.reset()
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val activeFilter = targetFilter

        try {
            val brightObservation = if (activeFilter == TargetFilter.ALL || activeFilter == TargetFilter.SCREENS) {
                findBrightRegion(imageProxy, rotation)
            } else {
                null
            }

            val cameraBitmap = imageProxy.toBitmap()
            val orientedBitmap = rotateBitmap(cameraBitmap, rotation)
            val (detections, rejectedCandidates) = yoloDetector.value.detect(orientedBitmap, activeFilter)

            val observations = detections.map { detection ->
                RawObservation(
                    sourceTrackingId = null,
                    label = detection.label,
                    confidence = detection.confidence,
                    normalizedBox = detection.normalizedBox,
                    fromBrightnessTracker = false
                )
            }.toMutableList()

            if (brightObservation != null && observations.none {
                    intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.30f
                }
            ) {
                observations += brightObservation
            }

            val now = SystemClock.elapsedRealtime()
            dispatchFrame(
                targets = tracker.update(observations, now),
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                startedAt = startedAt,
                now = now,
                brightTrackerActive = brightObservation != null,
                activeFilter = activeFilter,
                rejectedCandidates = rejectedCandidates
            )
        } catch (_: Throwable) {
            val now = SystemClock.elapsedRealtime()
            dispatchFrame(
                targets = tracker.update(emptyList(), now),
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                startedAt = startedAt,
                now = now,
                brightTrackerActive = false,
                activeFilter = activeFilter,
                rejectedCandidates = 0
            )
        } finally {
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
        brightTrackerActive: Boolean,
        activeFilter: TargetFilter,
        rejectedCandidates: Int
    ) {
        val frameDelta = if (lastResultAt == 0L) 0L else now - lastResultAt
        lastResultAt = now
        val fps = if (frameDelta <= 0L) 0 else {
            (1000L / max(1L, frameDelta)).toInt().coerceIn(0, 60)
        }

        val frame = DetectionFrame(
            targets = targets,
            imageWidth = orientedWidth,
            imageHeight = orientedHeight,
            inferenceFps = fps,
            inferenceMs = now - startedAt,
            brightTrackerActive = brightTrackerActive,
            targetFilter = activeFilter,
            rejectedCandidates = rejectedCandidates
        )
        callbackExecutor.execute { onFrame(frame) }
    }

    private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
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
        if (mean > 110f) return null
        val threshold = maxOf(182f, mean + 72f).toInt()

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
        if (maxX <= minX || maxY <= minY || ratio !in 0.0015f..0.10f) return null

        val rawRect = RectF(
            (minX.toFloat() / width - 0.02f).coerceIn(0f, 1f),
            (minY.toFloat() / height - 0.02f).coerceIn(0f, 1f),
            (maxX.toFloat() / width + 0.02f).coerceIn(0f, 1f),
            (maxY.toFloat() / height + 0.02f).coerceIn(0f, 1f)
        )
        val orientedRect = rotateNormalizedRect(rawRect, rotation)
        val area = orientedRect.width() * orientedRect.height()
        if (orientedRect.width() !in 0.02f..0.55f ||
            orientedRect.height() !in 0.02f..0.55f ||
            area !in 0.0008f..0.16f
        ) return null

        return RawObservation(
            sourceTrackingId = null,
            label = "BRIGHT OBJECT",
            confidence = (0.48f + ratio * 3.5f).coerceIn(0.48f, 0.90f),
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
        if (yoloDetector.isInitialized()) {
            yoloDetector.value.close()
        }
    }
}
