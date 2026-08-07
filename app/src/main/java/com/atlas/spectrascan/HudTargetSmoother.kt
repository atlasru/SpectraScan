package com.atlas.spectrascan

import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Presentation-only smoother for target boxes.
 *
 * Detector/tracker coordinates remain authoritative. This class only produces a
 * visually continuous box for the Compose HUD so a 2-5 FPS semantic detector
 * does not make overlays jump at the same frequency.
 */
internal class HudTargetSmoother {
    private data class VisualTrack(
        var shown: RectF,
        var target: RectF,
        var velocityX: Float,
        var velocityY: Float,
        var status: TrackStatus,
        var lastInputAt: Long,
        var lastStepAt: Long
    )

    private val tracks = linkedMapOf<Int, VisualTrack>()

    fun reset() = tracks.clear()

    fun update(targets: List<DetectionTarget>, now: Long = SystemClock.elapsedRealtime()) {
        val ids = targets.mapTo(mutableSetOf()) { it.trackingId }
        targets.forEach { t ->
            val incoming = RectF(t.normalizedBox)
            val existing = tracks[t.trackingId]
            if (existing == null) {
                tracks[t.trackingId] = VisualTrack(
                    shown = RectF(incoming),
                    target = RectF(incoming),
                    velocityX = t.velocityX,
                    velocityY = t.velocityY,
                    status = t.status,
                    lastInputAt = now,
                    lastStepAt = now
                )
            } else {
                val jump = hypot(
                    incoming.centerX() - existing.shown.centerX(),
                    incoming.centerY() - existing.shown.centerY()
                )
                val scaleJump = maxOf(
                    ratio(incoming.width(), existing.shown.width()),
                    ratio(incoming.height(), existing.shown.height())
                )

                // On a real reacquire / ID correction, snap quickly instead of
                // animating a box across half the screen.
                if (jump > 0.22f || scaleJump > 2.1f || t.status == TrackStatus.LOST) {
                    existing.shown = RectF(incoming)
                }
                existing.target = incoming
                existing.velocityX = t.velocityX
                existing.velocityY = t.velocityY
                existing.status = t.status
                existing.lastInputAt = now
            }
        }

        tracks.entries.removeAll { (id, track) ->
            id !in ids && now - track.lastInputAt > REMOVE_AFTER_MS
        }
    }

    fun visualTargets(targets: List<DetectionTarget>, now: Long = SystemClock.elapsedRealtime()): List<DetectionTarget> {
        update(targets, now)
        return targets.map { target ->
            val visual = tracks[target.trackingId] ?: return@map target
            step(visual, now)
            target.copy(normalizedBox = RectF(visual.shown))
        }
    }

    private fun step(track: VisualTrack, now: Long) {
        val dt = ((now - track.lastStepAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.050f)
        track.lastStepAt = now

        // Small velocity look-ahead hides some detector latency without letting
        // prediction run away. LOST targets never receive look-ahead.
        val lookAhead = if (track.status == TrackStatus.TRACKING || track.status == TrackStatus.PREDICTED) {
            LOOK_AHEAD_SEC
        } else 0f
        val desired = shiftClamped(track.target, track.velocityX * lookAhead, track.velocityY * lookAhead)

        val response = when (track.status) {
            TrackStatus.TRACKING -> TRACKING_RESPONSE
            TrackStatus.ACQUIRING -> ACQUIRING_RESPONSE
            TrackStatus.PREDICTED -> PREDICTED_RESPONSE
            TrackStatus.LOST -> LOST_RESPONSE
        }
        val alpha = (1f - exp(-response * dt)).coerceIn(0.05f, 0.88f)

        track.shown = RectF(
            lerp(track.shown.left, desired.left, alpha),
            lerp(track.shown.top, desired.top, alpha),
            lerp(track.shown.right, desired.right, alpha),
            lerp(track.shown.bottom, desired.bottom, alpha)
        )
    }

    private fun shiftClamped(box: RectF, dx: Float, dy: Float): RectF {
        val w = box.width().coerceIn(0.002f, 1f)
        val h = box.height().coerceIn(0.002f, 1f)
        val left = (box.left + dx).coerceIn(0f, 1f - w)
        val top = (box.top + dy).coerceIn(0f, 1f - h)
        return RectF(left, top, left + w, top + h)
    }

    private fun ratio(a: Float, b: Float): Float {
        val lo = minOf(a, b).coerceAtLeast(0.0001f)
        val hi = maxOf(a, b)
        return hi / lo
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val TRACKING_RESPONSE = 16f
        const val ACQUIRING_RESPONSE = 11f
        const val PREDICTED_RESPONSE = 8f
        const val LOST_RESPONSE = 24f
        const val LOOK_AHEAD_SEC = 0.055f
        const val REMOVE_AFTER_MS = 1_500L
    }
}
