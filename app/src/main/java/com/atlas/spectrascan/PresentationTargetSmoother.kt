package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.hypot

/** Smooths boxes for HUD presentation only. Called on every analyzer frame. */
internal class PresentationTargetSmoother {
    private data class State(
        var box: RectF,
        var lastAt: Long,
        var lastSeenInputAt: Long
    )

    private val states = linkedMapOf<Int, State>()

    fun reset() = states.clear()

    fun apply(targets: List<DetectionTarget>, now: Long): List<DetectionTarget> {
        val activeIds = targets.mapTo(mutableSetOf()) { it.trackingId }
        val result = targets.map { target ->
            val desired = target.normalizedBox
            val state = states[target.trackingId]
            if (state == null) {
                states[target.trackingId] = State(RectF(desired), now, now)
                target
            } else {
                val dt = ((now - state.lastAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.10f)
                state.lastAt = now
                state.lastSeenInputAt = now

                val centerJump = hypot(
                    desired.centerX() - state.box.centerX(),
                    desired.centerY() - state.box.centerY()
                )
                val sizeRatio = maxOf(
                    ratio(desired.width(), state.box.width()),
                    ratio(desired.height(), state.box.height())
                )

                if (centerJump > 0.22f || sizeRatio > 2.2f || target.status == TrackStatus.LOST) {
                    state.box = RectF(desired)
                } else {
                    // A critically damped-looking exponential response. With analyzer
                    // callbacks at camera rate, this appears much smoother than YOLO-rate boxes.
                    val response = when (target.status) {
                        TrackStatus.TRACKING -> 18f
                        TrackStatus.ACQUIRING -> 13f
                        TrackStatus.PREDICTED -> 9f
                        TrackStatus.LOST -> 24f
                    }
                    val alpha = (1f - exp(-response * dt)).coerceIn(0.06f, 0.92f)

                    // Very small look-ahead masks detector latency without allowing runaway drift.
                    val lookAhead = if (target.status == TrackStatus.TRACKING || target.status == TrackStatus.PREDICTED) 0.040f else 0f
                    val predicted = shift(desired, target.velocityX * lookAhead, target.velocityY * lookAhead)
                    state.box = RectF(
                        lerp(state.box.left, predicted.left, alpha),
                        lerp(state.box.top, predicted.top, alpha),
                        lerp(state.box.right, predicted.right, alpha),
                        lerp(state.box.bottom, predicted.bottom, alpha)
                    )
                }
                target.copy(normalizedBox = RectF(state.box))
            }
        }
        states.entries.removeAll { (id, state) -> id !in activeIds && now - state.lastSeenInputAt > 1_500L }
        return result
    }

    private fun shift(box: RectF, dx: Float, dy: Float): RectF {
        val w = box.width().coerceIn(0.002f, 1f)
        val h = box.height().coerceIn(0.002f, 1f)
        val left = (box.left + dx).coerceIn(0f, 1f - w)
        val top = (box.top + dy).coerceIn(0f, 1f - h)
        return RectF(left, top, left + w, top + h)
    }

    private fun ratio(a: Float, b: Float): Float {
        val lo = minOf(a, b).coerceAtLeast(0.0001f)
        return maxOf(a, b) / lo
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
