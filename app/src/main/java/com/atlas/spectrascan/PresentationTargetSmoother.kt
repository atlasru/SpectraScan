package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Adaptive One Euro presentation filter. Every target ID has independent center
 * and size filters; fast motion raises the center cutoff while box size remains
 * calmer. The flow geometry itself is never changed by this presentation layer.
 */
internal class PresentationTargetSmoother {
    private class TrackState(
        val x: OneEuroScalar,
        val y: OneEuroScalar,
        val w: OneEuroScalar,
        val h: OneEuroScalar,
        var lastSeenAt: Long
    )

    private val states = linkedMapOf<Int, TrackState>()
    @Volatile private var profile: TrackingProfile = TrackingProfile.BALANCED

    fun setProfile(value: TrackingProfile) { profile = value }
    fun reset() = states.clear()

    fun apply(targets: List<DetectionTarget>, now: Long): List<DetectionTarget> {
        val active = targets.mapTo(mutableSetOf()) { it.trackingId }
        val result = targets.map { target ->
            val input = target.normalizedBox
            val previous = states[target.trackingId]
            if (previous == null) {
                states[target.trackingId] = TrackState(
                    OneEuroScalar(input.centerX()), OneEuroScalar(input.centerY()),
                    OneEuroScalar(input.width()), OneEuroScalar(input.height()), now
                )
                target
            } else {
                val speed = hypot(target.velocityX, target.velocityY)
                val isFlow = target.fromFlowTracker && target.status == TrackStatus.TRACKING
                val centerCutoff = when (profile) {
                    TrackingProfile.RESPONSIVE -> 8f
                    TrackingProfile.BALANCED -> 6f
                    TrackingProfile.SMOOTH -> 4f
                } * if (isFlow) 1.40f else 1f
                val sizeCutoff = when (profile) {
                    TrackingProfile.RESPONSIVE -> 3.0f
                    TrackingProfile.BALANCED -> 2.2f
                    TrackingProfile.SMOOTH -> 1.5f
                }
                // Only cover a small estimated presentation age, never lead a
                // LOST track and never let velocity prediction run away.
                val lookAhead = if (target.status == TrackStatus.TRACKING ||
                    target.status == TrackStatus.PREDICTED
                ) (if (isFlow) .008f else .020f) else 0f
                val cx = (input.centerX() + target.velocityX * lookAhead).coerceIn(0f, 1f)
                val cy = (input.centerY() + target.velocityY * lookAhead).coerceIn(0f, 1f)
                val jump = hypot(cx - previous.x.value, cy - previous.y.value)
                val sizeJump = maxOf(
                    input.width() / previous.w.value.coerceAtLeast(.001f),
                    previous.w.value / input.width().coerceAtLeast(.001f),
                    input.height() / previous.h.value.coerceAtLeast(.001f),
                    previous.h.value / input.height().coerceAtLeast(.001f)
                )
                val width: Float
                val height: Float
                val sx: Float
                val sy: Float
                if (jump > .22f || sizeJump > 2.1f || target.status == TrackStatus.LOST) {
                    // Real reacquisition/large correction: no animated flight.
                    previous.x.reset(cx); previous.y.reset(cy)
                    previous.w.reset(input.width()); previous.h.reset(input.height())
                    sx = cx; sy = cy; width = input.width(); height = input.height()
                } else {
                    val beta = if (speed > .15f) 6f else 3f
                    sx = previous.x.update(cx, now, centerCutoff, beta)
                    sy = previous.y.update(cy, now, centerCutoff, beta)
                    width = previous.w.update(input.width(), now, sizeCutoff, 1.0f)
                    height = previous.h.update(input.height(), now, sizeCutoff, 1.0f)
                }
                previous.lastSeenAt = now
                target.copy(normalizedBox = fromCenter(sx, sy, width, height))
            }
        }
        states.entries.removeAll { (id, state) ->
            id !in active && now - state.lastSeenAt > 1_500L
        }
        return result
    }

    private fun fromCenter(cx: Float, cy: Float, width: Float, height: Float): RectF {
        val w = width.coerceIn(.002f, 1f)
        val h = height.coerceIn(.002f, 1f)
        val x = cx.coerceIn(w / 2f, 1f - w / 2f)
        val y = cy.coerceIn(h / 2f, 1f - h / 2f)
        return RectF(x - w / 2f, y - h / 2f, x + w / 2f, y + h / 2f)
    }
}

/**
 * Units are seconds and Hertz. Independent of Android API so it can be tested
 * with deterministic synthetic motion.
 */
internal class OneEuroScalar(initial: Float) {
    var value: Float = initial
        private set
    private var lastInput = initial
    private var lastAt: Long? = null
    private var derivative = 0f

    fun reset(x: Float) {
        value = x
        lastInput = x
        derivative = 0f
        lastAt = null
    }

    fun update(x: Float, timeMs: Long, minCutoff: Float, beta: Float): Float {
        val previous = lastAt
        lastAt = timeMs
        if (previous == null || timeMs <= previous) {
            value = x
            lastInput = x
            return value
        }
        val dt = ((timeMs - previous) / 1000f).coerceIn(.001f, .1f)
        val dx = (x - lastInput) / dt
        val derivativeAlpha = alpha(1f, dt)
        derivative += derivativeAlpha * (dx - derivative)
        lastInput = x
        val cutoff = minCutoff.coerceAtLeast(.01f) + beta.coerceAtLeast(0f) * abs(derivative)
        value += alpha(cutoff, dt) * (x - value)
        return value
    }

    private fun alpha(cutoff: Float, dt: Float): Float =
        (1f - exp((-2f * Math.PI.toFloat() * cutoff * dt).toDouble()).toFloat()).coerceIn(0f, 1f)
}
