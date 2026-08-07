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
    private val motionDetector = MotionFlowDetector()
    private val semanticFlow = SemanticFlowTracker()
    private val yoloDetector = lazy { YoloDetector(SpectraScanApplication.appContext) }

    private var lastResultAt = 0L
    private var lastYoloAt = 0L
    private var previousYoloAt = 0L
    private var lastYoloMs = 0L
    private var lastYoloFps = 0
    private var lastTargets: List<DetectionTarget> = emptyList()

    @Volatile private var targetFilter: TargetFilter = TargetFilter.ALL
    @Volatile private var digitalGain: Float = 1.0f
    @Volatile private var motionDetectionEnabled: Boolean = false

    fun setProfile(profile: TrackingProfile) { tracker.profile = profile }

    fun setTargetFilter(filter: TargetFilter) {
        if (targetFilter != filter) {
            targetFilter = filter
            tracker.reset()
            motionDetector.reset()
            semanticFlow.reset()
            lastTargets = emptyList()
            lastYoloAt = 0L
        }
    }

    fun setDigitalGain(gain: Float) { digitalGain = gain.coerceIn(1.0f, 2.4f) }

    fun setMotionDetectionEnabled(enabled: Boolean) {
        if (motionDetectionEnabled != enabled) {
            motionDetectionEnabled = enabled
            motionDetector.reset()
            // Keep semantic tracks when toggling motion. The two channels are independent.
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
            val meanLuma = calculateMeanLuma(imageProxy)
            val lowLight = meanLuma < 58f
            val nightVisionSuggested = meanLuma < 40f
            val now = SystemClock.elapsedRealtime()

            val motionResult = if (motionDetectionEnabled) {
                motionDetector.analyze(imageProxy, rotation)
            } else {
                MotionFlowDetector.Result(emptyList(), 0f, 0f, false)
            }

            // Cheap local appearance tracking runs between YOLO passes. It only searches
            // small regions around previously confirmed semantic targets.
            val flowResult = semanticFlow.track(imageProxy, rotation, now)

            // Legacy class-agnostic motion mode remains supported, though the UI no
            // longer cycles into it.
            if (activeFilter == TargetFilter.MOTION) {
                val targets = tracker.update(motionResult.observations, now)
                lastTargets = targets
                dispatchFrame(
                    targets = targets,
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    now = now,
                    brightTrackerActive = false,
                    motionTrackerActive = motionDetectionEnabled && motionResult.active,
                    hybridFlowActive = false,
                    activeFilter = activeFilter,
                    rejectedCandidates = 0,
                    meanLuma = meanLuma,
                    lowLight = lowLight,
                    nightVisionSuggested = nightVisionSuggested,
                    detectionThrottled = true
                )
                return
            }

            val yoloInterval = adaptiveYoloInterval(
                meanLuma = meanLuma,
                flow = flowResult,
                targets = lastTargets,
                motionActive = motionDetectionEnabled && motionResult.active
            )
            val yoloDue = lastYoloAt == 0L || now - lastYoloAt >= yoloInterval

            if (!yoloDue) {
                val observations = mergeLightweightObservations(
                    flowResult.observations,
                    if (motionDetectionEnabled) motionResult.observations else emptyList()
                )
                val targets = if (observations.isNotEmpty()) {
                    tracker.update(observations, now)
                } else {
                    tracker.update(emptyList(), now)
                }
                lastTargets = targets
                dispatchFrame(
                    targets = targets,
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    now = now,
                    brightTrackerActive = false,
                    motionTrackerActive = motionDetectionEnabled && motionResult.active,
                    hybridFlowActive = flowResult.active,
                    activeFilter = activeFilter,
                    rejectedCandidates = 0,
                    meanLuma = meanLuma,
                    lowLight = lowLight,
                    nightVisionSuggested = nightVisionSuggested,
                    detectionThrottled = true
                )
                return
            }

            previousYoloAt = lastYoloAt
            lastYoloAt = now

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

            val yoloStarted = SystemClock.elapsedRealtime()
            val detectionResult = try {
                yoloDetector.value.detect(orientedBitmap, activeFilter, effectiveGain)
            } finally {
                if (orientedBitmap !== cameraBitmap && !orientedBitmap.isRecycled) orientedBitmap.recycle()
                if (!cameraBitmap.isRecycled) cameraBitmap.recycle()
            }
            lastYoloMs = SystemClock.elapsedRealtime() - yoloStarted
            lastYoloFps = if (previousYoloAt <= 0L) 0 else {
                (1000L / max(1L, lastYoloAt - previousYoloAt)).toInt().coerceIn(0, 30)
            }

            val (detections, rejectedCandidates) = detectionResult
            val observations = detections.map { detection ->
                RawObservation(
                    sourceTrackingId = null,
                    label = detection.label,
                    confidence = detection.confidence,
                    normalizedBox = detection.normalizedBox
                )
            }.toMutableList()

            // A fresh semantic YOLO box wins. Local flow fills only objects not already
            // covered by the detector on this pass.
            flowResult.observations.forEach { flow ->
                val overlapsSemantic = observations.any {
                    intersectionOverUnion(it.normalizedBox, flow.normalizedBox) > 0.20f
                }
                if (!overlapsSemantic) observations += flow
            }

            if (motionDetectionEnabled) {
                motionResult.observations.forEach { motion ->
                    val overlapsKnown = observations.any {
                        intersectionOverUnion(it.normalizedBox, motion.normalizedBox) > 0.18f
                    }
                    if (!overlapsKnown) observations += motion
                }
            }

            if (brightObservation != null && observations.none {
                    intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.30f
                }) {
                observations += brightObservation
            }

            val afterYolo = SystemClock.elapsedRealtime()
            val targets = tracker.update(observations, afterYolo)
            lastTargets = targets

            // Save small appearance templates after a semantic correction. Subsequent
            // frames can now be tracked without another full-network inference.
            semanticFlow.seed(imageProxy, rotation, targets, afterYolo)

            dispatchFrame(
                targets = targets,
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                now = afterYolo,
                brightTrackerActive = brightObservation != null,
                motionTrackerActive = motionDetectionEnabled && motionResult.active,
                hybridFlowActive = flowResult.active,
                activeFilter = activeFilter,
                rejectedCandidates = rejectedCandidates,
                meanLuma = meanLuma,
                lowLight = lowLight,
                nightVisionSuggested = nightVisionSuggested,
                detectionThrottled = false
            )
        } catch (_: Throwable) {
            val now = SystemClock.elapsedRealtime()
            val targets = tracker.update(emptyList(), now)
            lastTargets = targets
            dispatchFrame(
                targets = targets,
                orientedWidth = orientedWidth,
                orientedHeight = orientedHeight,
                now = now,
                brightTrackerActive = false,
                motionTrackerActive = false,
                hybridFlowActive = false,
                activeFilter = activeFilter,
                rejectedCandidates = 0,
                meanLuma = 255f,
                lowLight = false,
                nightVisionSuggested = false,
                detectionThrottled = true
            )
        } finally {
            busy.set(false)
            imageProxy.close()
        }
    }

    /**
     * Balanced mobile scheduler:
     * - searching: ~2.5 FPS YOLO
     * - stable local track: ~1.2-1.7 FPS YOLO recheck
     * - weak/lost local track: temporarily 3-4 FPS
     * - darkness always lowers the detector rate further
     */
    private fun adaptiveYoloInterval(
        meanLuma: Float,
        flow: SemanticFlowTracker.Result,
        targets: List<DetectionTarget>,
        motionActive: Boolean
    ): Long {
        val trackingInterval = when {
            targets.isEmpty() -> if (motionActive) 320L else 400L
            flow.active && flow.averageScore >= 0.76f -> 850L
            flow.active && flow.averageScore >= 0.60f -> 650L
            targets.any { it.status == TrackStatus.LOST || it.status == TrackStatus.PREDICTED } -> 260L
            else -> 320L
        }
        val lowLightFloor = when {
            meanLuma < 22f -> 800L
            meanLuma < 38f -> 520L
            meanLuma < 58f -> 320L
            else -> 0L
        }
        return maxOf(trackingInterval, lowLightFloor)
    }

    private fun mergeLightweightObservations(
        flow: List<RawObservation>,
        motion: List<RawObservation>
    ): List<RawObservation> {
        if (flow.isEmpty()) return motion
        if (motion.isEmpty()) return flow
        val merged = flow.toMutableList()
        motion.forEach { candidate ->
            if (merged.none { intersectionOverUnion(it.normalizedBox, candidate.normalizedBox) > 0.18f }) {
                merged += candidate
            }
        }
        return merged
    }

    private fun dispatchFrame(
        targets: List<DetectionTarget>,
        orientedWidth: Int,
        orientedHeight: Int,
        now: Long,
        brightTrackerActive: Boolean,
        motionTrackerActive: Boolean,
        hybridFlowActive: Boolean,
        activeFilter: TargetFilter,
        rejectedCandidates: Int,
        meanLuma: Float,
        lowLight: Boolean,
        nightVisionSuggested: Boolean,
        detectionThrottled: Boolean
    ) {
        lastResultAt = now
        val frame = DetectionFrame(
            targets = targets,
            imageWidth = orientedWidth,
            imageHeight = orientedHeight,
            inferenceFps = lastYoloFps,
            inferenceMs = lastYoloMs,
            brightTrackerActive = brightTrackerActive,
            motionTrackerActive = motionTrackerActive,
            hybridFlowActive = hybridFlowActive,
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

    private fun calculateMeanLuma(imageProxy: ImageProxy): Float {
        val plane = imageProxy.planes.firstOrNull() ?: return 255f
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
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
                x += 8
            }
            y += 8
        }
        return if (samples == 0) 255f else sum.toFloat() / samples
    }

    private fun findBrightRegion(imageProxy: ImageProxy, rotation: Int, mean: Float): RawObservation? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (mean > 110f) return null
        val threshold = maxOf(182f, mean + 72f).toInt()
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var brightCount = 0
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    samples++
                    if ((buffer.get(index).toInt() and 0xFF) >= threshold) {
                        minX = minOf(minX, x)
                        minY = minOf(minY, y)
                        maxX = maxOf(maxX, x)
                        maxY = maxOf(maxY, y)
                        brightCount++
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
        motionDetector.reset()
        semanticFlow.reset()
        if (yoloDetector.isInitialized()) yoloDetector.value.close()
    }
}
