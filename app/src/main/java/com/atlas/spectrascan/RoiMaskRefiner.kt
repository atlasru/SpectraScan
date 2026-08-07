package com.atlas.spectrascan

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lightweight foreground refinement for already-confirmed YOLO detections.
 * It never creates targets; it only tightens an existing box and returns a coarse local mask.
 */
internal object RoiMaskRefiner {
    data class Result(val box: RectF, val mask: List<MaskCell>, val quality: Float)

    private const val GRID = 18

    fun refine(bitmap: Bitmap, detection: YoloDetection): Result {
        val original = detection.normalizedBox
        if (original.width() < .018f || original.height() < .018f) return Result(RectF(original), emptyList(), 0f)

        val left = (original.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = (original.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = (original.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = (original.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
        val roiW = right - left
        val roiH = bottom - top
        if (roiW < 12 || roiH < 12) return Result(RectF(original), emptyList(), 0f)

        val colors = IntArray(GRID * GRID)
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val px = (left + (gx + .5f) / GRID * roiW).toInt().coerceIn(left, right - 1)
            val py = (top + (gy + .5f) / GRID * roiH).toInt().coerceIn(top, bottom - 1)
            colors[gy * GRID + gx] = bitmap.getPixel(px, py)
        }

        var bgR = 0f; var bgG = 0f; var bgB = 0f; var bgN = 0
        fun addBg(c: Int) { bgR += Color.red(c); bgG += Color.green(c); bgB += Color.blue(c); bgN++ }
        for (i in 0 until GRID) { addBg(colors[i]); addBg(colors[(GRID - 1) * GRID + i]) }
        for (i in 1 until GRID - 1) { addBg(colors[i * GRID]); addBg(colors[i * GRID + GRID - 1]) }
        bgR /= bgN; bgG /= bgN; bgB /= bgN

        val rawScore = FloatArray(GRID * GRID)
        var mean = 0f
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val idx = gy * GRID + gx
            val c = colors[idx]
            val dr = Color.red(c) - bgR; val dg = Color.green(c) - bgG; val db = Color.blue(c) - bgB
            val colorDistance = kotlin.math.sqrt((dr * dr + dg * dg + db * db) / 3f)
            val centerPrior = 1f - (hypot((gx + .5f) / GRID - .5f, (gy + .5f) / GRID - .5f) / .71f).coerceIn(0f, 1f)
            var gradient = 0f
            if (gx > 0) gradient += colorDelta(c, colors[idx - 1])
            if (gx < GRID - 1) gradient += colorDelta(c, colors[idx + 1])
            if (gy > 0) gradient += colorDelta(c, colors[idx - GRID])
            if (gy < GRID - 1) gradient += colorDelta(c, colors[idx + GRID])
            gradient *= .25f
            val score = colorDistance * .72f + gradient * .22f + centerPrior * 18f
            rawScore[idx] = score
            mean += score
        }
        mean /= rawScore.size
        val threshold = (mean * 1.04f).coerceIn(22f, 58f)

        val active = BooleanArray(GRID * GRID)
        for (i in rawScore.indices) active[i] = rawScore[i] >= threshold
        // Keep only cells connected to the central region. This prevents background edges inside a YOLO box
        // from becoming the foreground silhouette.
        val connected = BooleanArray(GRID * GRID)
        val queue = IntArray(GRID * GRID); var qh = 0; var qt = 0
        val c0 = GRID / 2
        for (gy in c0 - 2..c0 + 2) for (gx in c0 - 2..c0 + 2) {
            val idx = gy * GRID + gx
            if (active[idx] && !connected[idx]) { connected[idx] = true; queue[qt++] = idx }
        }
        while (qh < qt) {
            val idx = queue[qh++]; val x = idx % GRID; val y = idx / GRID
            val neighbors = intArrayOf(idx - 1, idx + 1, idx - GRID, idx + GRID)
            for (n in neighbors) {
                if (n !in connected.indices) continue
                val nx = n % GRID; val ny = n / GRID
                if (abs(nx - x) + abs(ny - y) != 1) continue
                if (active[n] && !connected[n]) { connected[n] = true; queue[qt++] = n }
            }
        }

        var minX = GRID; var minY = GRID; var maxX = -1; var maxY = -1; var count = 0
        for (gy in 0 until GRID) for (gx in 0 until GRID) if (connected[gy * GRID + gx]) {
            minX = min(minX, gx); minY = min(minY, gy); maxX = max(maxX, gx); maxY = max(maxY, gy); count++
        }
        val occupancy = count.toFloat() / (GRID * GRID)
        if (count < 9 || occupancy !in .035f.. .78f || maxX <= minX || maxY <= minY) return Result(RectF(original), emptyList(), 0f)

        val padCells = 1
        minX = (minX - padCells).coerceAtLeast(0); minY = (minY - padCells).coerceAtLeast(0)
        maxX = (maxX + padCells).coerceAtMost(GRID - 1); maxY = (maxY + padCells).coerceAtMost(GRID - 1)
        val localL = minX.toFloat() / GRID; val localT = minY.toFloat() / GRID
        val localR = (maxX + 1f) / GRID; val localB = (maxY + 1f) / GRID
        val refined = RectF(
            original.left + original.width() * localL,
            original.top + original.height() * localT,
            original.left + original.width() * localR,
            original.top + original.height() * localB
        )
        val areaRatio = refined.width() * refined.height() / (original.width() * original.height()).coerceAtLeast(.000001f)
        val centerShift = hypot(refined.centerX() - original.centerX(), refined.centerY() - original.centerY())
        val maxShift = hypot(original.width(), original.height()) * .24f
        if (areaRatio !in .30f..1.02f || centerShift > maxShift) return Result(RectF(original), emptyList(), 0f)

        val mask = ArrayList<MaskCell>(count)
        val rw = (maxX - minX + 1).coerceAtLeast(1)
        val rh = (maxY - minY + 1).coerceAtLeast(1)
        for (gy in minY..maxY) for (gx in minX..maxX) {
            val idx = gy * GRID + gx
            if (!connected[idx]) continue
            val x = (gx - minX).toFloat() / rw
            val y = (gy - minY).toFloat() / rh
            mask += MaskCell(x, y, 1f / rw, 1f / rh, (rawScore[idx] / 110f).coerceIn(.25f, 1f))
        }
        val quality = ((1f - areaRatio).coerceAtLeast(0f) * .45f + occupancy * .55f).coerceIn(.15f, 1f)
        return Result(refined, mask, quality)
    }

    private fun colorDelta(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db) / 3f)
    }
}
