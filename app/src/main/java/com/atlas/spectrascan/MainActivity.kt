package com.atlas.spectrascan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SpectraScanApp() } }
    }
}

private val HudColor = Color(0xFF84D8A0)
private val HudDim = Color(0xFF86A78E)
private val HudAmber = Color(0xFFE6C46A)
private val HudRed = Color(0xFFE36B63)
private const val TRAIL_MAX_AGE_MS = 4_500L
private const val TRAIL_MAX_POINTS = 54

private data class MotionTrailPoint(val x: Float, val y: Float, val at: Long)

@Composable
private fun SpectraScanApp() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) ScannerScreen() else Box(
        Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) { Text("CAMERA PERMISSION REQUIRED", color = HudColor, fontSize = 11.sp) }
}

@Composable
private fun ScannerScreen() {
    var profile by remember { mutableStateOf(TrackingProfile.BALANCED) }
    var targetFilter by remember { mutableStateOf(TargetFilter.ALL) }
    var frame by remember { mutableStateOf(DetectionFrame()) }
    var lockedId by remember { mutableStateOf<Int?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var zoomBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var trailsEnabled by remember { mutableStateOf(true) }
    var trails by remember { mutableStateOf<Map<Int, List<MotionTrailPoint>>>(emptyMap()) }
    var cameraZoom by remember { mutableStateOf(1.0f) }
    var exposureIndex by remember { mutableStateOf(0) }
    var monochrome by remember { mutableStateOf(false) }
    var digitalGain by remember { mutableStateOf(1.0f) }
    var autoNightVision by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }

    val latestFrame by rememberUpdatedState(frame)
    val lockedTarget = frame.targets.firstOrNull { it.trackingId == lockedId }
    val nightVisionActive = autoNightVision && frame.nightVisionSuggested
    val globalStatus = when {
        lockedId != null && lockedTarget == null -> "TARGET LOST"
        lockedTarget != null -> lockedTarget.status.name
        frame.targets.isEmpty() -> "SEARCH"
        else -> "TRACKING"
    }

    LaunchedEffect(lockedId, previewView) {
        while (lockedId != null) {
            val currentFrame = latestFrame
            val target = currentFrame.targets.firstOrNull { it.trackingId == lockedId }
            val source = previewView?.bitmap
            if (target != null && source != null) cropTarget(source, target, currentFrame)?.let { zoomBitmap = it.asImageBitmap() }
            delay(160)
        }
        zoomBitmap = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            profile = profile,
            targetFilter = targetFilter,
            zoomRatio = cameraZoom,
            exposureIndex = exposureIndex,
            monochrome = monochrome,
            digitalGain = digitalGain,
            nightVision = nightVisionActive,
            onPreviewReady = { previewView = it },
            onFrame = { nextFrame ->
                frame = if (nextFrame.detectionThrottled && nextFrame.targets.isEmpty()) nextFrame.copy(targets = frame.targets) else nextFrame
                trails = updateMotionTrails(trails, frame)
            }
        )

        TrackingHud(
            color = HudColor,
            frame = frame,
            lockedId = lockedId,
            trails = if (trailsEnabled) trails else emptyMap(),
            showTargetLeader = lockedId != null,
            onTargetTapped = { tappedId -> lockedId = if (lockedId == tappedId) null else tappedId }
        )

        SensorStatusPanel(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 10.dp),
            frame = frame,
            profile = profile,
            globalStatus = globalStatus,
            cameraZoom = cameraZoom,
            lockedTarget = lockedTarget,
            nightVisionActive = nightVisionActive
        )

        if (lockedId != null) {
            TargetZoomPanel(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp),
                bitmap = zoomBitmap,
                target = lockedTarget,
                color = HudColor,
                onUnlock = { lockedId = null }
            )
        }

        if (settingsOpen) {
            SettingsPanel(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                profile = profile,
                exposureIndex = exposureIndex,
                digitalGain = digitalGain,
                monochrome = monochrome,
                autoNightVision = autoNightVision,
                trailsEnabled = trailsEnabled,
                onProfile = { profile = profile.next() },
                onExposure = { exposureIndex = if (exposureIndex >= 3) -3 else exposureIndex + 1 },
                onGain = {
                    digitalGain = when {
                        digitalGain < 1.2f -> 1.35f
                        digitalGain < 1.5f -> 1.70f
                        digitalGain < 2.0f -> 2.20f
                        else -> 1.0f
                    }
                },
                onMonochrome = { monochrome = !monochrome },
                onAutoNv = { autoNightVision = !autoNightVision },
                onTrails = {
                    trailsEnabled = !trailsEnabled
                    if (!trailsEnabled) trails = emptyMap()
                },
                onClearTrails = { trails = emptyMap() },
                onClose = { settingsOpen = false }
            )
        }

        LegacyBottomBar(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            canLock = frame.targets.isNotEmpty(),
            canMotionLock = frame.targets.any { targetSpeed(it) > 0.015f },
            filter = targetFilter,
            cameraZoom = cameraZoom,
            settingsOpen = settingsOpen,
            onLock = { lockedId = nearestTargetToCenter(frame.targets)?.trackingId },
            onMotionLock = { lockedId = fastestMovingTarget(frame.targets)?.trackingId },
            onFilter = {
                lockedId = null
                trails = emptyMap()
                targetFilter = targetFilter.next()
            },
            onZoom = {
                cameraZoom = when {
                    cameraZoom < 1.5f -> 2.0f
                    cameraZoom < 3.0f -> 4.0f
                    else -> 1.0f
                }
            },
            onSettings = { settingsOpen = !settingsOpen }
        )
    }
}

@Composable
private fun SensorStatusPanel(
    modifier: Modifier,
    frame: DetectionFrame,
    profile: TrackingProfile,
    globalStatus: String,
    cameraZoom: Float,
    lockedTarget: DetectionTarget?,
    nightVisionActive: Boolean
) {
    val statusColor = if (frame.lowLight) HudAmber else HudColor
    Column(
        modifier = modifier
            .width(205.dp)
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, HudDim.copy(alpha = 0.72f))
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        LegacyText("SPECTRASCAN // SENSOR SUITE", HudColor, 10)
        LegacyText("BUILD ${BuildConfig.VERSION_NAME}", HudDim, 8)
        Spacer(Modifier.height(3.dp))
        LegacyText("STATUS   $globalStatus", statusColor, 9)
        LegacyText("DETECT   YOLO11", HudDim, 8)
        LegacyText("RATE     ${frame.inferenceFps.toString().padStart(2, '0')} FPS", HudDim, 8)
        LegacyText("LATENCY  ${frame.inferenceMs.toString().padStart(3, ' ')} MS", HudDim, 8)
        LegacyText("TARGETS  ${frame.targets.size.toString().padStart(2, '0')}", HudDim, 8)
        LegacyText("LUMA     ${frame.meanLuma.toInt().toString().padStart(3, ' ')}", HudDim, 8)
        LegacyText("ZOOM     ${formatZoom(cameraZoom)}", HudDim, 8)
        LegacyText("PROFILE  ${profile.title}", HudDim, 8)
        LegacyText("FILTER   ${frame.targetFilter.title}", HudDim, 8)
        LegacyText("LOCK     ${lockedTarget?.trackingId?.toString()?.padStart(2, '0') ?: "--"}", if (lockedTarget != null) HudAmber else HudDim, 8)
        if (frame.lowLight) LegacyText(if (nightVisionActive) "LOW LIGHT // AUTO NV" else "LOW LIGHT", HudAmber, 8)
    }
}

@Composable
private fun LegacyText(text: String, color: Color, size: Int) {
    Text(text = text, color = color, fontSize = size.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
}

@Composable
private fun LegacyBottomBar(
    modifier: Modifier,
    canLock: Boolean,
    canMotionLock: Boolean,
    filter: TargetFilter,
    cameraZoom: Float,
    settingsOpen: Boolean,
    onLock: () -> Unit,
    onMotionLock: () -> Unit,
    onFilter: () -> Unit,
    onZoom: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.78f))
            .border(1.dp, HudDim.copy(alpha = 0.75f)),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        LegacyButton("LOCK", canLock, false, onLock)
        LegacyButton("M-LOCK", canMotionLock, false, onMotionLock)
        LegacyButton("F:${filter.title}", true, true, onFilter)
        LegacyButton(formatZoom(cameraZoom).uppercase(Locale.US), true, cameraZoom > 1.01f, onZoom)
        LegacyButton("SET", true, settingsOpen, onSettings)
    }
}

@Composable
private fun LegacyButton(text: String, enabled: Boolean, selected: Boolean, onClick: () -> Unit) {
    val c = when {
        !enabled -> Color.Gray.copy(alpha = 0.5f)
        selected -> HudColor
        else -> Color.White.copy(alpha = 0.76f)
    }
    Box(
        modifier = Modifier
            .border(0.5.dp, HudDim.copy(alpha = 0.55f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { LegacyText(text, c, 8) }
}

@Composable
private fun SettingsPanel(
    modifier: Modifier,
    profile: TrackingProfile,
    exposureIndex: Int,
    digitalGain: Float,
    monochrome: Boolean,
    autoNightVision: Boolean,
    trailsEnabled: Boolean,
    onProfile: () -> Unit,
    onExposure: () -> Unit,
    onGain: () -> Unit,
    onMonochrome: () -> Unit,
    onAutoNv: () -> Unit,
    onTrails: () -> Unit,
    onClearTrails: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = modifier
            .width(180.dp)
            .background(Color.Black.copy(alpha = 0.86f))
            .border(1.dp, HudDim.copy(alpha = 0.78f))
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LegacyText("SYSTEM // CONTROL", HudColor, 9)
        LegacySetting("PERF", profile.title, onProfile)
        LegacySetting("EV", if (exposureIndex >= 0) "+$exposureIndex" else "$exposureIndex", onExposure)
        LegacySetting("GAIN", String.format(Locale.US, "%.1fx", digitalGain), onGain)
        LegacySetting("B/W", if (monochrome) "ON" else "OFF", onMonochrome)
        LegacySetting("AUTO NV", if (autoNightVision) "ON" else "OFF", onAutoNv)
        LegacySetting("TRAIL", if (trailsEnabled) "ON" else "OFF", onTrails)
        LegacySetting("TRAIL MEM", "CLEAR", onClearTrails)
        LegacySetting("PANEL", "CLOSE", onClose)
    }
}

@Composable
private fun LegacySetting(name: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, HudDim.copy(alpha = 0.45f)).clickable(onClick = onClick).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        LegacyText(name, HudDim, 8)
        LegacyText(value, HudColor, 8)
    }
}

private fun formatZoom(zoom: Float): String = String.format(Locale.US, "%.1fx", zoom)

@Composable
private fun CameraPreview(
    modifier: Modifier,
    profile: TrackingProfile,
    targetFilter: TargetFilter,
    zoomRatio: Float,
    exposureIndex: Int,
    monochrome: Boolean,
    digitalGain: Float,
    nightVision: Boolean,
    onPreviewReady: (PreviewView) -> Unit,
    onFrame: (DetectionFrame) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFrame by rememberUpdatedState(onFrame)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember { ObjectTrackingAnalyzer(mainExecutor) { latestOnFrame(it) } }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(profile) { analyzer.setProfile(profile) }
    LaunchedEffect(targetFilter) { analyzer.setTargetFilter(targetFilter) }
    LaunchedEffect(digitalGain) { analyzer.setDigitalGain(digitalGain) }

    LaunchedEffect(camera, zoomRatio) {
        val activeCamera = camera ?: return@LaunchedEffect
        val maxZoom = activeCamera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        activeCamera.cameraControl.setZoomRatio(zoomRatio.coerceIn(1f, maxZoom))
    }

    LaunchedEffect(camera, exposureIndex) {
        val activeCamera = camera ?: return@LaunchedEffect
        val state = activeCamera.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) {
            val range = state.exposureCompensationRange
            activeCamera.cameraControl.setExposureCompensationIndex(exposureIndex.coerceIn(range.lower, range.upper))
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                onPreviewReady(this)
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    cameraProvider = provider
                    val selectedCameraInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).first()
                    val previewBuilder = Preview.Builder()
                    CameraEnhancements.configurePreview(previewBuilder, selectedCameraInfo, sharpen = true, denoise = true, stabilization = true)
                    val preview = previewBuilder.build().also { it.setSurfaceProvider(surfaceProvider) }
                    val analysisBuilder = ImageAnalysis.Builder()
                        .setTargetResolution(android.util.Size(640, 480))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    CameraEnhancements.configureAnalysis(analysisBuilder, sharpen = true, denoise = true)
                    val analysis = analysisBuilder.build().also { it.setAnalyzer(analysisExecutor, analyzer) }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, mainExecutor)
            }
        },
        update = { preview -> applyPreviewFilter(preview, monochrome, digitalGain, nightVision) }
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }
}

private fun applyPreviewFilter(preview: PreviewView, monochrome: Boolean, gain: Float, nightVision: Boolean) {
    val effectiveGain = gain.coerceIn(1f, 2.4f)
    val matrix = when {
        nightVision -> {
            val l = 1.35f * effectiveGain
            ColorMatrix(floatArrayOf(
                0.04f*l,0.08f*l,0.02f*l,0f,0f,
                0.30f*l,0.88f*l,0.18f*l,0f,4f,
                0.03f*l,0.10f*l,0.03f*l,0f,0f,
                0f,0f,0f,1f,0f
            ))
        }
        monochrome -> {
            val l = effectiveGain
            ColorMatrix(floatArrayOf(
                0.299f*l,0.587f*l,0.114f*l,0f,0f,
                0.299f*l,0.587f*l,0.114f*l,0f,0f,
                0.299f*l,0.587f*l,0.114f*l,0f,0f,
                0f,0f,0f,1f,0f
            ))
        }
        else -> ColorMatrix(floatArrayOf(
            effectiveGain,0f,0f,0f,0f,
            0f,effectiveGain,0f,0f,0f,
            0f,0f,effectiveGain,0f,0f,
            0f,0f,0f,1f,0f
        ))
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    preview.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    if (preview.childCount > 0) preview.getChildAt(0)?.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
}

@Composable
private fun TrackingHud(
    color: Color,
    frame: DetectionFrame,
    lockedId: Int?,
    trails: Map<Int, List<MotionTrailPoint>>,
    showTargetLeader: Boolean,
    onTargetTapped: (Int) -> Unit
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = 20f
        }
    }

    Canvas(
        Modifier.fillMaxSize().pointerInput(frame.targets, frame.imageWidth, frame.imageHeight, lockedId) {
            detectTapGestures { tap ->
                frame.targets.asReversed().firstOrNull {
                    mapTargetRect(it.normalizedBox, size.width.toFloat(), size.height.toFloat(), frame.imageWidth, frame.imageHeight).contains(tap.x, tap.y)
                }?.let { onTargetTapped(it.trackingId) }
            }
        }
    ) {
        drawMotionTrails(color, trails, frame, lockedId)
        drawLegacyReticle(color)

        frame.targets.forEach { target ->
            val rect = mapTargetRect(target.normalizedBox, size.width, size.height, frame.imageWidth, frame.imageHeight)
            val isLocked = target.trackingId == lockedId
            val targetColor = if (isLocked) HudAmber else statusColor(target.status, color)
            val dashed = target.status == TrackStatus.PREDICTED || target.status == TrackStatus.LOST
            val pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null

            drawRect(
                color = targetColor.copy(alpha = if (isLocked) 0.96f else 0.80f),
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                style = Stroke(if (isLocked) 2.2f else 1.4f, pathEffect = pathEffect)
            )

            if (isLocked) {
                val tick = 8f
                drawLine(targetColor, Offset(rect.left - tick, rect.top), Offset(rect.left, rect.top), 2f)
                drawLine(targetColor, Offset(rect.right, rect.bottom), Offset(rect.right + tick, rect.bottom), 2f)
                drawCircle(targetColor, 3f, Offset(rect.centerX(), rect.centerY()))
                if (showTargetLeader) {
                    val panelAnchor = Offset(size.width - 202f, 82f)
                    val start = Offset(rect.right, rect.top + rect.height() * 0.28f)
                    val elbow = Offset((start.x + panelAnchor.x) * 0.55f, start.y)
                    drawLine(targetColor.copy(alpha = 0.70f), start, elbow, 1.3f)
                    drawLine(targetColor.copy(alpha = 0.70f), elbow, panelAnchor, 1.3f)
                    drawCircle(targetColor.copy(alpha = 0.85f), 2.5f, panelAnchor)
                }
            }

            val percent = if (target.confidence > 0f) " ${(target.confidence * 100).toInt()}%" else ""
            val label = "${target.label}$percent  ID:${target.trackingId.toString().padStart(2, '0')}  ${target.status.name}"
            labelPaint.color = targetColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, rect.left, (rect.top - 6f).coerceAtLeast(24f), labelPaint)
        }

        drawRadar(color, frame.targets, lockedId)
    }
}

private fun DrawScope.drawLegacyReticle(color: Color) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val dim = color.copy(alpha = 0.48f)
    drawLine(dim, Offset(cx - 34f, cy), Offset(cx - 8f, cy), 1f)
    drawLine(dim, Offset(cx + 8f, cy), Offset(cx + 34f, cy), 1f)
    drawLine(dim, Offset(cx, cy - 34f), Offset(cx, cy - 8f), 1f)
    drawLine(dim, Offset(cx, cy + 8f), Offset(cx, cy + 34f), 1f)
    drawCircle(dim, 2f, Offset(cx, cy))

    val pad = 18f
    val len = 34f
    drawLine(dim, Offset(pad, pad), Offset(pad + len, pad), 1f)
    drawLine(dim, Offset(pad, pad), Offset(pad, pad + len), 1f)
    drawLine(dim, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), 1f)
    drawLine(dim, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), 1f)
    drawLine(dim, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), 1f)
    drawLine(dim, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), 1f)
    drawLine(dim, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), 1f)
    drawLine(dim, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), 1f)
}

private fun DrawScope.drawMotionTrails(color: Color, trails: Map<Int, List<MotionTrailPoint>>, frame: DetectionFrame, lockedId: Int?) {
    val now = SystemClock.elapsedRealtime()
    trails.forEach { (id, points) ->
        if (points.size < 2) return@forEach
        val trailColor = if (id == lockedId) HudAmber else color
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            val age = now - b.at
            if (age > TRAIL_MAX_AGE_MS) continue
            val alpha = (1f - age.toFloat() / TRAIL_MAX_AGE_MS).coerceIn(0.04f, 0.42f)
            val start = mapNormalizedPoint(a.x, a.y, size.width, size.height, frame.imageWidth, frame.imageHeight)
            val end = mapNormalizedPoint(b.x, b.y, size.width, size.height, frame.imageWidth, frame.imageHeight)
            drawLine(trailColor.copy(alpha = alpha), start, end, if (id == lockedId) 1.5f else 1f)
        }
    }
}

private fun DrawScope.drawRadar(color: Color, targets: List<DetectionTarget>, lockedId: Int?) {
    val radius = 48f
    val center = Offset(size.width - 66f, size.height - 100f)
    drawRect(Color.Black.copy(alpha = 0.55f), Offset(center.x - 58f, center.y - 58f), Size(116f, 116f))
    drawCircle(color.copy(alpha = 0.55f), radius, center, style = Stroke(1f))
    drawCircle(color.copy(alpha = 0.28f), radius / 2f, center, style = Stroke(1f))
    drawLine(color.copy(alpha = 0.28f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1f)
    drawLine(color.copy(alpha = 0.28f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1f)
    targets.forEach { target ->
        val x = center.x + (target.normalizedBox.centerX() - 0.5f) * radius * 1.65f
        val y = center.y + (target.normalizedBox.centerY() - 0.5f) * radius * 1.65f
        val dotColor = if (target.trackingId == lockedId) HudAmber else statusColor(target.status, color)
        drawCircle(dotColor, if (target.trackingId == lockedId) 4f else 2.5f, Offset(x, y))
    }
}

@Composable
private fun TargetZoomPanel(modifier: Modifier, bitmap: ImageBitmap?, target: DetectionTarget?, color: Color, onUnlock: () -> Unit) {
    val line = statusColor(target?.status, color)
    Column(
        modifier = modifier.width(188.dp).background(Color.Black.copy(alpha = 0.80f)).border(1.dp, line.copy(alpha = 0.78f)).clickable { onUnlock() }.padding(5.dp)
    ) {
        LegacyText("TARGET VIEW // LIVE RGB", line, 8)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(118.dp).background(Color.Black).border(0.5.dp, HudDim.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, "Locked target", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else LegacyText("NO SIGNAL", HudRed, 9)
        }
        Spacer(Modifier.height(4.dp))
        LegacyText("ID      ${target?.trackingId?.toString()?.padStart(2, '0') ?: "--"}", HudDim, 8)
        LegacyText("CLASS   ${target?.label ?: "UNKNOWN"}", line, 8)
        LegacyText("CONF    ${target?.let { "${(it.confidence * 100).toInt()}%" } ?: "--"}", HudDim, 8)
        LegacyText("STATE   ${target?.status?.name ?: "LOST"}", line, 8)
        LegacyText("TAP PANEL // RELEASE", HudDim.copy(alpha = 0.65f), 7)
    }
}

private fun updateMotionTrails(current: Map<Int, List<MotionTrailPoint>>, frame: DetectionFrame): Map<Int, List<MotionTrailPoint>> {
    val now = SystemClock.elapsedRealtime()
    val next = current.mapValues { (_, points) -> points.filter { now - it.at <= TRAIL_MAX_AGE_MS } }.filterValues { it.isNotEmpty() }.toMutableMap()
    frame.targets.forEach { target ->
        if (target.status == TrackStatus.LOST) return@forEach
        if (targetSpeed(target) < 0.006f && next[target.trackingId].isNullOrEmpty()) return@forEach
        val point = MotionTrailPoint(target.normalizedBox.centerX(), target.normalizedBox.centerY(), now)
        val old = next[target.trackingId].orEmpty()
        val last = old.lastOrNull()
        if (last == null || hypot(point.x - last.x, point.y - last.y) >= 0.0045f) next[target.trackingId] = (old + point).takeLast(TRAIL_MAX_POINTS)
    }
    return next
}

private fun statusColor(status: TrackStatus?, default: Color): Color = when (status) {
    TrackStatus.PREDICTED -> HudAmber.copy(alpha = 0.85f)
    TrackStatus.LOST -> HudRed
    TrackStatus.ACQUIRING -> Color(0xFFA7C8AF)
    TrackStatus.TRACKING -> default
    null -> default
}

private fun targetSpeed(target: DetectionTarget): Float = hypot(target.velocityX, target.velocityY)
private fun fastestMovingTarget(targets: List<DetectionTarget>): DetectionTarget? = targets.filter { it.status != TrackStatus.LOST }.maxByOrNull(::targetSpeed)?.takeIf { targetSpeed(it) > 0.015f }
private fun nearestTargetToCenter(targets: List<DetectionTarget>): DetectionTarget? = targets.minByOrNull { hypot(it.normalizedBox.centerX() - 0.5f, it.normalizedBox.centerY() - 0.5f) }

private fun mapNormalizedPoint(x: Float, y: Float, viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): Offset {
    if (imageWidth <= 0 || imageHeight <= 0) return Offset.Zero
    val scale = max(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat())
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    val offsetX = (viewWidth - displayedWidth) / 2f
    val offsetY = (viewHeight - displayedHeight) / 2f
    return Offset(offsetX + x * displayedWidth, offsetY + y * displayedHeight)
}

private fun mapTargetRect(normalizedBox: RectF, viewWidth: Float, viewHeight: Float, imageWidth: Int, imageHeight: Int): RectF {
    if (imageWidth <= 0 || imageHeight <= 0) return RectF()
    val scale = max(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat())
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    val offsetX = (viewWidth - displayedWidth) / 2f
    val offsetY = (viewHeight - displayedHeight) / 2f
    return RectF(
        offsetX + normalizedBox.left * displayedWidth,
        offsetY + normalizedBox.top * displayedHeight,
        offsetX + normalizedBox.right * displayedWidth,
        offsetY + normalizedBox.bottom * displayedHeight
    )
}

private fun cropTarget(source: Bitmap, target: DetectionTarget, frame: DetectionFrame): Bitmap? {
    val mapped = mapTargetRect(target.normalizedBox, source.width.toFloat(), source.height.toFloat(), frame.imageWidth, frame.imageHeight)
    val marginX = mapped.width() * 0.16f
    val marginY = mapped.height() * 0.16f
    val left = (mapped.left - marginX).toInt().coerceIn(0, source.width - 1)
    val top = (mapped.top - marginY).toInt().coerceIn(0, source.height - 1)
    val right = (mapped.right + marginX).toInt().coerceIn(left + 1, source.width)
    val bottom = (mapped.bottom + marginY).toInt().coerceIn(top + 1, source.height)
    return runCatching { Bitmap.createBitmap(source, left, top, right - left, bottom - top) }.getOrNull()
}
