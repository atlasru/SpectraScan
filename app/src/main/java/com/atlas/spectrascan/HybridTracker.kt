package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.hypot

internal data class RawObservation(
    val sourceTrackingId: Int?,
    val label: String,
    val confidence: Float,
    val normalizedBox: RectF,
    val fromBrightnessTracker: Boolean = false,
    val fromMotionTracker: Boolean = false,
    val fromFlowTracker: Boolean = false,
    val maskCells: List<MaskCell> = emptyList(),
    val maskQuality: Float = 0f
)

/**
 * Semantic detections remain authoritative for identity/class/confidence.
 * Local flow may update only geometry/velocity of an already-confirmed track.
 */
internal class HybridTracker {
    @Volatile var profile: TrackingProfile = TrackingProfile.BALANCED

    private data class Track(
        val stableId: Int,
        var sourceTrackingId: Int?,
        var label: String,
        var confidence: Float,
        var box: RectF,
        var velocityX: Float,
        var velocityY: Float,
        var lastSeenAt: Long,
        var lastGeometryAt: Long,
        var updatedAt: Long,
        var hits: Int,
        var consecutiveHits: Int,
        var confirmed: Boolean,
        var geometryFromFlow: Boolean,
        var fromBrightnessTracker: Boolean,
        var fromMotionTracker: Boolean,
        var maskCells: List<MaskCell>,
        var maskQuality: Float
    )

    private val tracks = linkedMapOf<Int, Track>()
    private var nextStableId = 1

    @Synchronized
    fun update(observations: List<RawObservation>, now: Long): List<DetectionTarget> {
        val unmatched = tracks.keys.toMutableSet()

        observations
            .filterNot { it.fromFlowTracker }
            .forEach { observation ->
                val matching = findBestTrack(observation, unmatched)
                if (matching == null) {
                    val id = nextStableId++
                    tracks[id] = Track(
                        stableId = id,
                        sourceTrackingId = observation.sourceTrackingId,
                        label = observation.label,
                        confidence = observation.confidence,
                        box = RectF(observation.normalizedBox),
                        velocityX = 0f,
                        velocityY = 0f,
                        lastSeenAt = now,
                        lastGeometryAt = now,
                        updatedAt = now,
                        hits = 1,
                        consecutiveHits = 1,
                        confirmed = false,
                        geometryFromFlow = false,
                        fromBrightnessTracker = observation.fromBrightnessTracker,
                        fromMotionTracker = observation.fromMotionTracker,
                        maskCells = observation.maskCells,
                        maskQuality = observation.maskQuality
                    )
                } else {
                    unmatched.remove(matching.stableId)
                    updateObservedTrack(matching, observation, now)
                }
            }

        unmatched.forEach { id ->
            tracks[id]?.let { track ->
                track.consecutiveHits = 0
                predictMissingTrack(track, now)
            }
        }

        pruneExpired(now)
        return buildTargets(now)
    }

    /**
     * Apply high-frequency visual tracking without changing semantic identity.
     * Unknown IDs and unconfirmed tracks are ignored by design.
     */
    @Synchronized
    fun applyFlow(observations: List<RawObservation>, now: Long): List<DetectionTarget> {
        observations.asSequence()
            .filter { it.fromFlowTracker }
            .forEach { observation ->
                val sourceId = observation.sourceTrackingId ?: return@forEach
                val track = tracks[sourceId]
                    ?: tracks.values.firstOrNull { it.sourceTrackingId == sourceId }
                    ?: return@forEach
                if (!track.confirmed) return@forEach
                updateFlowGeometry(track, observation, now)
            }
        pruneExpired(now)
        return buildTargets(now)
    }

    @Synchronized
    fun snapshot(now: Long): List<DetectionTarget> = buildTargets(now)

    @Synchronized
    fun reset() {
        tracks.clear()
        nextStableId = 1
    }

    private fun pruneExpired(now: Long) {
        tracks.entries.removeAll { (_, track) ->
            val missingFor = now - track.lastSeenAt
            if (track.confirmed) missingFor > profile.holdMs else missingFor > 500L
        }
    }

    private fun buildTargets(now: Long): List<DetectionTarget> = tracks.values.mapNotNull { track ->
        if (!track.confirmed && track.consecutiveHits >= requiredConfirmationHits(track)) {
            track.confirmed = true
        }
        if (!track.confirmed) return@mapNotNull null

        val missingFor = now - track.lastSeenAt
        val geometryAge = now - track.lastGeometryAt
        val flowFresh = track.geometryFromFlow &&
            geometryAge <= FLOW_FRESH_MS &&
            missingFor <= profile.holdMs
        val status = when {
            missingFor == 0L || flowFresh -> TrackStatus.TRACKING
            missingFor <= profile.predictionMs -> TrackStatus.PREDICTED
            else -> TrackStatus.LOST
        }
        val confidenceDecay = if (missingFor == 0L) 1f else {
            (1f - missingFor.toFloat() / profile.holdMs.toFloat()).coerceIn(0.15f, 1f)
        }

        DetectionTarget(
            trackingId = track.stableId,
            label = track.label,
            confidence = track.confidence * confidenceDecay,
            normalizedBox = RectF(track.box),
            status = status,
            missingForMs = missingFor,
            velocityX = track.velocityX,
            velocityY = track.velocityY,
            fromBrightnessTracker = track.fromBrightnessTracker,
            fromMotionTracker = track.fromMotionTracker,
            fromFlowTracker = flowFresh,
            maskCells = track.maskCells,
            maskQuality = track.maskQuality
        )
    }.sortedBy { it.trackingId }

    private fun requiredConfirmationHits(track: Track): Int = when {
        track.fromBrightnessTracker -> 2
        track.fromMotionTracker -> 2
        track.label in FAST_CONFIRM_LABELS -> 2
        else -> 3
    }

    private fun findBestTrack(observation: RawObservation, availableIds: Set<Int>): Track? {
        if (observation.sourceTrackingId != null) {
            tracks.values.firstOrNull {
                it.stableId in availableIds &&
                    (it.stableId == observation.sourceTrackingId || it.sourceTrackingId == observation.sourceTrackingId)
            }?.let { return it }
        }

        var best: Track? = null
        var bestScore = Float.NEGATIVE_INFINITY
        tracks.values.forEach { track ->
            if (track.stableId !in availableIds) return@forEach
            val overlap = intersectionOverUnion(track.box, observation.normalizedBox)
            val distance = centerDistance(track.box, observation.normalizedBox)
            val sameLabel = track.label == observation.label

            if (!observation.fromBrightnessTracker && !observation.fromMotionTracker &&
                !sameLabel && overlap < 0.10f && distance > 0.13f) return@forEach
            if (overlap < 0.045f && distance > 0.22f) return@forEach

            var score = overlap * 2.0f - distance * 1.15f
            if (sameLabel) score += 0.32f
            if (track.fromBrightnessTracker == observation.fromBrightnessTracker) score += 0.08f
            if (track.fromMotionTracker == observation.fromMotionTracker) score += 0.05f
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    private fun updateObservedTrack(track: Track, observation: RawObservation, now: Long) {
        val dt = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.60f)
        val prevCx = track.box.centerX()
        val prevCy = track.box.centerY()
        val measuredVx = (observation.normalizedBox.centerX() - prevCx) / dt
        val measuredVy = (observation.normalizedBox.centerY() - prevCy) / dt

        val mvx = measuredVx.coerceIn(-1.25f, 1.25f)
        val mvy = measuredVy.coerceIn(-1.25f, 1.25f)
        track.velocityX = track.velocityX * 0.48f + mvx * 0.52f
        track.velocityY = track.velocityY * 0.48f + mvy * 0.52f

        val centerAmount: Float
        val sizeAmount: Float
        when {
            observation.fromBrightnessTracker -> {
                centerAmount = minOf(profile.smoothing, 0.38f)
                sizeAmount = minOf(profile.smoothing, 0.30f)
            }
            observation.fromMotionTracker -> {
                centerAmount = minOf(profile.smoothing, 0.34f)
                sizeAmount = minOf(profile.smoothing, 0.26f)
            }
            else -> {
                centerAmount = when (profile) {
                    TrackingProfile.SMOOTH -> 0.80f
                    TrackingProfile.BALANCED -> 0.92f
                    TrackingProfile.RESPONSIVE -> 0.98f
                }
                sizeAmount = when (profile) {
                    TrackingProfile.SMOOTH -> 0.34f
                    TrackingProfile.BALANCED -> 0.44f
                    TrackingProfile.RESPONSIVE -> 0.58f
                }
            }
        }
        track.box = blendCenterAndSize(track.box, observation.normalizedBox, centerAmount, sizeAmount)

        track.sourceTrackingId = observation.sourceTrackingId ?: track.sourceTrackingId
        if (!observation.fromMotionTracker || track.label == "MOTION") track.label = observation.label
        track.confidence = observation.confidence
        track.lastSeenAt = now
        track.lastGeometryAt = now
        track.updatedAt = now
        track.hits += 1
        track.consecutiveHits += 1
        track.geometryFromFlow = false
        track.fromBrightnessTracker = observation.fromBrightnessTracker
        track.fromMotionTracker = observation.fromMotionTracker
        if (observation.maskCells.isNotEmpty()) {
            track.maskCells = observation.maskCells
            track.maskQuality = observation.maskQuality
        }
    }

    private fun updateFlowGeometry(track: Track, observation: RawObservation, now: Long) {
        val dt = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.20f)
        val prevCx = track.box.centerX()
        val prevCy = track.box.centerY()
        val measuredVx = ((observation.normalizedBox.centerX() - prevCx) / dt).coerceIn(-1.5f, 1.5f)
        val measuredVy = ((observation.normalizedBox.centerY() - prevCy) / dt).coerceIn(-1.5f, 1.5f)

        track.velocityX = track.velocityX * 0.30f + measuredVx * 0.70f
        track.velocityY = track.velocityY * 0.30f + measuredVy * 0.70f
        track.box = blendCenterAndSize(track.box, observation.normalizedBox, 1.0f, FLOW_SIZE_BLEND)
        track.lastGeometryAt = now
        track.updatedAt = now
        track.geometryFromFlow = true
    }

    private fun predictMissingTrack(track: Track, now: Long) {
        val dt = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.18f)
        val age = now - track.lastSeenAt
        if (age <= profile.holdMs) {
            track.box = shiftAndClamp(track.box, track.velocityX * dt, track.velocityY * dt)
            track.velocityX *= 0.86f
            track.velocityY *= 0.86f
            track.updatedAt = now
            track.geometryFromFlow = false
        }
    }

    private fun blendCenterAndSize(
        from: RectF,
        to: RectF,
        centerAmount: Float,
        sizeAmount: Float
    ): RectF {
        val cx = from.centerX() + (to.centerX() - from.centerX()) * centerAmount
        val cy = from.centerY() + (to.centerY() - from.centerY()) * centerAmount
        val width = (from.width() + (to.width() - from.width()) * sizeAmount).coerceIn(0.008f, 1f)
        val height = (from.height() + (to.height() - from.height()) * sizeAmount).coerceIn(0.008f, 1f)
        val halfW = width / 2f
        val halfH = height / 2f
        val clampedCx = cx.coerceIn(halfW, 1f - halfW)
        val clampedCy = cy.coerceIn(halfH, 1f - halfH)
        return RectF(clampedCx - halfW, clampedCy - halfH, clampedCx + halfW, clampedCy + halfH)
    }

    private fun shiftAndClamp(source: RectF, dx: Float, dy: Float): RectF {
        val width = source.width().coerceIn(0.008f, 1f)
        val height = source.height().coerceIn(0.008f, 1f)
        val left = (source.left + dx).coerceIn(0f, 1f - width)
        val top = (source.top + dy).coerceIn(0f, 1f - height)
        return RectF(left, top, left + width, top + height)
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

    private fun centerDistance(a: RectF, b: RectF): Float = hypot(
        a.centerX() - b.centerX(),
        a.centerY() - b.centerY()
    )

    private companion object {
        const val FLOW_FRESH_MS = 140L
        const val FLOW_SIZE_BLEND = 0.42f

        val FAST_CONFIRM_LABELS = setOf(
            "PERSON", "CELL PHONE", "TV", "LAPTOP", "REMOTE", "CLOCK",
            "CAT", "DOG", "BIRD", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA", "GIRAFFE",
            "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT"
        )
    }
}
