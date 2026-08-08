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
 * Legacy-first tracker rebuilt from the stable 0.6.1 core.
 *
 * Semantic detections are authoritative. The tracker only associates detections,
 * smooths geometry/velocity and predicts briefly between detector passes. It does
 * not fuse competing CV measurements, which keeps the box deterministic.
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
        var updatedAt: Long,
        var hits: Int,
        var consecutiveHits: Int,
        var confirmed: Boolean,
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
                        updatedAt = now,
                        hits = 1,
                        consecutiveHits = 1,
                        confirmed = false,
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

        tracks.entries.removeAll { (_, track) ->
            val missingFor = now - track.lastSeenAt
            if (track.confirmed) missingFor > profile.holdMs else missingFor > 500L
        }

        return buildTargets(now)
    }

    @Synchronized
    fun snapshot(now: Long): List<DetectionTarget> = update(emptyList(), now)

    @Synchronized
    fun reset() {
        tracks.clear()
        nextStableId = 1
    }

    private fun buildTargets(now: Long): List<DetectionTarget> = tracks.values.mapNotNull { track ->
        if (!track.confirmed && track.consecutiveHits >= requiredConfirmationHits(track)) {
            track.confirmed = true
        }
        if (!track.confirmed) return@mapNotNull null

        val missingFor = now - track.lastSeenAt
        val status = when {
            missingFor == 0L -> TrackStatus.TRACKING
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
            fromFlowTracker = false,
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

            // 0.6.1 was deliberately permissive. Keep that character, but reject
            // obviously unrelated semantic detections before they can steal an ID.
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

        // Preserve the proven 0.6.1 velocity EMA, with a small outlier clamp.
        val mvx = measuredVx.coerceIn(-1.25f, 1.25f)
        val mvy = measuredVy.coerceIn(-1.25f, 1.25f)
        track.velocityX = track.velocityX * 0.58f + mvx * 0.42f
        track.velocityY = track.velocityY * 0.58f + mvy * 0.42f

        // Semantic YOLO geometry remains authoritative. Bright/motion helpers are
        // intentionally softer so they cannot drag a semantic box around.
        val amount = when {
            observation.fromBrightnessTracker -> minOf(profile.smoothing, 0.34f)
            observation.fromMotionTracker -> minOf(profile.smoothing, 0.28f)
            else -> profile.smoothing
        }
        track.box = lerpRect(track.box, observation.normalizedBox, amount)

        track.sourceTrackingId = observation.sourceTrackingId ?: track.sourceTrackingId
        if (!observation.fromMotionTracker || track.label == "MOTION") track.label = observation.label
        track.confidence = observation.confidence
        track.lastSeenAt = now
        track.updatedAt = now
        track.hits += 1
        track.consecutiveHits += 1
        track.fromBrightnessTracker = observation.fromBrightnessTracker
        track.fromMotionTracker = observation.fromMotionTracker
        if (observation.maskCells.isNotEmpty()) {
            track.maskCells = observation.maskCells
            track.maskQuality = observation.maskQuality
        }
    }

    private fun predictMissingTrack(track: Track, now: Long) {
        val dt = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.18f)
        val age = now - track.lastSeenAt
        if (age <= profile.holdMs) {
            // Short velocity prediction only. Decay is intentionally stronger than
            // the current Kalman branch so stale tracks cannot run away.
            track.box = shiftAndClamp(track.box, track.velocityX * dt, track.velocityY * dt)
            track.velocityX *= 0.82f
            track.velocityY *= 0.82f
            track.updatedAt = now
        }
    }

    private fun lerpRect(from: RectF, to: RectF, amount: Float): RectF = RectF(
        from.left + (to.left - from.left) * amount,
        from.top + (to.top - from.top) * amount,
        from.right + (to.right - from.right) * amount,
        from.bottom + (to.bottom - from.bottom) * amount
    )

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
        val FAST_CONFIRM_LABELS = setOf(
            "PERSON", "CELL PHONE", "TV", "LAPTOP", "REMOTE", "CLOCK",
            "CAT", "DOG", "BIRD", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA", "GIRAFFE",
            "CAR", "MOTORCYCLE", "AIRPLANE", "BUS", "TRAIN", "TRUCK", "BOAT"
        )
    }
}
