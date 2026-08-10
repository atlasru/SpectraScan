package com.atlas.spectrascan

import android.os.SystemClock

/** Session statistics layer. It consumes DetectionFrame results and never influences tracking. */
class SessionStore071 {
    data class TargetStat(
        val trackingId: Int,
        var label: String,
        val firstSeenElapsed: Long,
        var lastSeenElapsed: Long,
        var peakConfidence: Float,
        var observations: Int = 1
    ) {
        fun visibleMs(): Long = (lastSeenElapsed - firstSeenElapsed).coerceAtLeast(0L)
    }

    data class Summary(
        val running: Boolean,
        val startedElapsed: Long?,
        val elapsedMs: Long,
        val uniqueTargets: Int,
        val activeTargets: Int,
        val classCounts: Map<String, Int>,
        val targets: List<TargetStat>
    )

    private var startedElapsed: Long? = null
    private var stoppedElapsed: Long? = null
    private val targets = linkedMapOf<Int, TargetStat>()
    private var activeIds: Set<Int> = emptySet()

    @Synchronized
    fun start(now: Long = SystemClock.elapsedRealtime()) {
        startedElapsed = now
        stoppedElapsed = null
        targets.clear()
        activeIds = emptySet()
    }

    @Synchronized
    fun stop(now: Long = SystemClock.elapsedRealtime()) {
        if (startedElapsed != null) stoppedElapsed = now
        activeIds = emptySet()
    }

    @Synchronized
    fun isRunning(): Boolean = startedElapsed != null && stoppedElapsed == null

    @Synchronized
    fun observe(frame: DetectionFrame, now: Long = SystemClock.elapsedRealtime()) {
        if (!isRunning()) return
        activeIds = frame.targets.filter { it.status != TrackStatus.LOST }.mapTo(linkedSetOf()) { it.trackingId }
        frame.targets.forEach { t ->
            if (t.status == TrackStatus.LOST) return@forEach
            val old = targets[t.trackingId]
            if (old == null) {
                targets[t.trackingId] = TargetStat(
                    trackingId = t.trackingId,
                    label = t.label,
                    firstSeenElapsed = now,
                    lastSeenElapsed = now,
                    peakConfidence = t.confidence
                )
            } else {
                old.label = t.label
                old.lastSeenElapsed = now
                old.peakConfidence = maxOf(old.peakConfidence, t.confidence)
                old.observations++
            }
        }
    }

    @Synchronized
    fun summary(now: Long = SystemClock.elapsedRealtime()): Summary {
        val start = startedElapsed
        val end = stoppedElapsed ?: now
        val duration = if (start == null) 0L else (end - start).coerceAtLeast(0L)
        val copy = targets.values.map { it.copy() }
        return Summary(
            running = start != null && stoppedElapsed == null,
            startedElapsed = start,
            elapsedMs = duration,
            uniqueTargets = copy.size,
            activeTargets = if (start != null && stoppedElapsed == null) activeIds.size else 0,
            classCounts = copy.groupingBy { it.label.uppercase() }.eachCount().toSortedMap(),
            targets = copy.sortedBy { it.firstSeenElapsed }
        )
    }

    @Synchronized
    fun reset() {
        startedElapsed = null
        stoppedElapsed = null
        targets.clear()
        activeIds = emptySet()
    }
}
