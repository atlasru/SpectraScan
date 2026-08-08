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

/**
 * 0.11.6 control pipeline: rebuilt around the stable 0.6.1 philosophy.
 *
 * YOLO/bright detections are authoritative. Between detector passes the tracker
 * performs only short velocity prediction; no LK/local-motion/template fusion is
 * allowed to move a semantic target. This gives us a clean baseline for adding
 * one secondary method at a time later.
 */
class ObjectTrackingAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val busy = AtomicBoolean(false)
    private val tracker = HybridTracker()
    private val motionDetector = MotionFlowDetector()
    private val presentationSmoother = PresentationTargetSmoother()
    private val yoloDetector = lazy { YoloDetector(SpectraScanApplication.appContext) }

    private var lastResultAt = 0L
    private var lastYoloAt = 0L
    private var previousYoloAt = 0L
    private var lastYoloMs = 0L
    private var lastYoloFps = 0
    private var lastZoomGeneration = 0L

    @Volatile private var targetFilter = TargetFilter.ALL
    @Volatile private var digitalGain = 1f
    @Volatile private var motionDetectionEnabled = false
    @Volatile private var skyWatchEnabled = false
    @Volatile private var stationaryCamera = false
    @Volatile private var powerProfile = TrackingProfile.BALANCED

    fun setProfile(profile: TrackingProfile) {
        powerProfile = profile
        tracker.profile = profile
        presentationSmoother.setProfile(profile)
    }

    fun setTargetFilter(filter: TargetFilter) {
        if (targetFilter == filter) return
        targetFilter = filter
        skyWatchEnabled = filter == TargetFilter.SKY
        stationaryCamera = skyWatchEnabled
        motionDetector.setSkyWatch(skyWatchEnabled, stationaryCamera)
        resetTrackingState()
    }

    fun setDigitalGain(gain: Float) {
        digitalGain = gain.coerceIn(1f, 2.4f)
    }

    fun setMotionDetectionEnabled(enabled: Boolean) {
        if (motionDetectionEnabled != enabled) {
            motionDetectionEnabled = enabled
            motionDetector.reset()
        }
    }

    fun setSkyWatch(enabled: Boolean, stationary: Boolean) {
        if (skyWatchEnabled == enabled && stationaryCamera == (enabled && stationary)) return
        skyWatchEnabled = enabled
        stationaryCamera = enabled && stationary
        motionDetector.setSkyWatch(enabled, stationaryCamera)
        resetTrackingState()
    }

    private fun resetTrackingState() {
        tracker.reset()
        motionDetector.reset()
        presentationSmoother.reset()
        lastYoloAt = 0L
        previousYoloAt = 0L
        lastYoloFps = 0
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val activeFilter = targetFilter

        try {
            val now = SystemClock.elapsedRealtime()
            val meanLuma = calculateMeanLuma(imageProxy)
            val lowLight = meanLuma < 58f
            val nightVisionSuggested = meanLuma < 40f

            val zoomGeneration = ZoomBoostSignal.generation()
            val zoomChanged = zoomGeneration != lastZoomGeneration
            if (zoomChanged) {
                lastZoomGeneration = zoomGeneration
                presentationSmoother.reset()
                lastYoloAt = 0L
            }
            val zoomBoost = ZoomBoostSignal.isActive(now)

            // Global motion remains available only when the user explicitly asks for
            // MOTION/SKY behaviour. It is not mixed into ordinary semantic tracking.
            val useMotion = motionDetectionEnabled || skyWatchEnabled || activeFilter == TargetFilter.MOTION
            val motionResult = if (useMotion) {
                motionDetector.analyze(imageProxy, rotation)
            } else {
                MotionFlowDetector.Result(emptyList(), 0f, 0f, false)
            }

            if (activeFilter == TargetFilter.MOTION && !skyWatchEnabled) {
                val targets = tracker.update(motionResult.observations, now)
                dispatchFrame(
                    targets, orientedWidth, orientedHeight, now,
                    bright = false, motion = motionResult.active, filter = activeFilter,
                    rejected = 0, luma = meanLuma, low = lowLight, nv = nightVisionSuggested,
                    throttled = true
                )
                return
            }

            val interval = yoloInterval(meanLuma, zoomBoost)
            val yoloDue = zoomBoost || lastYoloAt == 0L || now - lastYoloAt >= interval

            if (!yoloDue) {
                // Improved 0.6.1 behaviour: do not freeze or inject secondary CV.
                // Let the simple legacy tracker coast briefly using its velocity EMA.
                val predicted = tracker.update(emptyList(), now)
                dispatchFrame(
                    predicted, orientedWidth, orientedHeight, now,
                    bright = false, motion = motionResult.active, filter = activeFilter,
                    rejected = 0, luma = meanLuma, low = lowLight, nv = nightVisionSuggested,
                    throttled = true
                )
                return
            }

            previousYoloAt = lastYoloAt
            lastYoloAt = now

            val brightObservation = if (!skyWatchEnabled &&
                (activeFilter == TargetFilter.ALL || activeFilter == TargetFilter.SCREENS)
            ) {
                findBrightRegion(imageProxy, rotation, meanLuma)
            } else null

            val cameraBitmap = imageProxy.toBitmap()
            val orientedBitmap = rotateBitmap(cameraBitmap, rotation)
            val automaticGain = when {
                meanLuma < 28f -> 1.75f
                meanLuma < 45f -> 1.45f
                meanLuma < 70f -> 1.20f
                else -> 1f
            }
            val effectiveGain = maxOf(digitalGain, automaticGain)

            val yoloStarted = SystemClock.elapsedRealtime()
            val detectionResult = try {
                yoloDetector.value.detect(orientedBitmap, activeFilter, effectiveGain)
            } finally {
                if (orientedBitmap !== cameraBitmap && !orientedBitmap.isRecycled) orientedBitmap.recycle()
                if (!cameraBitmap.isRecycled) cameraBitmap.recycle()
            }
            val afterYolo = SystemClock.elapsedRealtime()
            lastYoloMs = afterYolo - yoloStarted
            lastYoloFps = if (previousYoloAt <= 0L) 0 else {
                (1000L / max(1L, lastYoloAt - previousYoloAt)).toInt().coerceIn(0, 30)
            }

            val (detections, rejected) = detectionResult
            val observations = detections.map { detection ->
                RawObservation(
                    sourceTrackingId = null,
                    label = detection.label,
                    confidence = detection.confidence,
                    normalizedBox = detection.normalizedBox,
                    maskCells = detection.maskCells,
                    maskQuality = detection.maskQuality
                )
            }.toMutableList()

            // Bright-region helper is kept from the successful early versions.
            if (brightObservation != null && observations.none {
                    intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.30f
                }) {
                observations += brightObservation
            }

            // SKY still needs UNKNOWN motion candidates, but ordinary ALL/PEOPLE/etc.
            // never receive motion observations in this control build.
            if (skyWatchEnabled) {
                motionResult.observations.forEach { candidate ->
                    if (observations.none {
                            intersectionOverUnion(it.normalizedBox, candidate.normalizedBox) > 0.16f
                        }) {
                        observations += candidate
                    }
                }
            }

            val targets = tracker.update(observations, afterYolo)
            dispatchFrame(
                targets, orientedWidth, orientedHeight, afterYolo,
                bright = brightObservation != null, motion = motionResult.active, filter = activeFilter,
                rejected = rejected, luma = meanLuma, low = lowLight, nv = nightVisionSuggested,
                throttled = false
            )
        } catch (_: Throwable) {
            val now = SystemClock.elapsedRealtime()
            val predicted = tracker.update(emptyList(), now)
            dispatchFrame(
                predicted, orientedWidth, orientedHeight, now,
                bright = false, motion = false, filter = activeFilter,
                rejected = 0, luma = 255f, low = false, nv = false, throttled = true
            )
        } finally {
            busy.set(false)
            imageProxy.close()
        }
    }

    private fun yoloInterval(meanLuma: Float, zoomBoost: Boolean): Long {
        if (zoomBoost) return 0L
        return when (powerProfile) {
            TrackingProfile.RESPONSIVE -> {
                // Pure throughput mode. With ~220 ms inference this should naturally
                // land near 4-5 FPS because no secondary tracker consumes analyzer time.
                if (meanLuma < 24f) 120L else 0L
            }
            TrackingProfile.BALANCED -> {
                when {
                    meanLuma < 24f -> 520L
                    meanLuma < 40f -> 360L
                    meanLuma < 58f -> 280L
                    else -> 240L
                }
            }
            TrackingProfile.SMOOTH -> {
                when {
                    meanLuma < 24f -> 1_050L
                    meanLuma < 40f -> 820L
                    meanLuma < 58f -> 650L
                    else -> 520L
                }
            }
        }
    }

    private fun dispatchFrame(
        targets: List<DetectionTarget>,
        w: Int,
        h: Int,
        now: Long,
        bright: Boolean,
        motion: Boolean,
        filter: TargetFilter,
        rejected: Int,
        luma: Float,
        low: Boolean,
        nv: Boolean,
        throttled: Boolean
    ) {
        lastResultAt = now
        val presented = presentationSmoother.apply(targets, now)
        val frame = DetectionFrame(
            targets = presented,
            imageWidth = w,
            imageHeight = h,
            inferenceFps = lastYoloFps,
            inferenceMs = lastYoloMs,
            brightTrackerActive = bright,
            motionTrackerActive = motion,
            hybridFlowActive = false,
            targetFilter = filter,
            rejectedCandidates = rejected,
            meanLuma = luma,
            lowLight = low,
            nightVisionSuggested = nv,
            detectionThrottled = throttled,
            frameAtMs = now
        )
        callbackExecutor.execute { onFrame(frame) }
    }

    private fun rotateBitmap(source: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun calculateMeanLuma(image: ImageProxy): Float {
        val plane = image.planes.firstOrNull() ?: return 255f
        val buffer = plane.buffer.duplicate()
        var sum = 0L
        var samples = 0
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val index = y * plane.rowStride + x * plane.pixelStride
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

    private fun findBrightRegion(image: ImageProxy, rotation: Int, mean: Float): RawObservation? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = image.width
        val height = image.height
        if (mean > 110f) return null
        val threshold = maxOf(182f, mean + 72f).toInt()

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var count = 0
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                if (index < buffer.limit()) {
                    samples++
                    if ((buffer.get(index).toInt() and 0xFF) >= threshold) {
                        minX = minOf(minX, x)
                        minY = minOf(minY, y)
                        maxX = maxOf(maxX, x)
                        maxY = maxOf(maxY, y)
                        count++
                    }
                }
                x += 4
            }
            y += 4
        }
        if (samples == 0) return null
        val ratio = count.toFloat() / samples
        if (maxX <= minX || maxY <= minY || ratio !in 0.0015f..0.10f) return null

        val raw = RectF(
            (minX.toFloat() / width - 0.02f).coerceIn(0f, 1f),
            (minY.toFloat() / height - 0.02f).coerceIn(0f, 1f),
            (maxX.toFloat() / width + 0.02f).coerceIn(0f, 1f),
            (maxY.toFloat() / height + 0.02f).coerceIn(0f, 1f)
        )
        val rect = rotateNormalizedRect(raw, rotation)
        val area = rect.width() * rect.height()
        if (rect.width() !in 0.02f..0.55f || rect.height() !in 0.02f..0.55f || area !in 0.0008f..0.16f) return null

        return RawObservation(
            sourceTrackingId = null,
            label = "BRIGHT OBJECT",
            confidence = (0.48f + ratio * 3.5f).coerceIn(0.48f, 0.90f),
            normalizedBox = rect,
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
        presentationSmoother.reset()
        if (yoloDetector.isInitialized()) yoloDetector.value.close()
    }
}
