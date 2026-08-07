package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight motion detector with global frame-motion compensation.
 *
 * The luma plane is downsampled, the best global translation between two
 * consecutive frames is estimated with a small SAD search, then connected
 * components are extracted from the residual motion mask. This makes the
 * detector useful for small moving targets without pulling OpenCV into the APK.
 */
internal class MotionFlowDetector {
    private val gridWidth = 96
    private val gridHeight = 72
    private var previous: ByteArray? = null

    data class Result(
        val observations: List<RawObservation>,
        val globalDx: Float,
        val globalDy: Float,
        val active: Boolean
    )

    fun reset() {
        previous = null
    }

    fun analyze(image: ImageProxy, rotation: Int): Result {
        val current = downsampleLuma(image)
        val prev = previous
        previous = current
        if (prev == null) return Result(emptyList(), 0f, 0f, false)

        val (shiftX, shiftY) = estimateGlobalShift(prev, current)
        val residual = IntArray(gridWidth * gridHeight)
        var residualSum = 0L
        var compared = 0

        for (y in 2 until gridHeight - 2) {
            for (x in 2 until gridWidth - 2) {
                val px = x - shiftX
                val py = y - shiftY
                if (px !in 0 until gridWidth || py !in 0 until gridHeight) continue
                val now = current[y * gridWidth + x].toInt() and 0xFF
                val old = prev[py * gridWidth + px].toInt() and 0xFF
                val d = abs(now - old)
                residual[y * gridWidth + x] = d
                residualSum += d
                compared++
            }
        }

        if (compared == 0) return Result(emptyList(), shiftX.toFloat(), shiftY.toFloat(), false)
        val meanResidual = residualSum.toFloat() / compared
        val threshold = max(24f, meanResidual * 2.35f + 8f).toInt().coerceAtMost(72)

        val mask = BooleanArray(residual.size)
        var activeCells = 0
        for (i in residual.indices) {
            if (residual[i] >= threshold) {
                mask[i] = true
                activeCells++
            }
        }

        // A large residual field almost always means rotation/blur/major camera motion.
        val activeRatio = activeCells.toFloat() / residual.size.toFloat()
        if (activeRatio > 0.16f) {
            return Result(emptyList(), shiftX.toFloat(), shiftY.toFloat(), false)
        }

        val visited = BooleanArray(mask.size)
        val components = mutableListOf<Component>()
        for (y in 1 until gridHeight - 1) {
            for (x in 1 until gridWidth - 1) {
                val start = y * gridWidth + x
                if (!mask[start] || visited[start]) continue
                val c = flood(mask, visited, residual, x, y)
                if (c.cells in 2..95) components += c
            }
        }

        val observations = components
            .sortedByDescending { it.energy }
            .take(6)
            .mapNotNull { component -> componentToObservation(component, rotation, threshold) }

        return Result(
            observations = observations,
            globalDx = shiftX.toFloat() / gridWidth,
            globalDy = shiftY.toFloat() / gridHeight,
            active = observations.isNotEmpty()
        )
    }

    private data class Component(
        var minX: Int,
        var minY: Int,
        var maxX: Int,
        var maxY: Int,
        var cells: Int,
        var energy: Int
    )

    private fun flood(
        mask: BooleanArray,
        visited: BooleanArray,
        residual: IntArray,
        sx: Int,
        sy: Int
    ): Component {
        val qx = IntArray(gridWidth * gridHeight)
        val qy = IntArray(gridWidth * gridHeight)
        var head = 0
        var tail = 0
        qx[tail] = sx
        qy[tail] = sy
        tail++
        visited[sy * gridWidth + sx] = true
        val c = Component(sx, sy, sx, sy, 0, 0)

        while (head < tail) {
            val x = qx[head]
            val y = qy[head]
            head++
            val idx = y * gridWidth + x
            c.minX = min(c.minX, x)
            c.minY = min(c.minY, y)
            c.maxX = max(c.maxX, x)
            c.maxY = max(c.maxY, y)
            c.cells++
            c.energy += residual[idx]

            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 1 until gridWidth - 1 || ny !in 1 until gridHeight - 1) continue
                val n = ny * gridWidth + nx
                if (mask[n] && !visited[n]) {
                    visited[n] = true
                    qx[tail] = nx
                    qy[tail] = ny
                    tail++
                }
            }
        }
        return c
    }

    private fun componentToObservation(c: Component, rotation: Int, threshold: Int): RawObservation? {
        val padX = 2f / gridWidth
        val padY = 2f / gridHeight
        val raw = RectF(
            (c.minX.toFloat() / gridWidth - padX).coerceIn(0f, 1f),
            (c.minY.toFloat() / gridHeight - padY).coerceIn(0f, 1f),
            ((c.maxX + 1).toFloat() / gridWidth + padX).coerceIn(0f, 1f),
            ((c.maxY + 1).toFloat() / gridHeight + padY).coerceIn(0f, 1f)
        )
        val oriented = rotateNormalizedRect(raw, rotation)
        val area = oriented.width() * oriented.height()
        if (area !in 0.00035f..0.075f) return null
        if (oriented.width() > 0.38f || oriented.height() > 0.38f) return null

        val avgEnergy = c.energy.toFloat() / c.cells.coerceAtLeast(1)
        val confidence = (0.42f + (avgEnergy - threshold).coerceAtLeast(0f) / 120f + c.cells.coerceAtMost(20) / 100f)
            .coerceIn(0.42f, 0.88f)

        return RawObservation(
            sourceTrackingId = null,
            label = "MOTION",
            confidence = confidence,
            normalizedBox = oriented,
            fromBrightnessTracker = false,
            fromMotionTracker = true
        )
    }

    private fun estimateGlobalShift(previous: ByteArray, current: ByteArray): Pair<Int, Int> {
        var bestDx = 0
        var bestDy = 0
        var bestScore = Long.MAX_VALUE
        for (dy in -3..3) {
            for (dx in -3..3) {
                var score = 0L
                var count = 0
                var y = 6
                while (y < gridHeight - 6) {
                    var x = 6
                    while (x < gridWidth - 6) {
                        val px = x - dx
                        val py = y - dy
                        if (px in 0 until gridWidth && py in 0 until gridHeight) {
                            val a = current[y * gridWidth + x].toInt() and 0xFF
                            val b = previous[py * gridWidth + px].toInt() and 0xFF
                            score += abs(a - b)
                            count++
                        }
                        x += 3
                    }
                    y += 3
                }
                if (count > 0) {
                    val normalized = score / count
                    if (normalized < bestScore) {
                        bestScore = normalized
                        bestDx = dx
                        bestDy = dy
                    }
                }
            }
        }
        return bestDx to bestDy
    }

    private fun downsampleLuma(image: ImageProxy): ByteArray {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width
        val srcH = image.height
        val output = ByteArray(gridWidth * gridHeight)
        for (gy in 0 until gridHeight) {
            val sy = ((gy + 0.5f) * srcH / gridHeight).toInt().coerceIn(0, srcH - 1)
            for (gx in 0 until gridWidth) {
                val sx = ((gx + 0.5f) * srcW / gridWidth).toInt().coerceIn(0, srcW - 1)
                val index = sy * rowStride + sx * pixelStride
                output[gy * gridWidth + gx] = if (index < buffer.limit()) buffer.get(index) else 0
            }
        }
        return output
    }

    private fun rotateNormalizedRect(rect: RectF, rotation: Int): RectF = when (rotation) {
        90 -> RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
        180 -> RectF(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
        270 -> RectF(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
        else -> RectF(rect)
    }
}
