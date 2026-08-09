package com.atlas.spectrascan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Lightweight evidence/session storage kept outside the tracking pipeline. */
class EvidenceStore071(private val context: Context) {
    data class Entry(
        val timestampMs: Long,
        val source: String,
        val label: String,
        val confidence: Float,
        val trackingId: Int,
        val event: String
    ) {
        fun line(): String {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
            val pct = (confidence.coerceIn(0f, 1f) * 100f).toInt()
            return "[ $time ] - $source/${label.uppercase(Locale.US)}/$pct%/#$trackingId/$event"
        }
    }

    private val entries = ArrayDeque<Entry>()
    private val lastSeen = mutableMapOf<Int, Snapshot>()
    private data class Snapshot(val label: String, val confidenceBucket: Int, val status: TrackStatus, val seenAt: Long)

    @Synchronized
    fun observe(frame: DetectionFrame, now: Long = System.currentTimeMillis()): List<Entry> {
        val emitted = mutableListOf<Entry>()
        val activeIds = frame.targets.mapTo(mutableSetOf()) { it.trackingId }

        frame.targets.forEach { target ->
            val bucket = ((target.confidence.coerceIn(0f, 1f) * 100f).toInt() / 10) * 10
            val previous = lastSeen[target.trackingId]
            val event = when {
                previous == null -> "ACQUIRED"
                previous.label != target.label -> "RECLASSIFIED"
                previous.status == TrackStatus.LOST && target.status != TrackStatus.LOST -> "REACQUIRED"
                target.status == TrackStatus.LOST && previous.status != TrackStatus.LOST -> "LOST"
                kotlin.math.abs(previous.confidenceBucket - bucket) >= 20 -> "UPDATE"
                else -> null
            }
            if (event != null) {
                val entry = Entry(now, "YOLO", target.label, target.confidence, target.trackingId, event)
                add(entry); emitted += entry
            }
            lastSeen[target.trackingId] = Snapshot(target.label, bucket, target.status, now)
        }

        val expired = lastSeen.filter { (id, snapshot) -> id !in activeIds && now - snapshot.seenAt > 1_500L }.keys.toList()
        expired.forEach { id ->
            val old = lastSeen.remove(id) ?: return@forEach
            val entry = Entry(now, "YOLO", old.label, old.confidenceBucket / 100f, id, "DISAPPEARED")
            add(entry); emitted += entry
        }
        return emitted
    }

    @Synchronized
    fun manual(target: DetectionTarget?, event: String, now: Long = System.currentTimeMillis()) {
        add(Entry(now, "USER", target?.label ?: "TARGET", target?.confidence ?: 0f, target?.trackingId ?: -1, event))
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear(); lastSeen.clear()
    }

    @Synchronized
    private fun add(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > 500) entries.removeFirst()
    }

    fun exportText(): String? {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "SpectraScan_session_$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/SpectraScan")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            val body = buildString {
                appendLine("SPECTRASCAN SESSION LOG")
                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine()
                snapshot().forEach { appendLine(it.line()) }
            }
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(body) } ?: error("No output stream")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun saveAnnotatedSnapshot(bitmap: Bitmap, frame: DetectionFrame, lockedId: Int?): String? {
        val annotated = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, annotated.width / 540f)
            color = Color.rgb(97, 255, 178)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = max(18f, annotated.width / 34f)
            color = Color.rgb(97, 255, 178)
        }
        val shadow = Paint(text).apply { color = Color.argb(180, 0, 0, 0); strokeWidth = 6f; style = Paint.Style.STROKE }

        frame.targets.forEach { target ->
            val r = mappedRect(target.normalizedBox, annotated.width.toFloat(), annotated.height.toFloat(), frame.imageWidth, frame.imageHeight)
            val locked = target.trackingId == lockedId
            val color = when {
                locked -> Color.rgb(255, 214, 74)
                target.status == TrackStatus.LOST -> Color.rgb(255, 83, 83)
                target.status == TrackStatus.PREDICTED -> Color.rgb(255, 163, 60)
                else -> Color.rgb(97, 255, 178)
            }
            stroke.color = color; text.color = color
            canvas.drawRect(r, stroke)
            val label = "YOLO/${target.label.uppercase(Locale.US)}/${(target.confidence * 100).toInt()}%/#${target.trackingId}"
            val tx = r.left.coerceAtLeast(4f)
            val ty = (r.top - 8f).coerceAtLeast(text.textSize + 4f)
            canvas.drawText(label, tx, ty, shadow)
            canvas.drawText(label, tx, ty, text)
        }

        val stampText = "SPECTRASCAN ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}"
        text.color = Color.rgb(97, 255, 178)
        val sx = 12f; val sy = annotated.height - 18f
        canvas.drawText(stampText, sx, sy, shadow)
        canvas.drawText(stampText, sx, sy, text)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "SpectraScan_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpectraScan")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return runCatching {
            resolver.openOutputStream(uri)?.use { out -> annotated.compress(Bitmap.CompressFormat.JPEG, 94, out) }
                ?: error("No output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun mappedRect(box: RectF, vw: Float, vh: Float, iw: Int, ih: Int): RectF {
        if (iw <= 0 || ih <= 0) return RectF()
        val scale = max(vw / iw.toFloat(), vh / ih.toFloat())
        val dw = iw * scale
        val dh = ih * scale
        val ox = (vw - dw) / 2f
        val oy = (vh - dh) / 2f
        return RectF(ox + box.left * dw, oy + box.top * dh, ox + box.right * dw, oy + box.bottom * dh)
    }
}
