package com.atlas.spectrascan

import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.hypot

/**
 * Dedicated state machine for a user-selected target.
 * It never invents detections: it remembers the last reliable target and can re-bind
 * the lock to a new tracker ID when YOLO reacquires the same object nearby.
 */
internal class LockEngine {
    enum class State { OFF, LOCKED, PREDICTING, REACQUIRING, LOST }

    data class Snapshot(
        val lockedId: Int?,
        val state: State,
        val target: DetectionTarget?,
        val lostForMs: Long,
        val confidence: Float
    )

    private var lockedId: Int? = null
    private var label: String? = null
    private var lastBox: RectF? = null
    private var lastVx = 0f
    private var lastVy = 0f
    private var lastSeenAt = 0L
    private var lostSince = 0L

    fun clear() {
        lockedId = null; label = null; lastBox = null
        lastVx = 0f; lastVy = 0f; lastSeenAt = 0L; lostSince = 0L
    }

    fun lock(target: DetectionTarget, now: Long = SystemClock.elapsedRealtime()) {
        lockedId = target.trackingId
        label = target.label
        lastBox = RectF(target.normalizedBox)
        lastVx = target.velocityX
        lastVy = target.velocityY
        lastSeenAt = now
        lostSince = 0L
    }

    fun update(targets: List<DetectionTarget>, now: Long = SystemClock.elapsedRealtime()): Snapshot {
        val id = lockedId ?: return Snapshot(null, State.OFF, null, 0L, 0f)
        val direct = targets.firstOrNull { it.trackingId == id && it.status != TrackStatus.LOST }
        if (direct != null) {
            remember(direct, now)
            val state = if (direct.status == TrackStatus.PREDICTED) State.PREDICTING else State.LOCKED
            return Snapshot(id, state, direct, 0L, direct.confidence)
        }

        if (lostSince == 0L) lostSince = now
        val lostFor = now - lostSince
        val predicted = predictedBox(now)
        val oldLabel = label

        // A tracker ID may change after YOLO reacquisition. Re-bind only to the same class,
        // close to the motion-predicted position, with sane scale/aspect similarity.
        val candidate = targets.asSequence()
            .filter { it.status != TrackStatus.LOST }
            .filter { oldLabel == null || it.label == oldLabel }
            .map { it to reacquireScore(predicted, it) }
            .filter { it.second >= MIN_REACQUIRE_SCORE }
            .maxByOrNull { it.second }
            ?.first

        if (candidate != null) {
            lockedId = candidate.trackingId
            remember(candidate, now)
            return Snapshot(candidate.trackingId, State.LOCKED, candidate, 0L, candidate.confidence)
        }

        val state = if (lostFor <= REACQUIRE_WINDOW_MS) State.REACQUIRING else State.LOST
        return Snapshot(lockedId, state, null, lostFor, 0f)
    }

    private fun remember(t: DetectionTarget, now: Long) {
        label = t.label
        lastBox = RectF(t.normalizedBox)
        lastVx = t.velocityX
        lastVy = t.velocityY
        lastSeenAt = now
        lostSince = 0L
    }

    private fun predictedBox(now: Long): RectF? {
        val b = lastBox ?: return null
        val dt = ((now - lastSeenAt).coerceAtLeast(0L) / 1000f).coerceAtMost(1.25f)
        val w = b.width(); val h = b.height()
        val cx = (b.centerX() + lastVx * dt).coerceIn(w / 2f, 1f - w / 2f)
        val cy = (b.centerY() + lastVy * dt).coerceIn(h / 2f, 1f - h / 2f)
        return RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    private fun reacquireScore(predicted: RectF?, candidate: DetectionTarget): Float {
        val p = predicted ?: return 0f
        val distance = hypot(p.centerX() - candidate.normalizedBox.centerX(), p.centerY() - candidate.normalizedBox.centerY())
        if (distance > MAX_REACQUIRE_DISTANCE) return 0f
        val wr = candidate.normalizedBox.width() / p.width().coerceAtLeast(.001f)
        val hr = candidate.normalizedBox.height() / p.height().coerceAtLeast(.001f)
        if (wr !in .45f..2.2f || hr !in .45f..2.2f) return 0f
        val overlap = iou(p, candidate.normalizedBox)
        val proximity = (1f - distance / MAX_REACQUIRE_DISTANCE).coerceIn(0f, 1f)
        val scale = (1f - (kotlin.math.abs(1f - wr) + kotlin.math.abs(1f - hr)) * .25f).coerceIn(0f, 1f)
        return overlap * .45f + proximity * .35f + scale * .10f + candidate.confidence * .10f
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l=maxOf(a.left,b.left); val t=maxOf(a.top,b.top); val r=minOf(a.right,b.right); val bot=minOf(a.bottom,b.bottom)
        if(r<=l||bot<=t)return 0f
        val x=(r-l)*(bot-t); val u=a.width()*a.height()+b.width()*b.height()-x
        return if(u<=0f)0f else x/u
    }

    private companion object {
        const val REACQUIRE_WINDOW_MS = 2_800L
        const val MAX_REACQUIRE_DISTANCE = .22f
        const val MIN_REACQUIRE_SCORE = .42f
    }
}
