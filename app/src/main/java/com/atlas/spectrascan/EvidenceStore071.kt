package com.atlas.spectrascan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        val entry = Entry(now, "USER", target?.label ?: "TARGET", target?.confidence ?: 0f, target?.trackingId ?: -1, event)
        add(entry)
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

    fun exportText(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "SpectraScan")
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(dir, "session_$stamp.txt").also { file ->
            val body = buildString {
                appendLine("SPECTRASCAN SESSION LOG")
                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine()
                snapshot().forEach { appendLine(it.line()) }
            }
            file.writeText(body)
        }
    }

    fun saveSnapshot(bitmap: Bitmap): String? {
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
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out) }
                ?: error("No output stream")
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        }.getOrElse {
            resolver.delete(uri, null, null)
            null
        }
    }
}
