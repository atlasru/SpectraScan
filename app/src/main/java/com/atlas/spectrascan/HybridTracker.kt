package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.hypot

internal data class RawObservation(
    val sourceTrackingId: Int?,
    val label: String,
    val confidence: Float,
    val normalizedBox: RectF,
    val fromBrightnessTracker: Boolean = false,
    val fromMotionTracker: Boolean = false
)

internal class HybridTracker {
    @Volatile
    var profile: TrackingProfile = TrackingProfile.BALANCED

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
        var fromMotionTracker: Boolean
    )

    private val tracks = linkedMapOf<Int, Track>()
    private var nextStableId = 1

    @Synchronized
    fun update(observations: List<RawObservation>, now: Long): List<DetectionTarget> {
        val unmatchedTrackIds = tracks.keys.toMutableSet()

        observations.forEach { observation ->
            val matchingTrack = findBestTrack(observation, unmatchedTrackIds)
            if (matchingTrack == null) {
                val stableId = nextStableId++
                tracks[stableId] = Track(
                    stableId = stableId,
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
                    fromMotionTracker = observation.fromMotionTracker
                )
            } else {
                unmatchedTrackIds.remove(matchingTrack.stableId)
                updateObservedTrack(matchingTrack, observation, now)
            }
        }

        unmatchedTrackIds.forEach { id ->
            tracks[id]?.let { track ->
                track.consecutiveHits = 0
                predictMissingTrack(track, now)
            }
        }

        tracks.entries.removeAll { (_, track) ->
            val missingFor = now - track.lastSeenAt
            if (track.confirmed) missingFor > profile.holdMs else missingFor > 500L
        }

        return tracks.values.mapNotNull { track ->
            val requiredHits = requiredConfirmationHits(track)
            if (!track.confirmed && track.consecutiveHits >= requiredHits) {
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
                fromMotionTracker = track.fromMotionTracker
            )
        }.sortedBy { it.trackingId }
    }

    @Synchronized
    fun reset() {
        tracks.clear()
        nextStableId = 1
    }

    private fun requiredConfirmationHits(track: Track): Int = when {
        track.fromMotionTracker -> 2
        track.fromBrightnessTracker -> 2
        track.label in FAST_CONFIRM_LABELS -> 2
        else -> 3
    }

    private fun findBestTrack(
        observation: RawObservation,
        availableIds: Set<Int>
    ): Track? {
        if (observation.sourceTrackingId != null) {
            tracks.values.firstOrNull {
                it.stableId in availableIds && it.sourceTrackingId == observation.sourceTrackingId
            }?.let { return it }
        }

        var best: Track? = null
        var bestScore = Float.NEGATIVE_INFINITY
        tracks.values.forEach { track ->
            if (track.stableId !in availableIds) return@forEach
            val overlap = intersectionOverUnion(track.box, observation.normalizedBox)
            val distance = centerDistance(track.box, observation.normalizedBox)
            if (overlap < 0.06f && distance > 0.24f) return@forEach

            var score = overlap * 1.9f - distance
            if (track.label == observation.label) score += 0.25f
            if (track.fromBrightnessTracker == observation.fromBrightnessTracker) score += 0.10f
            if (track.fromMotionTracker == observation.fromMotionTracker) score += 0.18f
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    private fun updateObservedTrack(track: Track, observation: RawObservation, now: Long) {
        val dtSeconds = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.6f)
        val previousCenterX = track.box.centerX()
        val previousCenterY = track.box.centerY()
        val measuredVelocityX = (observation.normalizedBox.centerX() - previousCenterX) / dtSeconds
        val measuredVelocityY = (observation.normalizedBox.centerY() - previousCenterY) / dtSeconds

        track.velocityX = track.velocityX * 0.58f + measuredVelocityX * 0.42f
        track.velocityY = track.velocityY * 0.58f + measuredVelocityY * 0.42f
        track.box = lerpRect(track.box, observation.normalizedBox, profile.smoothing)
        track.sourceTrackingId = observation.sourceTrackingId ?: track.sourceTrackingId

        // A semantic YOLO label wins over a generic motion label when both channels converge.
        if (track.fromMotionTracker && !observation.fromMotionTracker && observation.label != "MOTION") {
            track.label = observation.label
        } else if (!track.fromMotionTracker || observation.fromMotionTracker) {
            track.label = observation.label
        }

        track.confidence = maxOf(track.confidence * 0.72f, observation.confidence)
        track.lastSeenAt = now
        track.updatedAt = now
        track.hits += 1
        track.consecutiveHits += 1
        track.fromBrightnessTracker = observation.fromBrightnessTracker
        track.fromMotionTracker = observation.fromMotionTracker
    }

    private fun predictMissingTrack(track: Track, now: Long) {
        val dtSeconds = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.25f)
        val age = now - track.lastSeenAt
        if (age <= profile.holdMs) {
            track.box = shiftAndClamp(track.box, track.velocityX * dtSeconds, track.velocityY * dtSeconds)
            track.velocityX *= 0.86f
            track.velocityY *= 0.86f
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
        val width = source.width().coerceIn(0.01f, 1f)
        val height = source.height().coerceIn(0.01f, 1f)
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
            "CAT", "DOG", "BIRD", "HORSE", "SHEEP", "COW", "ELEPHANT", "BEAR", "ZEBRA", "GIRAFFE"
        )
    }
}
