package com.atlas.spectrascan

import android.graphics.RectF
import android.os.SystemClock

internal enum class LockTrackingMode071(val title: String) {
    STANDARD("STD"),
    PRECISION("PRC");

    fun next(): LockTrackingMode071 = if (this == STANDARD) PRECISION else STANDARD
}

/**
 * Small thread-safe bridge between the Compose lock controls and the camera analyzer.
 * It carries only user intent/diagnostics; the normal HybridTracker never depends on it.
 */
internal object PrecisionLockControl071 {
    data class Selection(
        val trackingId: Int,
        val label: String,
        val confidence: Float,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val selectedAtMs: Long
    ) {
        fun box(): RectF = RectF(left, top, right, bottom)
    }

    data class Telemetry(
        val mode: LockTrackingMode071,
        val trackingId: Int?,
        val state: String,
        val flowScore: Float,
        val updatedAtMs: Long
    )

    @Volatile private var cachedMode: LockTrackingMode071? = null
    @Volatile private var selected: Selection? = null
    @Volatile private var telemetryState = Telemetry(
        mode = LockTrackingMode071.STANDARD,
        trackingId = null,
        state = "IDLE",
        flowScore = 0f,
        updatedAtMs = 0L
    )

    fun mode(): LockTrackingMode071 {
        cachedMode?.let { return it }
        synchronized(this) {
            cachedMode?.let { return it }
            val prefs = SpectraScanApplication.appContext.getSharedPreferences("spectrascan_ui", 0)
            val loaded = runCatching {
                LockTrackingMode071.valueOf(
                    prefs.getString("lock_engine071", LockTrackingMode071.STANDARD.name)
                        ?: LockTrackingMode071.STANDARD.name
                )
            }.getOrDefault(LockTrackingMode071.STANDARD)
            cachedMode = loaded
            telemetryState = telemetryState.copy(mode = loaded)
            return loaded
        }
    }

    fun toggleMode(): LockTrackingMode071 = setMode(mode().next())

    fun setMode(value: LockTrackingMode071): LockTrackingMode071 {
        cachedMode = value
        SpectraScanApplication.appContext.getSharedPreferences("spectrascan_ui", 0)
            .edit().putString("lock_engine071", value.name).apply()
        telemetryState = telemetryState.copy(
            mode = value,
            state = if (value == LockTrackingMode071.PRECISION && selected != null) "ARMED" else "IDLE",
            flowScore = 0f,
            updatedAtMs = SystemClock.elapsedRealtime()
        )
        return value
    }

    fun onUserEvent(target: DetectionTarget?, event: String) {
        when (event) {
            "LOCK", "MOTION_LOCK" -> if (target != null) select(target)
            "UNLOCK" -> clearSelection()
        }
    }

    fun select(target: DetectionTarget) {
        val b = target.normalizedBox
        selected = Selection(
            trackingId = target.trackingId,
            label = target.label,
            confidence = target.confidence,
            left = b.left,
            top = b.top,
            right = b.right,
            bottom = b.bottom,
            selectedAtMs = SystemClock.elapsedRealtime()
        )
        telemetryState = telemetryState.copy(
            mode = mode(),
            trackingId = target.trackingId,
            state = if (mode() == LockTrackingMode071.PRECISION) "ARMED" else "IDLE",
            flowScore = 0f,
            updatedAtMs = SystemClock.elapsedRealtime()
        )
    }

    fun rebind(target: DetectionTarget) {
        val b = target.normalizedBox
        selected = Selection(
            trackingId = target.trackingId,
            label = target.label,
            confidence = target.confidence,
            left = b.left,
            top = b.top,
            right = b.right,
            bottom = b.bottom,
            selectedAtMs = selected?.selectedAtMs ?: SystemClock.elapsedRealtime()
        )
    }

    fun clearSelection() {
        selected = null
        telemetryState = telemetryState.copy(
            mode = mode(),
            trackingId = null,
            state = "IDLE",
            flowScore = 0f,
            updatedAtMs = SystemClock.elapsedRealtime()
        )
    }

    fun selection(): Selection? = selected

    fun publish(trackingId: Int?, state: String, flowScore: Float) {
        telemetryState = Telemetry(
            mode = mode(),
            trackingId = trackingId,
            state = state,
            flowScore = flowScore.coerceIn(0f, 1f),
            updatedAtMs = SystemClock.elapsedRealtime()
        )
    }

    fun telemetry(): Telemetry {
        mode() // lazy-load persisted value first
        return telemetryState
    }
}
