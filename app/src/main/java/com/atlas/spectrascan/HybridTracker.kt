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
    val fromFlowTracker: Boolean = false
)

internal class HybridTracker {
    @Volatile
    var profile: TrackingProfile = TrackingProfile.BALANCED

    /** Small 1-D constant-velocity Kalman filter. Two instances form the 2-D tracker. */
    private class AxisKalman(initialPosition: Float) {
        var position = initialPosition
            private set
        var velocity = 0f
            private set

        private var p00 = 0.08f
        private var p01 = 0f
        private var p10 = 0f
        private var p11 = 0.20f

        fun predict(dt: Float) {
            val d = dt.coerceIn(0.001f, 0.35f)
            position += velocity * d

            val qPos = 0.0007f + d * 0.0015f
            val qVel = 0.004f + d * 0.008f
            val n00 = p00 + d * (p01 + p10) + d * d * p11 + qPos
            val n01 = p01 + d * p11
            val n10 = p10 + d * p11
            val n11 = p11 + qVel
            p00 = n00
            p01 = n01
            p10 = n10
            p11 = n11
        }

        fun correct(measurement: Float, measurementNoise: Float) {
            val r = measurementNoise.coerceIn(0.001f, 0.20f)
            val innovation = measurement - position
            val s = p00 + r
            if (s <= 0f) return
            val k0 = p00 / s
            val k1 = p10 / s

            val oldP00 = p00
            val oldP01 = p01
            val oldP10 = p10
            val oldP11 = p11

            position += k0 * innovation
            velocity += k1 * innovation
            p00 = (1f - k0) * oldP00
            p01 = (1f - k0) * oldP01
            p10 = oldP10 - k1 * oldP00
            p11 = oldP11 - k1 * oldP01
        }
    }

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
        var fromFlowTracker: Boolean,
        val kalmanX: AxisKalman,
        val kalmanY: AxisKalman
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
                    fromMotionTracker = observation.fromMotionTracker,
                    fromFlowTracker = observation.fromFlowTracker,
                    kalmanX = AxisKalman(observation.normalizedBox.centerX()),
                    kalmanY = AxisKalman(observation.normalizedBox.centerY())
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

        return buildTargets(now)
    }

    @Synchronized
    fun snapshot(now: Long): List<DetectionTarget> = buildTargets(now)

    @Synchronized
    fun reset() {
        tracks.clear()
        nextStableId = 1
    }

    private fun buildTargets(now: Long): List<DetectionTarget> = tracks.values.mapNotNull { track ->
        val requiredHits = requiredConfirmationHits(track)
        val confirmationCount = if (track.fromMotionTracker || track.fromBrightnessTracker) {
            track.consecutiveHits
        } else {
            // Semantic detections may have cheap flow frames between YOLO rechecks.
            // Count repeated semantic/flow hits without demanding back-to-back YOLO frames.
            track.hits
        }
        if (!track.confirmed && confirmationCount >= requiredHits) {
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
            fromFlowTracker = track.fromFlowTracker
        )
    }.sortedBy { it.trackingId }

    private fun requiredConfirmationHits(track: Track): Int = when {
        track.fromFlowTracker -> 1
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
            // Local flow uses our stable id as a direct hint. Legacy sources may still
            // provide their own source id, so support both forms.
            tracks[observation.sourceTrackingId]?.takeIf { it.stableId in availableIds }?.let { return it }
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
            if (track.fromFlowTracker == observation.fromFlowTracker) score += 0.16f
            if (score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }

    private fun updateObservedTrack(track: Track, observation: RawObservation, now: Long) {
        val dtSeconds = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.35f)
        track.kalmanX.predict(dtSeconds)
        track.kalmanY.predict(dtSeconds)

        // Flow/template matches are noisier than YOLO boxes; semantic detections get
        // a stronger correction while local tracking remains smooth.
        val measurementNoise = when {
            observation.fromMotionTracker -> 0.050f
            observation.fromBrightnessTracker -> 0.045f
            observation.fromFlowTracker -> 0.030f
            else -> 0.012f
        }
        track.kalmanX.correct(observation.normalizedBox.centerX(), measurementNoise)
        track.kalmanY.correct(observation.normalizedBox.centerY(), measurementNoise)

        track.velocityX = track.kalmanX.velocity
        track.velocityY = track.kalmanY.velocity

        val width = track.box.width() + (observation.normalizedBox.width() - track.box.width()) * profile.smoothing
        val height = track.box.height() + (observation.normalizedBox.height() - track.box.height()) * profile.smoothing
        track.box = centeredAndClamp(track.kalmanX.position, track.kalmanY.position, width, height)
        track.sourceTrackingId = if (observation.fromFlowTracker) track.sourceTrackingId else observation.sourceTrackingId ?: track.sourceTrackingId

        // Local flow carries the remembered semantic label; generic motion should
        // never overwrite an already-known YOLO class.
        if (!observation.fromMotionTracker || track.label == "MOTION") {
            track.label = observation.label
        }

        track.confidence = if (observation.fromFlowTracker) {
            maxOf(track.confidence * 0.96f, observation.confidence)
        } else {
            maxOf(track.confidence * 0.72f, observation.confidence)
        }
        track.lastSeenAt = now
        track.updatedAt = now
        track.hits += 1
        track.consecutiveHits += 1
        track.fromBrightnessTracker = observation.fromBrightnessTracker
        track.fromMotionTracker = observation.fromMotionTracker
        track.fromFlowTracker = observation.fromFlowTracker
    }

    private fun predictMissingTrack(track: Track, now: Long) {
        val dtSeconds = ((now - track.updatedAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.25f)
        val age = now - track.lastSeenAt
        if (age <= profile.holdMs) {
            track.kalmanX.predict(dtSeconds)
            track.kalmanY.predict(dtSeconds)
            track.velocityX = track.kalmanX.velocity
            track.velocityY = track.kalmanY.velocity
            track.box = centeredAndClamp(
                track.kalmanX.position,
                track.kalmanY.position,
                track.box.width(),
                track.box.height()
            )
            track.updatedAt = now
        }
    }

    private fun centeredAndClamp(cx: Float, cy: Float, widthIn: Float, heightIn: Float): RectF {
        val width = widthIn.coerceIn(0.008f, 0.98f)
        val height = heightIn.coerceIn(0.008f, 0.98f)
        val left = (cx - width / 2f).coerceIn(0f, 1f - width)
        val top = (cy - height / 2f).coerceIn(0f, 1f - height)
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
