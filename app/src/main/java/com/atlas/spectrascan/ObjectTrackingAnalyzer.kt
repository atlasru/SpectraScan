package com.atlas.spectrascan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
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
    private val motionDetector = MotionFlowDetector()
    private val yoloDetector = lazy { YoloDetector(SpectraScanApplication.appContext) }
    private var lastResultAt = 0L
    private var lastYoloAt = 0L

    @Volatile private var targetFilter: TargetFilter = TargetFilter.ALL
    @Volatile private var digitalGain: Float = 1.0f
    @Volatile private var motionDetectionEnabled: Boolean = false

    fun setProfile(profile: TrackingProfile) { tracker.profile = profile }

    fun setTargetFilter(filter: TargetFilter) {
        if (targetFilter != filter) {
            targetFilter = filter
            tracker.reset()
            motionDetector.reset()
        }
    }

    fun setDigitalGain(gain: Float) { digitalGain = gain.coerceIn(1.0f, 2.4f) }

    fun setMotionDetectionEnabled(enabled: Boolean) {
        if (motionDetectionEnabled != enabled) {
            motionDetectionEnabled = enabled
            motionDetector.reset()
            tracker.reset()
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) { imageProxy.close(); return }

        val startedAt = SystemClock.elapsedRealtime()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val activeFilter = targetFilter

        try {
            val meanLuma = calculateMeanLuma(imageProxy)
            val lowLight = meanLuma < 58f
            val nightVisionSuggested = meanLuma < 40f

            val motionResult = if (motionDetectionEnabled) {
                motionDetector.analyze(imageProxy, rotation)
            } else {
                motionDetector.reset()
                MotionFlowDetector.Result(emptyList(), 0f, 0f, false)
            }
            val motionObservations = motionResult.observations

            // Backwards compatibility only: an old saved MOTION filter behaves like
            // class-agnostic motion mode, but the UI no longer cycles into this value.
            if (activeFilter == TargetFilter.MOTION) {
                val now = SystemClock.elapsedRealtime()
                dispatchFrame(
                    targets = tracker.update(motionObservations, now),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = now,
                    brightTrackerActive = false,
                    motionTrackerActive = motionDetectionEnabled && motionResult.active,
                    activeFilter = activeFilter,
                    rejectedCandidates = 0,
                    meanLuma = meanLuma,
                    lowLight = lowLight,
                    nightVisionSuggested = nightVisionSuggested,
                    detectionThrottled = false,
                    preserveTracker = false
                )
                return
            }

            val minYoloInterval = when {
                meanLuma < 22f -> 700L
                meanLuma < 38f -> 420L
                meanLuma < 58f -> 240L
                else -> 0L
            }
            val nowBeforeYolo = SystemClock.elapsedRealtime()
            val throttled = minYoloInterval > 0L && nowBeforeYolo - lastYoloAt < minYoloInterval

            if (throttled) {
                val hasMotion = motionDetectionEnabled && motionObservations.isNotEmpty()
                dispatchFrame(
                    targets = if (hasMotion) tracker.update(motionObservations, nowBeforeYolo) else emptyList(),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = nowBeforeYolo,
                    brightTrackerActive = false,
                    motionTrackerActive = motionDetectionEnabled && motionResult.active,
                    activeFilter = activeFilter,
                    rejectedCandidates = 0,
                    meanLuma = meanLuma,
                    lowLight = lowLight,
                    nightVisionSuggested = nightVisionSuggested,
                    detectionThrottled = true,
                    preserveTracker = !hasMotion
                )
                return
            }
            lastYoloAt = nowBeforeYolo

            val brightObservation = if (activeFilter == TargetFilter.ALL || activeFilter == TargetFilter.SCREENS) {
                findBrightRegion(imageProxy, rotation, meanLuma)
            } else null

            val cameraBitmap = imageProxy.toBitmap()
            val orientedBitmap = rotateBitmap(cameraBitmap, rotation)
            val automaticGain = when {
                meanLuma < 28f -> 1.75f
                meanLuma < 45f -> 1.45f
                meanLuma < 70f -> 1.20f
                else -> 1.0f
            }
            val effectiveGain = maxOf(digitalGain, automaticGain)
            val detectorBitmap = if (effectiveGain > 1.01f) applyDigitalGain(orientedBitmap, effectiveGain) else orientedBitmap
            val (detections, rejectedCandidates) = yoloDetector.value.detect(detectorBitmap, activeFilter)

            val observations = detections.map { detection ->
                RawObservation(
                    sourceTrackingId = null,
                    label = detection.label,
                    confidence = detection.confidence,
                    normalizedBox = detection.normalizedBox
                )
            }.toMutableList()

            if (motionDetectionEnabled) {
                // Motion is complementary. If YOLO already covers the same object,
                // keep the semantic YOLO box instead of drawing a duplicate MOTION box.
                motionObservations.forEach { motion ->
                    val overlapsSemantic = observations.any {
                        intersectionOverUnion(it.normalizedBox, motion.normalizedBox) > 0.18f
                    }
                    if (!overlapsSemantic) observations += motion
                }
            }

            if (brightObservation != null && observations.none {
                    intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.30f
                }) {
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
                motionTrackerActive = motionDetectionEnabled && motionResult.active,
                activeFilter = activeFilter,
                rejectedCandidates = rejectedCandidates,
                meanLuma = meanLuma,
                lowLight = lowLight,
                nightVisionSuggested = nightVisionSuggested,
                detectionThrottled = false,
                preserveTracker = false
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
                motionTrackerActive = false,
                activeFilter = activeFilter,
                rejectedCandidates = 0,
                meanLuma = 255f,
                lowLight = false,
                nightVisionSuggested = false,
                detectionThrottled = false,
                preserveTracker = false
            )
        } finally {
            busy.set(false)
            imageProxy.close()
        }
    }

    private fun dispatchFrame(
        targets: List<DetectionTarget>, orientedWidth: Int, orientedHeight: Int,
        startedAt: Long, now: Long, brightTrackerActive: Boolean, motionTrackerActive: Boolean,
        activeFilter: TargetFilter, rejectedCandidates: Int, meanLuma: Float,
        lowLight: Boolean, nightVisionSuggested: Boolean, detectionThrottled: Boolean,
        preserveTracker: Boolean
    ) {
        val frameDelta = if (lastResultAt == 0L) 0L else now - lastResultAt
        lastResultAt = now
        val fps = if (frameDelta <= 0L) 0 else (1000L / max(1L, frameDelta)).toInt().coerceIn(0, 60)
        val frame = DetectionFrame(
            targets = if (preserveTracker) emptyList() else targets,
            imageWidth = orientedWidth,
            imageHeight = orientedHeight,
            inferenceFps = fps,
            inferenceMs = now - startedAt,
            brightTrackerActive = brightTrackerActive,
            motionTrackerActive = motionTrackerActive,
            targetFilter = activeFilter,
            rejectedCandidates = rejectedCandidates,
            meanLuma = meanLuma,
            lowLight = lowLight,
            nightVisionSuggested = nightVisionSuggested,
            detectionThrottled = detectionThrottled
        )
        callbackExecutor.execute { onFrame(frame) }
    }

    private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyDigitalGain(source: Bitmap, gain: Float): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val matrix = ColorMatrix(floatArrayOf(
            gain,0f,0f,0f,0f, 0f,gain,0f,0f,0f, 0f,0f,gain,0f,0f, 0f,0f,0f,1f,0f
        ))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(result).drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun calculateMeanLuma(imageProxy: ImageProxy): Float {
        val plane = imageProxy.planes.firstOrNull() ?: return 255f
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width; val height = imageProxy.height
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        var sum = 0L; var samples = 0; var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) { sum += buffer.get(index).toInt() and 0xFF; samples++ }
                x += 8
            }
            y += 8
        }
        return if (samples == 0) 255f else sum.toFloat() / samples
    }

    private fun findBrightRegion(imageProxy: ImageProxy, rotation: Int, mean: Float): RawObservation? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width; val height = imageProxy.height
        val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        if (mean > 110f) return null
        val threshold = maxOf(182f, mean + 72f).toInt()
        var minX = width; var minY = height; var maxX = -1; var maxY = -1
        var brightCount = 0; var samples = 0; var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    samples++
                    if ((buffer.get(index).toInt() and 0xFF) >= threshold) {
                        minX = minOf(minX, x); minY = minOf(minY, y); maxX = maxOf(maxX, x); maxY = maxOf(maxY, y); brightCount++
                    }
                }
                x += 4
            }
            y += 4
        }
        if (samples == 0) return null
        val ratio = brightCount.toFloat() / samples
        if (maxX <= minX || maxY <= minY || ratio !in 0.0015f..0.10f) return null
        val rawRect = RectF(
            (minX.toFloat()/width-.02f).coerceIn(0f,1f), (minY.toFloat()/height-.02f).coerceIn(0f,1f),
            (maxX.toFloat()/width+.02f).coerceIn(0f,1f), (maxY.toFloat()/height+.02f).coerceIn(0f,1f)
        )
        val orientedRect = rotateNormalizedRect(rawRect, rotation)
        val area = orientedRect.width()*orientedRect.height()
        if (orientedRect.width() !in .02f.. .55f || orientedRect.height() !in .02f.. .55f || area !in .0008f.. .16f) return null
        return RawObservation(null, "BRIGHT OBJECT", (.48f + ratio*3.5f).coerceIn(.48f,.90f), orientedRect, fromBrightnessTracker = true)
    }

    private fun rotateNormalizedRect(rect: RectF, rotation: Int): RectF = when(rotation) {
        90 -> RectF(1f-rect.bottom, rect.left, 1f-rect.top, rect.right)
        180 -> RectF(1f-rect.right, 1f-rect.bottom, 1f-rect.left, 1f-rect.top)
        270 -> RectF(rect.top, 1f-rect.right, rect.bottom, 1f-rect.left)
        else -> RectF(rect)
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left=maxOf(a.left,b.left); val top=maxOf(a.top,b.top); val right=minOf(a.right,b.right); val bottom=minOf(a.bottom,b.bottom)
        if (right<=left || bottom<=top) return 0f
        val intersection=(right-left)*(bottom-top)
        val union=a.width()*a.height()+b.width()*b.height()-intersection
        return if (union<=0f) 0f else intersection/union
    }

    override fun close() {
        tracker.reset(); motionDetector.reset()
        if (yoloDetector.isInitialized()) yoloDetector.value.close()
    }
}
