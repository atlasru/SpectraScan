package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Lightweight local appearance tracker used only to bridge gaps between YOLO passes. */
internal class SemanticFlowTracker {
    private val gridWidth = 128
    private val gridHeight = 96
    private val templateWidth = 12
    private val templateHeight = 12

    private data class Memory(
        val stableId: Int,
        var label: String,
        var confidence: Float,
        var box: RectF,
        var template: ByteArray,
        var lastAt: Long,
        var lastYoloAt: Long,
        var velocityX: Float,
        var velocityY: Float,
        var misses: Int = 0
    )

    data class Result(
        val observations: List<RawObservation>,
        val active: Boolean,
        val averageScore: Float,
        val needsYoloRecheck: Boolean
    )

    private val memories = linkedMapOf<Int, Memory>()

    fun reset() = memories.clear()

    /** Seed/update appearance memory only from fresh YOLO-confirmed semantic targets. */
    fun seed(image: ImageProxy, rotation: Int, targets: List<DetectionTarget>, now: Long) {
        val semantic = targets.asSequence()
            .filter { it.status != TrackStatus.LOST }
            .filter { !it.fromBrightnessTracker && !it.fromMotionTracker && !it.fromFlowTracker }
            .sortedByDescending { it.confidence }
            .take(MAX_TARGETS)
            .toList()
        if (semantic.isEmpty()) return

        val grid = downsampleOrientedLuma(image, rotation)
        val keep = mutableSetOf<Int>()
        semantic.forEach { target ->
            val template = extractPatch(grid, target.normalizedBox) ?: return@forEach
            keep += target.trackingId
            val old = memories[target.trackingId]
            if (old == null) {
                memories[target.trackingId] = Memory(
                    target.trackingId, target.label, target.confidence, RectF(target.normalizedBox), template,
                    now, now, target.velocityX, target.velocityY
                )
            } else {
                old.label = target.label
                old.confidence = target.confidence
                old.box = RectF(target.normalizedBox)
                old.velocityX = target.velocityX
                old.velocityY = target.velocityY
                old.lastAt = now
                old.lastYoloAt = now
                old.misses = 0
                blendTemplate(old.template, template, 0.24f)
            }
        }
        memories.entries.removeAll { (id, m) -> id !in keep && now - m.lastYoloAt > MEMORY_HOLD_MS }
    }

    fun track(image: ImageProxy, rotation: Int, now: Long): Result {
        if (memories.isEmpty()) return Result(emptyList(), false, 0f, false)
        val grid = downsampleOrientedLuma(image, rotation)
        val observations = mutableListOf<RawObservation>()
        var scoreSum = 0f
        var needsRecheck = false

        memories.values.forEach { memory ->
            // Flow is interpolation, never semantic truth. Force a YOLO validation quickly.
            if (now - memory.lastYoloAt > FLOW_TTL_MS) {
                needsRecheck = true
                return@forEach
            }
            val dt = ((now - memory.lastAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.25f)
            val predicted = shiftAndClamp(memory.box, memory.velocityX * dt, memory.velocityY * dt)
            val result = localSearch(grid, predicted, memory.template, memory.velocityX, memory.velocityY)
            if (result == null || result.score < MIN_ACCEPT_SCORE || !plausible(memory.box, predicted, result.box)) {
                memory.misses++
                memory.lastAt = now
                memory.velocityX *= 0.65f
                memory.velocityY *= 0.65f
                needsRecheck = true
                return@forEach
            }

            val previousX = memory.box.centerX()
            val previousY = memory.box.centerY()
            val measuredVx = (result.box.centerX() - previousX) / dt
            val measuredVy = (result.box.centerY() - previousY) / dt
            memory.velocityX = (memory.velocityX * 0.72f + measuredVx * 0.28f).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
            memory.velocityY = (memory.velocityY * 0.72f + measuredVy * 0.28f).coerceIn(-MAX_VELOCITY, MAX_VELOCITY)
            memory.box = result.box
            memory.lastAt = now
            memory.misses = 0

            // Never learn aggressively from our own prediction: that caused template drift.
            if (result.score > TEMPLATE_UPDATE_SCORE) {
                extractPatch(grid, result.box)?.let { blendTemplate(memory.template, it, 0.025f) }
            }

            val ageFactor = (1f - (now - memory.lastYoloAt).toFloat() / FLOW_TTL_MS).coerceIn(0.20f, 1f)
            val confidence = minOf(memory.confidence, (result.score * 0.88f * ageFactor).coerceIn(0.20f, 0.90f))
            observations += RawObservation(
                sourceTrackingId = memory.stableId,
                label = memory.label,
                confidence = confidence,
                normalizedBox = RectF(result.box),
                fromFlowTracker = true
            )
            scoreSum += result.score
            if (result.score < RECHECK_SCORE) needsRecheck = true
        }

        memories.entries.removeAll { (_, m) -> m.misses >= MAX_MISSES || now - m.lastYoloAt > MEMORY_HOLD_MS }
        val average = if (observations.isEmpty()) 0f else scoreSum / observations.size
        return Result(observations, observations.isNotEmpty(), average, needsRecheck)
    }

    private data class SearchResult(val box: RectF, val score: Float)

    private fun localSearch(grid: ByteArray, predicted: RectF, template: ByteArray, vx: Float, vy: Float): SearchResult? {
        val speed = hypot(vx, vy)
        val radius = (3 + (speed * 18f).toInt()).coerceIn(3, 7)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestBox: RectF? = null
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val shifted = shiftAndClamp(predicted, dx.toFloat() / gridWidth, dy.toFloat() / gridHeight)
            val patch = extractPatch(grid, shifted) ?: continue
            val score = appearanceScore(template, patch)
            if (score > bestScore) { bestScore = score; bestBox = shifted }
        }
        return bestBox?.let { SearchResult(it, bestScore.coerceIn(0f, 1f)) }
    }

    private fun plausible(previous: RectF, predicted: RectF, candidate: RectF): Boolean {
        val maxJump = max(0.035f, hypot(previous.width(), previous.height()) * 0.55f)
        if (hypot(candidate.centerX() - predicted.centerX(), candidate.centerY() - predicted.centerY()) > maxJump) return false
        val wr = candidate.width() / previous.width().coerceAtLeast(0.001f)
        val hr = candidate.height() / previous.height().coerceAtLeast(0.001f)
        if (wr !in 0.78f..1.28f || hr !in 0.78f..1.28f) return false
        val oldAspect = previous.width() / previous.height().coerceAtLeast(0.001f)
        val newAspect = candidate.width() / candidate.height().coerceAtLeast(0.001f)
        return newAspect / oldAspect in 0.78f..1.28f
    }

    private fun appearanceScore(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var diff = 0L; var meanA = 0L; var meanB = 0L
        for (i in a.indices) {
            val av = a[i].toInt() and 0xFF; val bv = b[i].toInt() and 0xFF
            diff += abs(av - bv); meanA += av; meanB += bv
        }
        val avgDiff = diff.toFloat() / a.size
        val brightnessPenalty = abs(meanA.toFloat() / a.size - meanB.toFloat() / b.size) * 0.30f
        return (1f - (avgDiff + brightnessPenalty) / 86f).coerceIn(0f, 1f)
    }

    private fun extractPatch(grid: ByteArray, box: RectF): ByteArray? {
        if (box.width() <= 0f || box.height() <= 0f) return null
        val w = max(box.width(), 0.025f); val h = max(box.height(), 0.025f)
        val cx = box.centerX(); val cy = box.centerY(); val out = ByteArray(templateWidth * templateHeight)
        for (ty in 0 until templateHeight) {
            val gy = ((cy - h / 2f + (ty + .5f) / templateHeight * h) * gridHeight).toInt().coerceIn(0, gridHeight - 1)
            for (tx in 0 until templateWidth) {
                val gx = ((cx - w / 2f + (tx + .5f) / templateWidth * w) * gridWidth).toInt().coerceIn(0, gridWidth - 1)
                out[ty * templateWidth + tx] = grid[gy * gridWidth + gx]
            }
        }
        return out
    }

    private fun blendTemplate(base: ByteArray, fresh: ByteArray, amount: Float) {
        if (base.size != fresh.size) return
        for (i in base.indices) {
            val a = base[i].toInt() and 0xFF; val b = fresh[i].toInt() and 0xFF
            base[i] = (a + (b - a) * amount).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun shiftAndClamp(source: RectF, dx: Float, dy: Float): RectF {
        val width = source.width().coerceIn(0.008f, 0.98f); val height = source.height().coerceIn(0.008f, 0.98f)
        val left = (source.left + dx).coerceIn(0f, 1f - width); val top = (source.top + dy).coerceIn(0f, 1f - height)
        return RectF(left, top, left + width, top + height)
    }

    private fun downsampleOrientedLuma(image: ImageProxy, rotation: Int): ByteArray {
        val plane = image.planes.first(); val buffer = plane.buffer.duplicate(); val rowStride = plane.rowStride; val pixelStride = plane.pixelStride
        val srcW = image.width; val srcH = image.height; val output = ByteArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) for (gx in 0 until gridWidth) {
            val u = (gx + .5f) / gridWidth; val v = (gy + .5f) / gridHeight; val raw = orientedToRaw(u, v, rotation)
            val sx = (raw.first * srcW).toInt().coerceIn(0, srcW - 1); val sy = (raw.second * srcH).toInt().coerceIn(0, srcH - 1)
            val index = sy * rowStride + sx * pixelStride; output[gy * gridWidth + gx] = if (index < buffer.limit()) buffer.get(index) else 0
        }
        return output
    }

    private fun orientedToRaw(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> v to (1f - u); 180 -> (1f - u) to (1f - v); 270 -> (1f - v) to u; else -> u to v
    }

    private companion object {
        const val MAX_TARGETS = 7
        const val MAX_MISSES = 2
        const val FLOW_TTL_MS = 900L
        const val MEMORY_HOLD_MS = 1_500L
        const val MIN_ACCEPT_SCORE = 0.66f
        const val RECHECK_SCORE = 0.74f
        const val TEMPLATE_UPDATE_SCORE = 0.84f
        const val MAX_VELOCITY = 1.2f
    }
}
