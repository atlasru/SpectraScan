package com.atlas.spectrascan

import android.graphics.RectF
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot

/**
 * MINOS-style local micro-motion assist. It never discovers new objects: states
 * are seeded only from semantic tracks and measurements are emitted with the
 * existing stable tracking ID.
 */
internal class LocalTargetMotionTracker {
    data class Result(val observations: List<RawObservation>, val active: Boolean, val needsYoloRecheck: Boolean)

    private data class State(
        val id: Int,
        var label: String,
        var confidence: Float,
        var box: RectF,
        var lastGoodAt: Long
    )

    private val states = linkedMapOf<Int, State>()
    private var prevGray: Mat? = null

    fun reset() {
        states.clear()
        prevGray?.release(); prevGray = null
    }

    fun seed(image: ImageProxy, rotation: Int, targets: List<DetectionTarget>, now: Long) {
        if (!SpectraScanApplication.openCvReady) return
        val keep = mutableSetOf<Int>()
        targets.asSequence()
            .filter { it.status == TrackStatus.TRACKING || it.status == TrackStatus.ACQUIRING }
            .filter { !it.fromFlowTracker && !it.fromMotionTracker && !it.fromBrightnessTracker }
            .sortedByDescending { it.confidence }
            .take(MAX_TARGETS)
            .forEach { t ->
                keep += t.trackingId
                states[t.trackingId] = State(t.trackingId, t.label, t.confidence, RectF(t.normalizedBox), now)
            }
        states.entries.removeAll { (id, s) -> id !in keep && now - s.lastGoodAt > HARD_TTL_MS }
        val gray = orientedGray(image, rotation)
        prevGray?.release(); prevGray = gray
    }

    fun track(image: ImageProxy, rotation: Int, now: Long): Result {
        if (!SpectraScanApplication.openCvReady || states.isEmpty()) return Result(emptyList(), false, false)
        val old = prevGray ?: return Result(emptyList(), false, true)
        val gray = orientedGray(image, rotation)
        if (old.size() != gray.size()) {
            old.release(); prevGray = gray; states.clear(); return Result(emptyList(), false, true)
        }

        val diff = Mat()
        Core.absdiff(old, gray, diff)
        Imgproc.GaussianBlur(diff, diff, Size(3.0, 3.0), 0.0)
        val out = mutableListOf<RawObservation>()
        var recheck = false

        states.values.forEach { s ->
            val roiRect = expandedRect(s.box, gray.cols(), gray.rows()) ?: return@forEach
            val roi = diff.submat(roiRect)
            val mask = Mat()
            Imgproc.threshold(roi, mask, DIFF_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0,2.0)))

            val labels = Mat(); val stats = Mat(); val centroids = Mat()
            val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8, CvType.CV_32S)
            val predictedCx = s.box.centerX() * gray.cols()
            val predictedCy = s.box.centerY() * gray.rows()
            var best: Point? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (i in 1 until count) {
                val area = stats.get(i, Imgproc.CC_STAT_AREA)?.getOrNull(0) ?: continue
                if (area < MIN_AREA || area > roiRect.area() * MAX_AREA_RATIO) continue
                val cx = (centroids.get(i,0)?.getOrNull(0) ?: continue) + roiRect.x
                val cy = (centroids.get(i,1)?.getOrNull(0) ?: continue) + roiRect.y
                val d = hypot(cx - predictedCx, cy - predictedCy)
                val maxD = maxOf(10.0, hypot(s.box.width().toDouble()*gray.cols(), s.box.height().toDouble()*gray.rows()) * 0.85)
                if (d > maxD) continue
                val score = area * 0.08 - d
                if (score > bestScore) { bestScore = score; best = Point(cx, cy) }
            }

            if (best != null) {
                val dx = ((best!!.x - predictedCx) / gray.cols()).toFloat().coerceIn(-MAX_SHIFT, MAX_SHIFT)
                val dy = ((best!!.y - predictedCy) / gray.rows()).toFloat().coerceIn(-MAX_SHIFT, MAX_SHIFT)
                val moved = shift(s.box, dx * MOTION_WEIGHT, dy * MOTION_WEIGHT)
                s.box = moved; s.lastGoodAt = now
                out += RawObservation(
                    sourceTrackingId = s.id,
                    label = s.label,
                    confidence = minOf(s.confidence, 0.72f),
                    normalizedBox = RectF(moved),
                    fromFlowTracker = true
                )
            } else if (now - s.lastGoodAt > RECHECK_AFTER_MS) recheck = true

            roi.release(); mask.release(); labels.release(); stats.release(); centroids.release()
        }

        states.entries.removeAll { (_, s) -> now - s.lastGoodAt > HARD_TTL_MS }
        diff.release(); prevGray?.release(); prevGray = gray
        return Result(out, out.isNotEmpty(), recheck)
    }

    private fun expandedRect(box: RectF, w: Int, h: Int): Rect? {
        val padX = box.width() * SEARCH_PAD
        val padY = box.height() * SEARCH_PAD
        val l = ((box.left - padX).coerceIn(0f,1f) * w).toInt()
        val t = ((box.top - padY).coerceIn(0f,1f) * h).toInt()
        val r = ((box.right + padX).coerceIn(0f,1f) * w).toInt()
        val b = ((box.bottom + padY).coerceIn(0f,1f) * h).toInt()
        if (r-l < 4 || b-t < 4) return null
        return Rect(l,t,r-l,b-t)
    }

    private fun shift(box: RectF, dx: Float, dy: Float): RectF {
        val w = box.width(); val h = box.height()
        val cx = (box.centerX()+dx).coerceIn(w/2f,1f-w/2f)
        val cy = (box.centerY()+dy).coerceIn(h/2f,1f-h/2f)
        return RectF(cx-w/2f, cy-h/2f, cx+w/2f, cy+h/2f)
    }

    private fun orientedGray(image: ImageProxy, rotation: Int): Mat {
        val plane = image.planes.first(); val buffer = plane.buffer.duplicate()
        val raw = ByteArray(image.width*image.height); var k=0
        for(y in 0 until image.height) for(x in 0 until image.width){
            val idx=y*plane.rowStride+x*plane.pixelStride; raw[k++]=if(idx<buffer.limit())buffer.get(idx) else 0
        }
        val src=Mat(image.height,image.width,CvType.CV_8UC1); src.put(0,0,raw)
        if(rotation==0)return src
        val out=Mat(); when(rotation){
            90->Core.rotate(src,out,Core.ROTATE_90_CLOCKWISE)
            180->Core.rotate(src,out,Core.ROTATE_180)
            270->Core.rotate(src,out,Core.ROTATE_90_COUNTERCLOCKWISE)
            else->src.copyTo(out)
        }; src.release(); return out
    }

    private companion object {
        const val MAX_TARGETS = 8
        const val SEARCH_PAD = .38f
        const val DIFF_THRESHOLD = 11.0
        const val MIN_AREA = 3.0
        const val MAX_AREA_RATIO = .32
        const val MAX_SHIFT = .055f
        const val MOTION_WEIGHT = .35f
        const val RECHECK_AFTER_MS = 420L
        const val HARD_TTL_MS = 1_100L
    }
}
