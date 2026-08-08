package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Smooths boxes for HUD presentation only. Called on every analyzer frame.
 *
 * Tracking algorithms are still the source of truth; this layer only controls
 * how quickly the visible rectangle catches up with their latest estimate.
 */
internal class PresentationTargetSmoother {
    private data class State(
        var box: RectF,
        var lastAt: Long,
        var lastSeenInputAt: Long
    )

    private val states = linkedMapOf<Int, State>()
    @Volatile private var profile: TrackingProfile = TrackingProfile.BALANCED

    fun setProfile(value: TrackingProfile) { profile = value }
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

                // Only impossible geometry snaps. Normal YOLO/LK corrections — even fairly
                // large ones — animate into place instead of visibly teleporting.
                if (centerJump > 0.62f || sizeRatio > 4.5f) {
                    state.box = RectF(desired)
                } else {
                    val baseResponse = when (profile) {
                        TrackingProfile.SMOOTH -> 9.5f
                        TrackingProfile.BALANCED -> 14.0f
                        TrackingProfile.RESPONSIVE -> 19.0f
                    }
                    val statusScale = when (target.status) {
                        TrackStatus.TRACKING -> 1.00f
                        TrackStatus.ACQUIRING -> 0.82f
                        TrackStatus.PREDICTED -> 0.72f
                        TrackStatus.LOST -> 0.60f
                    }

                    // Catch up faster when the detector makes a large correction, but keep
                    // several rendered intermediate positions so the eye sees movement.
                    val jumpBoost = when {
                        centerJump > 0.30f -> 2.10f
                        centerJump > 0.16f -> 1.70f
                        centerJump > 0.08f -> 1.35f
                        else -> 1.00f
                    }
                    val response = baseResponse * statusScale * jumpBoost
                    val maxAlpha = when (profile) {
                        TrackingProfile.SMOOTH -> 0.76f
                        TrackingProfile.BALANCED -> 0.84f
                        TrackingProfile.RESPONSIVE -> 0.88f
                    }
                    val alpha = (1f - exp(-response * dt)).coerceIn(0.035f, maxAlpha)

                    val lookAhead = when {
                        target.status != TrackStatus.TRACKING && target.status != TrackStatus.PREDICTED -> 0f
                        profile == TrackingProfile.RESPONSIVE -> 0.028f
                        profile == TrackingProfile.BALANCED -> 0.036f
                        else -> 0.045f
                    }
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
