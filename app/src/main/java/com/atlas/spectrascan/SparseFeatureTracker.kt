package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.video.Video
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Sparse pyramidal Lucas-Kanade bridge inspired by MINOS' advanced lock.
 * YOLO remains semantic truth; this class only moves already-confirmed targets
 * between detector passes and can never create a new semantic target.
 */
internal class SparseFeatureTracker {
    data class Result(
        val observations: List<RawObservation>,
        val active: Boolean,
        val averageScore: Float,
        val needsYoloRecheck: Boolean
    )

    private data class State(
        val id: Int,
        var label: String,
        var confidence: Float,
        var box: RectF,
        var points: MutableList<Point>,
        var lastAt: Long,
        var lastGoodAt: Long,
        var lastSeedAt: Long
    )

    private val states = linkedMapOf<Int, State>()
    private var prevGray: Mat? = null

    fun reset() {
        states.clear()
        prevGray?.release()
        prevGray = null
    }

    fun seed(image: ImageProxy, rotation: Int, targets: List<DetectionTarget>, now: Long) {
        if (!SpectraScanApplication.openCvReady) return
        val semantic = targets.asSequence()
            .filter { it.status == TrackStatus.TRACKING || it.status == TrackStatus.ACQUIRING }
            .filter { !it.fromFlowTracker && !it.fromMotionTracker && !it.fromBrightnessTracker }
            .sortedByDescending { it.confidence }
            .take(MAX_TARGETS)
            .toList()
        if (semantic.isEmpty()) return

        val gray = orientedGray(image, rotation)
        val keep = mutableSetOf<Int>()
        semantic.forEach { target ->
            val pts = detectPoints(gray, target.normalizedBox)
            if (pts.size < MIN_POINTS) return@forEach
            keep += target.trackingId
            states[target.trackingId] = State(
                id = target.trackingId,
                label = target.label,
                confidence = target.confidence,
                box = RectF(target.normalizedBox),
                points = pts.toMutableList(),
                lastAt = now,
                lastGoodAt = now,
                lastSeedAt = now
            )
        }
        states.entries.removeAll { (id, state) -> id !in keep && now - state.lastSeedAt > HARD_TTL_MS }
        prevGray?.release()
        prevGray = gray
    }

    fun track(image: ImageProxy, rotation: Int, now: Long): Result {
        if (!SpectraScanApplication.openCvReady || states.isEmpty()) return Result(emptyList(), false, 0f, false)
        val oldGray = prevGray ?: return Result(emptyList(), false, 0f, true)
        val gray = orientedGray(image, rotation)
        if (oldGray.rows() != gray.rows() || oldGray.cols() != gray.cols()) {
            oldGray.release(); prevGray = gray; states.clear()
            return Result(emptyList(), false, 0f, true)
        }

        val oldPoints = mutableListOf<Point>()
        val owners = mutableListOf<Int>()
        states.values.forEach { state ->
            state.points.forEach { p -> oldPoints += p; owners += state.id }
        }
        if (oldPoints.size < MIN_POINTS) {
            prevGray?.release(); prevGray = gray
            return Result(emptyList(), false, 0f, true)
        }

        val prevPts = MatOfPoint2f(*oldPoints.toTypedArray())
        val nextPts = MatOfPoint2f()
        val status = MatOfByte()
        val errors = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(
                oldGray, gray, prevPts, nextPts, status, errors,
                Size(21.0, 21.0), 2,
                TermCriteria(TermCriteria.COUNT or TermCriteria.EPS, 12, 0.03),
                0, 1e-4
            )
        } catch (_: Throwable) {
            prevPts.release(); nextPts.release(); status.release(); errors.release()
            prevGray?.release(); prevGray = gray
            return Result(emptyList(), false, 0f, true)
        }

        val newArray = nextPts.toArray()
        val statusArray = status.toArray()
        val errArray = errors.toArray()
        val byOwner = linkedMapOf<Int, MutableList<Pair<Point, Point>>>()
        for (i in oldPoints.indices) {
            if (i >= newArray.size || i >= statusArray.size || statusArray[i].toInt() == 0) continue
            if (i < errArray.size && errArray[i] > MAX_LK_ERROR) continue
            byOwner.getOrPut(owners[i]) { mutableListOf() } += oldPoints[i] to newArray[i]
        }

        val observations = mutableListOf<RawObservation>()
        var scoreSum = 0f
        var needsRecheck = false
        val frameW = gray.cols().toFloat().coerceAtLeast(1f)
        val frameH = gray.rows().toFloat().coerceAtLeast(1f)

        states.values.forEach { state ->
            val pairs = byOwner[state.id].orEmpty()
            if (pairs.size < MIN_POINTS) {
                needsRecheck = true
                return@forEach
            }

            val dxs = pairs.map { it.second.x - it.first.x }
            val dys = pairs.map { it.second.y - it.first.y }
            val mdx = median(dxs)
            val mdy = median(dys)
            val robust = pairs.filter { (a, b) ->
                hypot((b.x - a.x) - mdx, (b.y - a.y) - mdy) <= OUTLIER_RADIUS_PX
            }
            if (robust.size < MIN_POINTS) {
                needsRecheck = true
                return@forEach
            }

            val robustDx = median(robust.map { it.second.x - it.first.x })
            val robustDy = median(robust.map { it.second.y - it.first.y })
            val dxNorm = (robustDx / frameW).toFloat()
            val dyNorm = (robustDy / frameH).toFloat()
            if (hypot(dxNorm, dyNorm) > MAX_FRAME_SHIFT) {
                needsRecheck = true
                return@forEach
            }

            val dt = ((now - state.lastAt).coerceAtLeast(1L) / 1000f).coerceAtMost(.20f)
            val scale = estimateScale(robust).coerceIn(.96f, 1.04f)
            val oldBox = state.box
            val w = (oldBox.width() * scale).coerceIn(.006f, .98f)
            val h = (oldBox.height() * scale).coerceIn(.006f, .98f)
            val cx = (oldBox.centerX() + dxNorm).coerceIn(w / 2f, 1f - w / 2f)
            val cy = (oldBox.centerY() + dyNorm).coerceIn(h / 2f, 1f - h / 2f)
            state.box = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
            state.lastAt = now
            state.lastGoodAt = now
            state.points = robust.map { it.second }.toMutableList()

            if (state.points.size < RESEED_POINTS) {
                val reseeded = detectPoints(gray, state.box)
                if (reseeded.size >= MIN_POINTS) state.points = reseeded.toMutableList() else needsRecheck = true
            }

            val survival = (robust.size.toFloat() / max(MIN_POINTS, pairs.size).toFloat()).coerceIn(0f, 1f)
            val age = now - state.lastSeedAt
            val ageFactor = (1f - age.toFloat() / HARD_TTL_MS).coerceIn(.25f, 1f)
            val score = (survival * ageFactor).coerceIn(.25f, .98f)
            state.confidence = minOf(state.confidence, .52f + score * .42f)
            observations += RawObservation(
                sourceTrackingId = state.id,
                label = state.label,
                confidence = state.confidence,
                normalizedBox = RectF(state.box),
                fromFlowTracker = true
            )
            scoreSum += score
            if (age > RECHECK_AFTER_MS || score < .58f) needsRecheck = true
        }

        states.entries.removeAll { (_, s) -> now - s.lastGoodAt > HARD_TTL_MS }
        prevGray?.release(); prevGray = gray
        prevPts.release(); nextPts.release(); status.release(); errors.release()
        val average = if (observations.isEmpty()) 0f else scoreSum / observations.size
        return Result(observations, observations.isNotEmpty(), average, needsRecheck)
    }

    private fun detectPoints(gray: Mat, box: RectF): List<Point> {
        val cols = gray.cols(); val rows = gray.rows()
        val insetX = box.width() * .08f; val insetY = box.height() * .08f
        val l = ((box.left + insetX) * cols).toInt().coerceIn(0, cols - 1)
        val t = ((box.top + insetY) * rows).toInt().coerceIn(0, rows - 1)
        val r = ((box.right - insetX) * cols).toInt().coerceIn(l + 1, cols)
        val b = ((box.bottom - insetY) * rows).toInt().coerceIn(t + 1, rows)
        val rect = Rect(l, t, r - l, b - t)
        val roi = gray.submat(rect)
        val corners = MatOfPoint()
        return try {
            Imgproc.goodFeaturesToTrack(roi, corners, MAX_CORNERS_PER_TARGET, .01, 3.0)
            corners.toArray().map { Point(it.x + l, it.y + t) }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            corners.release(); roi.release()
        }
    }

    private fun estimateScale(pairs: List<Pair<Point, Point>>): Float {
        if (pairs.size < 6) return 1f
        val oldCx = pairs.map { it.first.x }.average(); val oldCy = pairs.map { it.first.y }.average()
        val newCx = pairs.map { it.second.x }.average(); val newCy = pairs.map { it.second.y }.average()
        val oldSpread = median(pairs.map { hypot(it.first.x - oldCx, it.first.y - oldCy) })
        val newSpread = median(pairs.map { hypot(it.second.x - newCx, it.second.y - newCy) })
        if (oldSpread < 2.0 || newSpread < 2.0) return 1f
        val raw = (newSpread / oldSpread).toFloat()
        return if (raw in .82f..1.22f) 1f + (raw - 1f) * .28f else 1f
    }

    private fun orientedGray(image: ImageProxy, rotation: Int): Mat {
        val plane = image.planes.first()
        val buffer = plane.buffer.duplicate()
        val raw = ByteArray(image.width * image.height)
        var k = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val index = y * plane.rowStride + x * plane.pixelStride
                raw[k++] = if (index < buffer.limit()) buffer.get(index) else 0
            }
        }
        val source = Mat(image.height, image.width, CvType.CV_8UC1)
        source.put(0, 0, raw)
        if (rotation == 0) return source
        val out = Mat()
        when (rotation) {
            90 -> Core.rotate(source, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(source, out, Core.ROTATE_180)
            270 -> Core.rotate(source, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> source.copyTo(out)
        }
        source.release()
        return out
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val m = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[m - 1] + sorted[m]) * .5 else sorted[m]
    }

    private companion object {
        const val MAX_TARGETS = 8
        const val MAX_CORNERS_PER_TARGET = 28
        const val MIN_POINTS = 4
        const val RESEED_POINTS = 8
        const val MAX_LK_ERROR = 28f
        const val OUTLIER_RADIUS_PX = 6.5
        const val MAX_FRAME_SHIFT = .12
        const val RECHECK_AFTER_MS = 520L
        const val HARD_TTL_MS = 1_150L
    }
}
