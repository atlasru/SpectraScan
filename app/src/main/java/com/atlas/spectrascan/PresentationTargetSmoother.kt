package com.atlas.spectrascan

import android.graphics.RectF
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Presentation-only smoothing. Center motion stays highly responsive while box size
 * is damped more strongly to hide detector edge jitter.
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

                if (centerJump > 0.42f || sizeRatio > 4.5f) {
                    state.box = RectF(desired)
                } else {
                    val centerResponse = when (profile) {
                        TrackingProfile.SMOOTH -> 28f
                        TrackingProfile.BALANCED -> 42f
                        TrackingProfile.RESPONSIVE -> 60f
                    } * when {
                        target.fromFlowTracker -> 1.18f
                        target.status == TrackStatus.PREDICTED -> 0.88f
                        target.status == TrackStatus.LOST -> 0.62f
                        else -> 1f
                    }
                    val sizeResponse = when (profile) {
                        TrackingProfile.SMOOTH -> 8.5f
                        TrackingProfile.BALANCED -> 12.5f
                        TrackingProfile.RESPONSIVE -> 18f
                    }

                    var centerAlpha = (1f - exp(-centerResponse * dt)).coerceIn(0.18f, 0.985f)
                    if (centerJump > 0.18f) centerAlpha = maxOf(centerAlpha, 0.94f)
                    else if (centerJump > 0.08f) centerAlpha = maxOf(centerAlpha, 0.82f)
                    val sizeAlpha = (1f - exp(-sizeResponse * dt)).coerceIn(0.06f, 0.72f)

                    val lookAhead = when {
                        target.fromFlowTracker -> 0.010f
                        target.status == TrackStatus.PREDICTED -> 0.035f
                        target.status == TrackStatus.TRACKING -> 0.018f
                        else -> 0f
                    }
                    val targetCx = desired.centerX() + target.velocityX * lookAhead
                    val targetCy = desired.centerY() + target.velocityY * lookAhead
                    val cx = lerp(state.box.centerX(), targetCx, centerAlpha)
                    val cy = lerp(state.box.centerY(), targetCy, centerAlpha)
                    val width = lerp(state.box.width(), desired.width(), sizeAlpha)
                    val height = lerp(state.box.height(), desired.height(), sizeAlpha)
                    state.box = rectFromCenter(cx, cy, width, height)
                }
                target.copy(normalizedBox = RectF(state.box))
            }
        }
        states.entries.removeAll { (id, state) -> id !in activeIds && now - state.lastSeenInputAt > 1_500L }
        return result
    }

    private fun rectFromCenter(cx: Float, cy: Float, width: Float, height: Float): RectF {
        val w = width.coerceIn(0.002f, 1f)
        val h = height.coerceIn(0.002f, 1f)
        val halfW = w / 2f
        val halfH = h / 2f
        val safeCx = cx.coerceIn(halfW, 1f - halfW)
        val safeCy = cy.coerceIn(halfH, 1f - halfH)
        return RectF(safeCx - halfW, safeCy - halfH, safeCx + halfW, safeCy + halfH)
    }

    private fun ratio(a: Float, b: Float): Float {
        val lo = minOf(a, b).coerceAtLeast(0.0001f)
        return maxOf(a, b) / lo
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
