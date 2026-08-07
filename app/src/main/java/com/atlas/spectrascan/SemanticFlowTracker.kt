package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lightweight local appearance tracker used between expensive YOLO passes.
 *
 * Each confirmed semantic target keeps a small luma template. On following
 * frames we search only around the predicted position, which is dramatically
 * cheaper than running the detector over the whole image. The tracker is
 * intentionally dependency-free so it remains Android/CameraX friendly.
 */
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
        var velocityX: Float,
        var velocityY: Float,
        var misses: Int = 0
    )

    data class Result(
        val observations: List<RawObservation>,
        val active: Boolean,
        val averageScore: Float
    )

    private val memories = linkedMapOf<Int, Memory>()

    fun reset() {
        memories.clear()
    }

    fun seed(image: ImageProxy, rotation: Int, targets: List<DetectionTarget>, now: Long) {
        val semantic = targets
            .asSequence()
            .filter { it.status != TrackStatus.LOST }
            .filter { !it.fromBrightnessTracker && !it.fromMotionTracker }
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
                    stableId = target.trackingId,
                    label = target.label,
                    confidence = target.confidence,
                    box = RectF(target.normalizedBox),
                    template = template,
                    lastAt = now,
                    velocityX = target.velocityX,
                    velocityY = target.velocityY
                )
            } else {
                old.label = target.label
                old.confidence = target.confidence
                old.box = RectF(target.normalizedBox)
                old.velocityX = target.velocityX
                old.velocityY = target.velocityY
                old.lastAt = now
                old.misses = 0
                blendTemplate(old.template, template, 0.32f)
            }
        }
        memories.entries.removeAll { (id, memory) -> id !in keep && now - memory.lastAt > MEMORY_HOLD_MS }
    }

    fun track(image: ImageProxy, rotation: Int, now: Long): Result {
        if (memories.isEmpty()) return Result(emptyList(), false, 0f)
        val grid = downsampleOrientedLuma(image, rotation)
        val observations = mutableListOf<RawObservation>()
        var scoreSum = 0f

        memories.values.forEach { memory ->
            val dt = ((now - memory.lastAt).coerceAtLeast(1L) / 1000f).coerceAtMost(0.35f)
            val predicted = shiftAndClamp(
                memory.box,
                memory.velocityX * dt,
                memory.velocityY * dt
            )
            val result = localSearch(grid, predicted, memory.template, memory.velocityX, memory.velocityY)
            if (result == null || result.score < MIN_ACCEPT_SCORE) {
                memory.misses++
                memory.lastAt = now
                memory.velocityX *= 0.82f
                memory.velocityY *= 0.82f
                return@forEach
            }

            val previousX = memory.box.centerX()
            val previousY = memory.box.centerY()
            val measuredVx = (result.box.centerX() - previousX) / dt
            val measuredVy = (result.box.centerY() - previousY) / dt
            memory.velocityX = memory.velocityX * 0.62f + measuredVx * 0.38f
            memory.velocityY = memory.velocityY * 0.62f + measuredVy * 0.38f
            memory.box = result.box
            memory.lastAt = now
            memory.misses = 0

            if (result.score > TEMPLATE_UPDATE_SCORE) {
                extractPatch(grid, result.box)?.let { fresh -> blendTemplate(memory.template, fresh, 0.08f) }
            }

            val confidence = minOf(
                memory.confidence,
                (0.48f + result.score * 0.46f).coerceIn(0.48f, 0.94f)
            )
            observations += RawObservation(
                sourceTrackingId = memory.stableId,
                label = memory.label,
                confidence = confidence,
                normalizedBox = RectF(result.box),
                fromFlowTracker = true
            )
            scoreSum += result.score
        }

        memories.entries.removeAll { (_, memory) -> memory.misses >= MAX_MISSES }
        val average = if (observations.isEmpty()) 0f else scoreSum / observations.size
        return Result(observations, observations.isNotEmpty(), average)
    }

    private data class SearchResult(val box: RectF, val score: Float)

    private fun localSearch(
        grid: ByteArray,
        predicted: RectF,
        template: ByteArray,
        velocityX: Float,
        velocityY: Float
    ): SearchResult? {
        val speed = hypot(velocityX, velocityY)
        val radius = (4 + (speed * 28f).toInt()).coerceIn(4, 10)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestBox: RectF? = null

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val shifted = shiftAndClamp(
                    predicted,
                    dx.toFloat() / gridWidth,
                    dy.toFloat() / gridHeight
                )
                val patch = extractPatch(grid, shifted) ?: continue
                val score = appearanceScore(template, patch)
                if (score > bestScore) {
                    bestScore = score
                    bestBox = shifted
                }
            }
        }
        return bestBox?.let { SearchResult(it, bestScore.coerceIn(0f, 1f)) }
    }

    private fun appearanceScore(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var diff = 0L
        var meanA = 0L
        var meanB = 0L
        for (i in a.indices) {
            val av = a[i].toInt() and 0xFF
            val bv = b[i].toInt() and 0xFF
            diff += abs(av - bv)
            meanA += av
            meanB += bv
        }
        val avgDiff = diff.toFloat() / a.size
        val brightnessPenalty = abs(meanA.toFloat() / a.size - meanB.toFloat() / b.size) * 0.22f
        return (1f - (avgDiff + brightnessPenalty) / 92f).coerceIn(0f, 1f)
    }

    private fun extractPatch(grid: ByteArray, box: RectF): ByteArray? {
        if (box.width() <= 0f || box.height() <= 0f) return null
        val expandedW = max(box.width(), 0.025f)
        val expandedH = max(box.height(), 0.025f)
        val cx = box.centerX()
        val cy = box.centerY()
        val output = ByteArray(templateWidth * templateHeight)
        for (ty in 0 until templateHeight) {
            val ny = cy - expandedH / 2f + (ty + 0.5f) / templateHeight * expandedH
            val gy = (ny * gridHeight).toInt().coerceIn(0, gridHeight - 1)
            for (tx in 0 until templateWidth) {
                val nx = cx - expandedW / 2f + (tx + 0.5f) / templateWidth * expandedW
                val gx = (nx * gridWidth).toInt().coerceIn(0, gridWidth - 1)
                output[ty * templateWidth + tx] = grid[gy * gridWidth + gx]
            }
        }
        return output
    }

    private fun blendTemplate(base: ByteArray, fresh: ByteArray, amount: Float) {
        if (base.size != fresh.size) return
        for (i in base.indices) {
            val a = base[i].toInt() and 0xFF
            val b = fresh[i].toInt() and 0xFF
            base[i] = (a + (b - a) * amount).toInt().coerceIn(0, 255).toByte()
        }
    }

    private fun shiftAndClamp(source: RectF, dx: Float, dy: Float): RectF {
        val width = source.width().coerceIn(0.008f, 0.98f)
        val height = source.height().coerceIn(0.008f, 0.98f)
        val left = (source.left + dx).coerceIn(0f, 1f - width)
        val top = (source.top + dy).coerceIn(0f, 1f - height)
        return RectF(left, top, left + width, top + height)
    }

    /** Samples the camera Y plane directly into an orientation-normalized grid. */
    private fun downsampleOrientedLuma(image: ImageProxy, rotation: Int): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height
        val output = ByteArray(gridWidth * gridHeight)

        for (gy in 0 until gridHeight) {
            val v = (gy + 0.5f) / gridHeight
            for (gx in 0 until gridWidth) {
                val u = (gx + 0.5f) / gridWidth
                val raw = orientedToRaw(u, v, rotation)
                val sx = (raw.first * srcW).toInt().coerceIn(0, srcW - 1)
                val sy = (raw.second * srcH).toInt().coerceIn(0, srcH - 1)
                val index = sy * rowStride + sx * pixelStride
                output[gy * gridWidth + gx] = if (index < buffer.limit()) buffer.get(index) else 0
            }
        }
        return output
    }

    private fun orientedToRaw(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> v to (1f - u)
        180 -> (1f - u) to (1f - v)
        270 -> (1f - v) to u
        else -> u to v
    }

    private companion object {
        const val MAX_TARGETS = 7
        const val MAX_MISSES = 7
        const val MEMORY_HOLD_MS = 2_500L
        const val MIN_ACCEPT_SCORE = 0.47f
        const val TEMPLATE_UPDATE_SCORE = 0.72f
    }
}
