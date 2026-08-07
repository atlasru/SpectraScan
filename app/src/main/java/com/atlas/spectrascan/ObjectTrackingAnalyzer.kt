package com.atlas.spectrascan

import android.graphics.RectF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class ObjectTrackingAnalyzer(
    private val callbackExecutor: Executor,
    private val onFrame: (DetectionFrame) -> Unit
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val busy = AtomicBoolean(false)
    private val tracker = HybridTracker()
    private var lastResultAt = 0L

    @Volatile
    private var targetFilter: TargetFilter = TargetFilter.ALL

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )

    fun setProfile(profile: TrackingProfile) {
        tracker.profile = profile
    }

    fun setTargetFilter(filter: TargetFilter) {
        if (targetFilter != filter) {
            targetFilter = filter
            tracker.reset()
        }
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            busy.set(false)
            imageProxy.close()
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        val rotation = imageProxy.imageInfo.rotationDegrees
        val orientedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val orientedHeight = if (rotation == 90 || rotation == 270) imageProxy.width else imageProxy.height
        val activeFilter = targetFilter
        val brightObservation = if (activeFilter == TargetFilter.ALL || activeFilter == TargetFilter.SCREENS) {
            findBrightRegion(imageProxy, rotation)
        } else {
            null
        }
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener(callbackExecutor) { detectedObjects ->
                val now = SystemClock.elapsedRealtime()
                val rawObservations = mutableListOf<RawObservation>()
                var rejectedCandidates = 0

                if (activeFilter != TargetFilter.SCREENS) {
                    detectedObjects.forEach { detected ->
                        val box = detected.boundingBox
                        val bestLabel = detected.labels.maxByOrNull { it.confidence }
                        val label = bestLabel?.text
                            ?.takeIf { it.isNotBlank() }
                            ?.uppercase(Locale.US)
                            ?: "TARGET"
                        val confidence = bestLabel?.confidence ?: 0f
                        val normalizedBox = RectF(
                            (box.left.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.top.toFloat() / orientedHeight).coerceIn(0f, 1f),
                            (box.right.toFloat() / orientedWidth).coerceIn(0f, 1f),
                            (box.bottom.toFloat() / orientedHeight).coerceIn(0f, 1f)
                        )

                        if (acceptMlObservation(label, confidence, normalizedBox, activeFilter)) {
                            rawObservations += RawObservation(
                                sourceTrackingId = detected.trackingId,
                                label = label,
                                confidence = confidence,
                                normalizedBox = normalizedBox,
                                fromBrightnessTracker = false
                            )
                        } else {
                            rejectedCandidates++
                        }
                    }
                } else {
                    rejectedCandidates += detectedObjects.size
                }

                if (brightObservation != null && rawObservations.none {
                        intersectionOverUnion(it.normalizedBox, brightObservation.normalizedBox) > 0.28f
                    }
                ) {
                    rawObservations += brightObservation
                }

                dispatchFrame(
                    targets = tracker.update(rawObservations, now),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = now,
                    brightTrackerActive = brightObservation != null,
                    activeFilter = activeFilter,
                    rejectedCandidates = rejectedCandidates
                )
            }
            .addOnFailureListener(callbackExecutor) {
                val now = SystemClock.elapsedRealtime()
                val fallback = brightObservation?.let(::listOf).orEmpty()
                dispatchFrame(
                    targets = tracker.update(fallback, now),
                    orientedWidth = orientedWidth,
                    orientedHeight = orientedHeight,
                    startedAt = startedAt,
                    now = now,
                    brightTrackerActive = brightObservation != null,
                    activeFilter = activeFilter,
                    rejectedCandidates = 0
                )
            }
            .addOnCompleteListener(callbackExecutor) {
                busy.set(false)
                imageProxy.close()
            }
    }

    private fun acceptMlObservation(
        label: String,
        confidence: Float,
        box: RectF,
        filter: TargetFilter
    ): Boolean {
        if (!passesGeometryFilter(box, filter)) return false

        return when (filter) {
            TargetFilter.SCREENS -> false
            TargetFilter.PEOPLE -> label in PEOPLE_LABELS && confidence >= 0.55f
            TargetFilter.ANIMALS -> label in ANIMAL_LABELS && confidence >= 0.55f
            TargetFilter.OBJECTS -> when (label) {
                "HOME GOOD" -> confidence >= 0.78f
                "FASHION GOOD" -> confidence >= 0.75f
                "FOOD" -> confidence >= 0.72f
                "PLANT" -> confidence >= 0.82f
                "PLACE" -> false
                "TARGET" -> false
                else -> confidence >= 0.72f
            }
            TargetFilter.ALL -> when (label) {
                "HOME GOOD" -> confidence >= 0.92f
                "FASHION GOOD" -> confidence >= 0.86f
                "FOOD" -> confidence >= 0.82f
                "PLANT", "PLACE", "TARGET" -> false
                else -> confidence >= 0.78f
            }
        }
    }

    private fun passesGeometryFilter(box: RectF, filter: TargetFilter): Boolean {
        val width = box.width()
        val height = box.height()
        val area = width * height
        if (width <= 0f || height <= 0f) return false
        if (width < 0.025f || height < 0.025f || area < 0.0012f) return false

        val maxSide = if (filter == TargetFilter.OBJECTS) 0.74f else 0.64f
        val maxArea = if (filter == TargetFilter.OBJECTS) 0.36f else 0.26f
        if (width > maxSide || height > maxSide || area > maxArea) return false

        val aspect = width / height
        if (aspect !in 0.18f..5.5f) return false
        return true
    }

    private fun dispatchFrame(
        targets: List<DetectionTarget>,
        orientedWidth: Int,
        orientedHeight: Int,
        startedAt: Long,
        now: Long,
        brightTrackerActive: Boolean,
        activeFilter: TargetFilter,
        rejectedCandidates: Int
    ) {
        val frameDelta = if (lastResultAt == 0L) 0L else now - lastResultAt
        lastResultAt = now
        val fps = if (frameDelta <= 0L) 0 else {
            (1000L / max(1L, frameDelta)).toInt().coerceIn(0, 60)
        }

        onFrame(
            DetectionFrame(
                targets = targets,
                imageWidth = orientedWidth,
                imageHeight = orientedHeight,
                inferenceFps = fps,
                inferenceMs = now - startedAt,
                brightTrackerActive = brightTrackerActive,
                targetFilter = activeFilter,
                rejectedCandidates = rejectedCandidates
            )
        )
    }

    private fun findBrightRegion(imageProxy: ImageProxy, rotation: Int): RawObservation? {
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val buffer = plane.buffer.duplicate()
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = 4

        var sum = 0L
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit()) {
                    sum += buffer.get(index).toInt() and 0xFF
                    samples++
                }
                x += step
            }
            y += step
        }
        if (samples == 0) return null

        val mean = sum.toFloat() / samples
        if (mean > 105f) return null
        val threshold = maxOf(178f, mean + 68f).toInt()

        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var brightCount = 0
        y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val index = y * rowStride + x * pixelStride
                if (index < buffer.limit() && (buffer.get(index).toInt() and 0xFF) >= threshold) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    brightCount++
                }
                x += step
            }
            y += step
        }

        val ratio = brightCount.toFloat() / samples.toFloat()
        if (maxX <= minX || maxY <= minY || ratio !in 0.0015f..0.12f) return null

        val rawRect = RectF(
            (minX.toFloat() / width - 0.02f).coerceIn(0f, 1f),
            (minY.toFloat() / height - 0.02f).coerceIn(0f, 1f),
            (maxX.toFloat() / width + 0.02f).coerceIn(0f, 1f),
            (maxY.toFloat() / height + 0.02f).coerceIn(0f, 1f)
        )
        val orientedRect = rotateNormalizedRect(rawRect, rotation)
        val area = orientedRect.width() * orientedRect.height()
        if (orientedRect.width() !in 0.025f..0.58f ||
            orientedRect.height() !in 0.025f..0.58f ||
            area !in 0.0012f..0.18f
        ) return null

        return RawObservation(
            sourceTrackingId = null,
            label = "BRIGHT OBJECT",
            confidence = (0.46f + ratio * 3.2f).coerceIn(0.46f, 0.88f),
            normalizedBox = orientedRect,
            fromBrightnessTracker = true
        )
    }

    private fun rotateNormalizedRect(rect: RectF, rotation: Int): RectF = when (rotation) {
        90 -> RectF(1f - rect.bottom, rect.left, 1f - rect.top, rect.right)
        180 -> RectF(1f - rect.right, 1f - rect.bottom, 1f - rect.left, 1f - rect.top)
        270 -> RectF(rect.top, 1f - rect.right, rect.bottom, 1f - rect.left)
        else -> RectF(rect)
    }

    private fun intersectionOverUnion(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    override fun close() {
        tracker.reset()
        detector.close()
    }

    private companion object {
        val PEOPLE_LABELS = setOf("PERSON", "PEOPLE", "HUMAN")
        val ANIMAL_LABELS = setOf(
            "ANIMAL", "CAT", "DOG", "BIRD", "HORSE", "SHEEP", "COW", "BEAR"
        )
    }
}
