package com.atlas.spectrascan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

internal data class YoloDetection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val normalizedBox: RectF
)

internal class YoloDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        // Two worker threads are a better sustained-power tradeoff on phones than
        // keeping four big CPU cores busy for every inference.
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val session: OrtSession
    private val inputName: String

    // Reused inference memory: avoid allocating a new 640x640 bitmap, pixel array
    // and ~4.9 MB float buffer several times per second.
    private val scaledBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    private val scaledCanvas = Canvas(scaledBitmap)
    private val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
    private val inputBuffer = ByteBuffer
        .allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    init {
        val modelBytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
        session = environment.createSession(modelBytes, sessionOptions)
        inputName = session.inputNames.first()
    }

    @Synchronized
    fun detect(bitmap: Bitmap, filter: TargetFilter, gain: Float = 1f): Pair<List<YoloDetection>, Int> {
        if (filter == TargetFilter.MOTION) return emptyList<YoloDetection>() to 0

        scaledCanvas.drawColor(Color.BLACK)
        scaledCanvas.drawBitmap(bitmap, null, Rect(0, 0, INPUT_SIZE, INPUT_SIZE), scalePaint)
        scaledBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        val effectiveGain = gain.coerceIn(1f, 2.4f)
        inputBuffer.clear()
        for (channel in 0..2) {
            for (pixel in pixels) {
                val raw = when (channel) {
                    0 -> (pixel shr 16) and 0xFF
                    1 -> (pixel shr 8) and 0xFF
                    else -> pixel and 0xFF
                }
                inputBuffer.put((raw * effectiveGain).coerceAtMost(255f) / 255f)
            }
        }
        inputBuffer.flip()

        val tensor = OnnxTensor.createTensor(
            environment,
            inputBuffer,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        var rejected = 0
        val candidates = mutableListOf<YoloDetection>()
        tensor.use { input ->
            session.run(mapOf(inputName to input)).use { result ->
                val value = result[0].value
                val batch = value as? Array<*> ?: return@use
                val channels = batch.firstOrNull() as? Array<*> ?: return@use
                if (channels.size < 84) return@use

                val boxX = channels[0] as? FloatArray ?: return@use
                val boxY = channels[1] as? FloatArray ?: return@use
                val boxW = channels[2] as? FloatArray ?: return@use
                val boxH = channels[3] as? FloatArray ?: return@use
                val anchors = minOf(boxX.size, boxY.size, boxW.size, boxH.size)

                for (i in 0 until anchors) {
                    var bestClass = -1
                    var bestScore = 0f
                    for (classId in COCO_LABELS.indices) {
                        val scores = channels[4 + classId] as? FloatArray ?: continue
                        if (i >= scores.size) continue
                        val score = scores[i]
                        if (score > bestScore) { bestScore = score; bestClass = classId }
                    }
                    if (bestClass < 0) continue
                    val label = COCO_LABELS[bestClass]
                    if (bestScore < confidenceThreshold(label) || !filterAccepts(label, filter)) {
                        if (bestScore >= 0.10f) rejected++
                        continue
                    }

                    val cx = boxX[i] / INPUT_SIZE
                    val cy = boxY[i] / INPUT_SIZE
                    val width = boxW[i] / INPUT_SIZE
                    val height = boxH[i] / INPUT_SIZE
                    val rect = RectF(
                        (cx - width / 2f).coerceIn(0f, 1f),
                        (cy - height / 2f).coerceIn(0f, 1f),
                        (cx + width / 2f).coerceIn(0f, 1f),
                        (cy + height / 2f).coerceIn(0f, 1f)
                    )
                    if (!geometryAccepts(rect, label)) { rejected++; continue }
                    candidates += YoloDetection(bestClass, label.uppercase(), bestScore, rect)
                }
            }
        }
        return nonMaxSuppression(candidates) to rejected
    }

    private fun confidenceThreshold(label: String): Float = when (label) {
        "person" -> 0.24f
        "cell phone" -> 0.18f
        "tv", "laptop", "remote", "clock" -> 0.22f
        "cat", "dog", "bird", "horse", "sheep", "cow" -> 0.24f
        else -> 0.30f
    }

    private fun filterAccepts(label: String, filter: TargetFilter): Boolean = when (filter) {
        TargetFilter.ALL -> true
        TargetFilter.PEOPLE -> label == "person"
        TargetFilter.ANIMALS -> label in ANIMAL_LABELS
        TargetFilter.SCREENS -> label in SCREEN_LABELS
        TargetFilter.OBJECTS -> label != "person" && label !in ANIMAL_LABELS
        TargetFilter.MOTION -> false
    }

    private fun geometryAccepts(box: RectF, label: String): Boolean {
        val width = box.width(); val height = box.height(); val area = width * height
        if (width <= 0f || height <= 0f) return false
        val minArea = when (label.lowercase()) { "cell phone", "remote", "clock" -> 0.00025f; else -> 0.0007f }
        if (area < minArea) return false
        if (label.equals("person", true)) {
            if (width > 0.98f || height > 0.98f || area > 0.86f) return false
        } else if (width > 0.90f || height > 0.90f || area > 0.70f) return false
        val aspect = width / max(height, 0.0001f)
        return aspect in 0.08f..12f
    }

    private fun nonMaxSuppression(input: List<YoloDetection>): List<YoloDetection> {
        val kept = mutableListOf<YoloDetection>()
        for (candidate in input.sortedByDescending { it.confidence }) {
            val overlaps = kept.any { it.classId == candidate.classId && intersectionOverUnion(it.normalizedBox, candidate.normalizedBox) > NMS_IOU }
            if (!overlaps) { kept += candidate; if (kept.size >= MAX_DETECTIONS) break }
        }
        return kept
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        session.close()
        sessionOptions.close()
        scaledBitmap.recycle()
    }

    private companion object {
        const val MODEL_FILE = "yolo11n.onnx"
        const val INPUT_SIZE = 640
        const val NMS_IOU = 0.45f
        const val MAX_DETECTIONS = 24
        val ANIMAL_LABELS = setOf("bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
        val SCREEN_LABELS = setOf("tv", "laptop", "cell phone", "remote", "clock")
        val COCO_LABELS = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
            "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
            "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
            "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
            "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
            "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        )
    }
}
