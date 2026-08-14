package com.atlas.spectrascan

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot
import kotlin.math.max

/**
 * Stable legacy-first semantic pipeline with an optional lock-only precision bridge.
 *
 * Normal tracking remains the proven 0.11.6/0.15.x path: YOLO/bright detections are
 * authoritative and HybridTracker only associates/smooths/predicts them. When the user
 * explicitly enables Precision Lock, sparse LK is allowed to move only the selected
 * target between YOLO anchors. LK is never fed back into HybridTracker and can never
 * create a new semantic target.
 */
class ObjectTrackingAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val busy = AtomicBoolean(false)
    private val tracker = HybridTracker()
    private val motionDetector = MotionFlowDetector()
    private val presentationSmoother = PresentationTargetSmoother()
    private val precisionTracker = SparseFeatureTracker()
    private val yoloDetector = lazy { YoloDetector(SpectraScanApplication.appContext) }

    private var lastResultAt = 0L
    private var lastYoloAt = 0L
    private var previousYoloAt = 0L
    private var lastYoloMs = 0L
    private var lastYoloFps = 0
    private var lastZoomGeneration = 0L

    private var precisionRequestedId: Int? = null
    private var precisionSeeded = false
    private var precisionLastBox: RectF? = null
    private var precisionLastGoodAt = 0L

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
        PrecisionLockControl071.clearSelection()
        resetTrackingState()
    }

    fun setDigitalGain(gain: Float) {
        digitalGain = gain.coerceIn(1f, 2.4f)
    }

    fun setMotionDetectionEnabled(enabled: Boolean) {
        if (motionDetectionEnabled != enabled) {
            motionDetectionEnabled = enabled
            motionDetector.reset()
            PrecisionLockControl071.clearSelection()
            resetPrecisionBridge()
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
        resetPrecisionBridge()
        lastYoloAt = 0L
        previousYoloAt = 0L
        lastYoloFps = 0
    }

    private fun resetPrecisionBridge() {
        precisionTracker.reset()
        precisionRequestedId = null
        precisionSeeded = false
        precisionLastBox = null
        precisionLastGoodAt = 0L
    }

    private fun syncPrecisionSelection(selection: PrecisionLockControl071.Selection?): Boolean {
        val nextId = selection?.trackingId
        if (nextId == precisionRequestedId) return false
        precisionTracker.reset()
        precisionRequestedId = nextId
        precisionSeeded = false
        precisionLastBox = selection?.box()
        precisionLastGoodAt = 0L
        if (selection == null) {
            PrecisionLockControl071.publish(null, "IDLE", 0f)
        } else {
            PrecisionLockControl071.publish(selection.trackingId, "ARMED", 0f)
        }
        return true
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

            val precisionSelection = if (PrecisionLockControl071.mode() == LockTrackingMode071.PRECISION) {
                PrecisionLockControl071.selection()
            } else null
            val precisionChanged = syncPrecisionSelection(precisionSelection)

            val zoomGeneration = ZoomBoostSignal.generation()
            val zoomChanged = zoomGeneration != lastZoomGeneration
            if (zoomChanged) {
                lastZoomGeneration = zoomGeneration
                presentationSmoother.reset()
                precisionTracker.reset()
                precisionSeeded = false
                precisionLastBox = precisionSelection?.box()
                precisionLastGoodAt = 0L
                lastYoloAt = 0L
            }
            val zoomBoost = ZoomBoostSignal.isActive(now)

            // The precision bridge runs only for the explicit selected ID. It reads the
            // same ImageProxy but never modifies semantic tracker state.
            val flowResult = if (precisionSelection != null && precisionSeeded && !zoomChanged) {
                precisionTracker.track(imageProxy, rotation, now)
            } else null
            val flowObservation = flowResult?.observations
                ?.firstOrNull { it.sourceTrackingId == precisionSelection?.trackingId }
            val flowScore = flowResult?.averageScore ?: 0f
            val flowGood = flowObservation != null && flowScore >= MIN_PRECISION_FLOW_SCORE
            if (flowGood) {
                precisionLastBox = RectF(flowObservation!!.normalizedBox)
                precisionLastGoodAt = now
            }

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
                    throttled = true, precisionActive = false
                )
                return
            }

            val precisionActive = precisionSelection != null
            val interval = yoloInterval(meanLuma, zoomBoost, precisionActive)
            val yoloDue = zoomBoost || precisionChanged ||
                (precisionActive && !precisionSeeded) ||
                (flowResult?.needsYoloRecheck == true) ||
                lastYoloAt == 0L || now - lastYoloAt >= interval

            if (!yoloDue) {
                val predicted = tracker.update(emptyList(), now)
                val bridged = if (precisionSelection != null) {
                    applyPrecisionBetweenAnchors(predicted, precisionSelection, flowObservation, flowScore, now)
                } else predicted
                dispatchFrame(
                    bridged, orientedWidth, orientedHeight, now,
                    bright = false, motion = motionResult.active, filter = activeFilter,
                    rejected = 0, luma = meanLuma, low = lowLight, nv = nightVisionSuggested,
                    throttled = true, precisionActive = flowGood
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

            val semanticTargets = tracker.update(observations, afterYolo)
            val targets = if (precisionSelection != null) {
                applyPrecisionYoloAnchor(
                    semanticTargets,
                    precisionSelection,
                    flowObservation,
                    flowScore,
                    imageProxy,
                    rotation,
                    afterYolo
                )
            } else semanticTargets

            dispatchFrame(
                targets, orientedWidth, orientedHeight, afterYolo,
                bright = brightObservation != null, motion = motionResult.active, filter = activeFilter,
                rejected = rejected, luma = meanLuma, low = lowLight, nv = nightVisionSuggested,
                throttled = false, precisionActive = precisionSelection != null && precisionSeeded
            )
        } catch (_: Throwable) {
            val now = SystemClock.elapsedRealtime()
            val predicted = tracker.update(emptyList(), now)
            dispatchFrame(
                predicted, orientedWidth, orientedHeight, now,
                bright = false, motion = false, filter = activeFilter,
                rejected = 0, luma = 255f, low = false, nv = false, throttled = true,
                precisionActive = false
            )
        } finally {
            busy.set(false)
            imageProxy.close()
        }
    }

    private fun applyPrecisionBetweenAnchors(
        targets: List<DetectionTarget>,
        selection: PrecisionLockControl071.Selection,
        flow: RawObservation?,
        flowScore: Float,
        now: Long
    ): List<DetectionTarget> {
        if (flow != null && flowScore >= MIN_PRECISION_FLOW_SCORE) {
            val template = targets.firstOrNull { it.trackingId == selection.trackingId }
            val locked = (template ?: DetectionTarget(
                trackingId = selection.trackingId,
                label = selection.label,
                confidence = selection.confidence,
                normalizedBox = RectF(flow.normalizedBox),
                status = TrackStatus.TRACKING
            )).copy(
                trackingId = selection.trackingId,
                label = selection.label,
                confidence = maxOf(template?.confidence ?: 0f, selection.confidence * flowScore.coerceAtLeast(.55f)),
                normalizedBox = RectF(flow.normalizedBox),
                status = TrackStatus.TRACKING,
                missingForMs = 0L,
                fromFlowTracker = true
            )
            precisionLastBox = RectF(flow.normalizedBox)
            precisionLastGoodAt = now
            PrecisionLockControl071.publish(selection.trackingId, "FLOW", flowScore)
            return replaceLockedTarget(targets, selection.trackingId, null, locked)
        }

        // Keep the last precision position briefly while requesting a fresh semantic
        // anchor. This avoids a one-frame disappearance but never runs away indefinitely.
        val age = if (precisionLastGoodAt > 0L) now - precisionLastGoodAt else Long.MAX_VALUE
        val last = precisionLastBox
        if (last != null && age <= PRECISION_HOLD_MS) {
            val template = targets.firstOrNull { it.trackingId == selection.trackingId }
            val held = (template ?: DetectionTarget(
                trackingId = selection.trackingId,
                label = selection.label,
                confidence = selection.confidence * .55f,
                normalizedBox = RectF(last),
                status = TrackStatus.PREDICTED
            )).copy(
                trackingId = selection.trackingId,
                label = selection.label,
                normalizedBox = RectF(last),
                status = TrackStatus.PREDICTED,
                missingForMs = age,
                fromFlowTracker = true
            )
            lastYoloAt = 0L
            PrecisionLockControl071.publish(selection.trackingId, "SEARCH", flowScore)
            return replaceLockedTarget(targets, selection.trackingId, null, held)
        }

        lastYoloAt = 0L
        PrecisionLockControl071.publish(selection.trackingId, "LOST", flowScore)
        return targets
    }

    private fun applyPrecisionYoloAnchor(
        targets: List<DetectionTarget>,
        selection: PrecisionLockControl071.Selection,
        flow: RawObservation?,
        flowScore: Float,
        image: ImageProxy,
        rotation: Int,
        now: Long
    ): List<DetectionTarget> {
        val reference = flow?.normalizedBox ?: precisionLastBox ?: selection.box()
        val candidate = findPrecisionCandidate(targets, selection, reference)

        if (candidate == null) {
            precisionSeeded = precisionSeeded && flow != null
            return applyPrecisionBetweenAnchors(targets, selection, flow, flowScore, now)
        }

        val anchoredBox = when {
            flow != null && flowScore >= MIN_PRECISION_FLOW_SCORE && boxesAgree(flow.normalizedBox, candidate.normalizedBox) ->
                lerpRect(flow.normalizedBox, candidate.normalizedBox, YOLO_CORRECTION_WEIGHT)
            precisionLastBox != null && boxesAgree(precisionLastBox!!, candidate.normalizedBox) ->
                lerpRect(precisionLastBox!!, candidate.normalizedBox, FALLBACK_CORRECTION_WEIGHT)
            else -> RectF(candidate.normalizedBox)
        }

        val locked = candidate.copy(
            trackingId = selection.trackingId,
            normalizedBox = anchoredBox,
            status = TrackStatus.TRACKING,
            missingForMs = 0L,
            fromFlowTracker = false
        )

        // Re-seed LK from the current image and the freshly corrected geometry. The
        // stable display ID remains the user's original lock ID even if HybridTracker
        // internally had to reacquire the same object with a different ID.
        precisionTracker.seed(image, rotation, listOf(locked), now)
        precisionSeeded = true
        precisionLastBox = RectF(anchoredBox)
        precisionLastGoodAt = now
        PrecisionLockControl071.publish(selection.trackingId, "ANCHORED", flowScore)
        return replaceLockedTarget(targets, selection.trackingId, candidate.trackingId, locked)
    }

    private fun findPrecisionCandidate(
        targets: List<DetectionTarget>,
        selection: PrecisionLockControl071.Selection,
        reference: RectF
    ): DetectionTarget? {
        targets.firstOrNull {
            it.trackingId == selection.trackingId &&
                it.status == TrackStatus.TRACKING &&
                it.label == selection.label
        }?.let { return it }

        val maxDistance = maxOf(
            MIN_REACQUIRE_DISTANCE,
            hypot(reference.width(), reference.height()) * .85f
        ).coerceAtMost(MAX_REACQUIRE_DISTANCE)

        return targets.asSequence()
            .filter { it.status == TrackStatus.TRACKING }
            .filter { !it.fromMotionTracker && !it.fromBrightnessTracker }
            .filter { it.label == selection.label }
            .mapNotNull { candidate ->
                val distance = centerDistance(reference, candidate.normalizedBox)
                if (distance > maxDistance) return@mapNotNull null
                val wr = candidate.normalizedBox.width() / reference.width().coerceAtLeast(.001f)
                val hr = candidate.normalizedBox.height() / reference.height().coerceAtLeast(.001f)
                if (wr !in .50f..2.0f || hr !in .50f..2.0f) return@mapNotNull null
                val overlap = intersectionOverUnion(reference, candidate.normalizedBox)
                val proximity = (1f - distance / maxDistance.coerceAtLeast(.001f)).coerceIn(0f, 1f)
                val score = overlap * .55f + proximity * .35f + candidate.confidence * .10f
                candidate to score
            }
            .filter { it.second >= MIN_REACQUIRE_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun replaceLockedTarget(
        targets: List<DetectionTarget>,
        lockedId: Int,
        semanticCandidateId: Int?,
        locked: DetectionTarget
    ): List<DetectionTarget> {
        val result = targets.filterNot {
            it.trackingId == lockedId ||
                (semanticCandidateId != null && semanticCandidateId != lockedId && it.trackingId == semanticCandidateId)
        }.toMutableList()
        result += locked
        return result.sortedBy { it.trackingId }
    }

    private fun boxesAgree(a: RectF, b: RectF): Boolean {
        val distance = centerDistance(a, b)
        val maxDistance = maxOf(.055f, hypot(a.width(), a.height()) * .65f).coerceAtMost(.14f)
        if (distance > maxDistance) return false
        val wr = b.width() / a.width().coerceAtLeast(.001f)
        val hr = b.height() / a.height().coerceAtLeast(.001f)
        return wr in .60f..1.65f && hr in .60f..1.65f
    }

    private fun lerpRect(from: RectF, to: RectF, amount: Float): RectF = RectF(
        from.left + (to.left - from.left) * amount,
        from.top + (to.top - from.top) * amount,
        from.right + (to.right - from.right) * amount,
        from.bottom + (to.bottom - from.bottom) * amount
    )

    private fun centerDistance(a: RectF, b: RectF): Float = hypot(
        a.centerX() - b.centerX(),
        a.centerY() - b.centerY()
    )

    private fun yoloInterval(meanLuma: Float, zoomBoost: Boolean, precisionActive: Boolean): Long {
        if (zoomBoost) return 0L
        if (precisionActive) {
            // Give LK enough camera frames to interpolate between semantic anchors.
            // If LK confidence drops, analyze() forces YOLO immediately regardless of
            // this interval.
            return when (powerProfile) {
                TrackingProfile.RESPONSIVE -> if (meanLuma < 24f) 380L else 280L
                TrackingProfile.BALANCED -> when {
                    meanLuma < 24f -> 620L
                    meanLuma < 40f -> 520L
                    else -> 420L
                }
                TrackingProfile.SMOOTH -> when {
                    meanLuma < 24f -> 950L
                    meanLuma < 40f -> 820L
                    else -> 650L
                }
            }
        }
        return when (powerProfile) {
            TrackingProfile.RESPONSIVE -> {
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
        throttled: Boolean,
        precisionActive: Boolean
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
            hybridFlowActive = precisionActive,
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
        precisionTracker.reset()
        presentationSmoother.reset()
        if (yoloDetector.isInitialized()) yoloDetector.value.close()
    }

    private companion object {
        const val MIN_PRECISION_FLOW_SCORE = .34f
        const val PRECISION_HOLD_MS = 900L
        const val YOLO_CORRECTION_WEIGHT = .22f
        const val FALLBACK_CORRECTION_WEIGHT = .42f
        const val MIN_REACQUIRE_DISTANCE = .07f
        const val MAX_REACQUIRE_DISTANCE = .20f
        const val MIN_REACQUIRE_SCORE = .42f
    }
}
