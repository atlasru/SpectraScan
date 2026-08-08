package com.atlas.spectrascan

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SpectraScanApp() } }
    }
}

private enum class UiTheme(val title: String) {
    STANDARD("STANDARD"),
    FUTURE("FUTURE"),
    MINOS("MINOS");

    fun next(): UiTheme = entries[(ordinal + 1) % entries.size]
}

private data class ThemePalette(
    val primary: Color,
    val dim: Color,
    val accent: Color,
    val danger: Color
)

private fun palette(theme: UiTheme): ThemePalette = when (theme) {
    UiTheme.STANDARD -> ThemePalette(Color(0xFF61FFB2), Color(0xFFB8CFC2), Color(0xFFFFD64A), Color(0xFFFF5353))
    UiTheme.FUTURE -> ThemePalette(Color(0xFF4CFFD6), Color(0xFF74C9D6), Color(0xFFFFC857), Color(0xFFFF5D73))
    UiTheme.MINOS -> ThemePalette(Color(0xFF28F59A), Color(0xFF6FBF91), Color(0xFFFFC23A), Color(0xFFFF6B3D))
}

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
    ) { Text("CAMERA PERMISSION REQUIRED", color = Color(0xFF61FFB2), fontFamily = FontFamily.Monospace) }
}

@Composable
private fun ScannerScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("spectrascan_ui", Context.MODE_PRIVATE) }
    var uiTheme by remember {
        mutableStateOf(runCatching { UiTheme.valueOf(prefs.getString("theme", UiTheme.STANDARD.name)!!) }.getOrDefault(UiTheme.STANDARD))
    }
    val colors = palette(uiTheme)

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
            delay(if (uiTheme == UiTheme.MINOS) 110 else 160)
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

        ThemeHud(
            theme = uiTheme,
            colors = colors,
            frame = frame,
            lockedId = lockedId,
            trails = if (trailsEnabled) trails else emptyMap(),
            onTargetTapped = { tappedId -> lockedId = if (lockedId == tappedId) null else tappedId }
        )

        when (uiTheme) {
            UiTheme.STANDARD -> StandardStatus(frame, profile, globalStatus, colors)
            UiTheme.FUTURE -> FutureStatus(frame, profile, globalStatus, colors)
            UiTheme.MINOS -> MinosStatus(frame, profile, globalStatus, cameraZoom, lockedTarget, colors)
        }

        if (lockedId != null) {
            TargetPanel(
                theme = uiTheme,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = if (uiTheme == UiTheme.STANDARD) 90.dp else 10.dp, end = 10.dp),
                bitmap = zoomBitmap,
                target = lockedTarget,
                colors = colors,
                onUnlock = { lockedId = null }
            )
        }

        if (settingsOpen) {
            SettingsPanel(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                theme = uiTheme,
                colors = colors,
                profile = profile,
                exposureIndex = exposureIndex,
                digitalGain = digitalGain,
                monochrome = monochrome,
                autoNightVision = autoNightVision,
                trailsEnabled = trailsEnabled,
                onTheme = {
                    uiTheme = uiTheme.next()
                    prefs.edit().putString("theme", uiTheme.name).apply()
                },
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

        BottomBar(
            theme = uiTheme,
            colors = colors,
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
private fun StandardStatus(frame: DetectionFrame, profile: TrackingProfile, globalStatus: String, colors: ThemePalette) {
    Column(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoText("SPECTRASCAN ${BuildConfig.VERSION_NAME}", colors.primary, 15)
        MonoText("YOLO ${frame.inferenceFps.toString().padStart(2, '0')} FPS  ${frame.inferenceMs} MS  TGT ${frame.targets.size.toString().padStart(2, '0')}", colors.primary.copy(alpha = 0.82f), 10)
        MonoText("$globalStatus // ${frame.targetFilter.title} // ${profile.title} // LUMA ${frame.meanLuma.toInt()}", statusColor(frame.targets.firstOrNull()?.status, colors), 10)
    }
}

@Composable
private fun FutureStatus(frame: DetectionFrame, profile: TrackingProfile, globalStatus: String, colors: ThemePalette) {
    Column(
        Modifier.alignTopCenterBox().background(Color.Black.copy(alpha = 0.35f)).border(1.dp, colors.primary.copy(alpha = 0.35f)).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoText("// SPECTRASCAN ${BuildConfig.VERSION_NAME} //", colors.primary, 13)
        MonoText("SYS $globalStatus | AI ${frame.inferenceFps}FPS | ${frame.inferenceMs}MS | ${profile.title}", colors.dim, 8)
    }
}

private fun Modifier.alignTopCenterBox(): Modifier = this.fillMaxWidth().padding(top = 12.dp, start = 92.dp, end = 92.dp)

@Composable
private fun MinosStatus(
    frame: DetectionFrame,
    profile: TrackingProfile,
    globalStatus: String,
    cameraZoom: Float,
    lockedTarget: DetectionTarget?,
    colors: ThemePalette
) {
    Column(
        Modifier.width(246.dp).padding(start = 7.dp, top = 8.dp).background(Color.Black.copy(alpha = 0.46f)).border(1.dp, colors.primary.copy(alpha = 0.50f)).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        MonoText("SPECTRASCAN // EXPERIMENTAL SENSOR SUITE", colors.primary, 8)
        MonoText("SCAN MODE: ${frame.targetFilter.title}    RENDER: LIVE RGB", colors.primary, 7)
        MonoText("CAM ZOOM: ${formatZoom(cameraZoom)}    LOCK: ${lockedTarget?.trackingId ?: "--"}", colors.dim, 7)
        MonoText("AI: YOLO11    RATE:${frame.inferenceFps}FPS    LAT:${frame.inferenceMs}MS", colors.dim, 7)
        MonoText("STATUS:$globalStatus    PROFILE:${profile.title}", colors.dim, 7)
        MonoText("TARGETS:${frame.targets.size}    LUMA:${frame.meanLuma.toInt()}    ISP:ON", colors.dim, 7)
        MonoText("HEAT:OFF    LOGS:0    AUTOLOCK:OFF", colors.dim, 7)
    }
}

@Composable
private fun MonoText(text: String, color: Color, size: Int) {
    Text(text, color = color, fontSize = size.sp, fontFamily = FontFamily.Monospace)
}

@Composable
private fun BottomBar(
    theme: UiTheme,
    colors: ThemePalette,
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
    val panelAlpha = if (theme == UiTheme.FUTURE) 0.55f else 0.78f
    Row(
        modifier.background(Color.Black.copy(alpha = panelAlpha)).border(1.dp, colors.dim.copy(alpha = 0.62f)),
        horizontalArrangement = Arrangement.spacedBy(if (theme == UiTheme.FUTURE) 4.dp else 0.dp)
    ) {
        ThemedButton("LOCK", canLock, false, theme, colors, onLock)
        ThemedButton(if (theme == UiTheme.MINOS) "MOTION" else "M-LOCK", canMotionLock, false, theme, colors, onMotionLock)
        ThemedButton("F:${filter.title}", true, true, theme, colors, onFilter)
        ThemedButton(formatZoom(cameraZoom).uppercase(Locale.US), true, cameraZoom > 1.01f, theme, colors, onZoom)
        ThemedButton(if (theme == UiTheme.MINOS) "MENU" else "SET", true, settingsOpen, theme, colors, onSettings)
    }
}

@Composable
private fun ThemedButton(text: String, enabled: Boolean, selected: Boolean, theme: UiTheme, colors: ThemePalette, onClick: () -> Unit) {
    val c = when {
        !enabled -> Color.Gray.copy(alpha = 0.48f)
        selected -> colors.primary
        else -> Color.White.copy(alpha = 0.76f)
    }
    val bg = if (theme == UiTheme.FUTURE) colors.primary.copy(alpha = if (selected) 0.12f else 0.03f) else Color.Transparent
    Box(
        Modifier.background(bg).border(if (theme == UiTheme.FUTURE) 1.dp else 0.5.dp, colors.dim.copy(alpha = 0.5f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) { MonoText(text, c, if (theme == UiTheme.MINOS) 7 else 8) }
}

@Composable
private fun SettingsPanel(
    modifier: Modifier,
    theme: UiTheme,
    colors: ThemePalette,
    profile: TrackingProfile,
    exposureIndex: Int,
    digitalGain: Float,
    monochrome: Boolean,
    autoNightVision: Boolean,
    trailsEnabled: Boolean,
    onTheme: () -> Unit,
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
        modifier.width(190.dp).background(Color.Black.copy(alpha = 0.90f)).border(1.dp, colors.dim.copy(alpha = 0.75f)).padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MonoText(if (theme == UiTheme.MINOS) "SYSTEM // CONTROL" else "SETTINGS", colors.primary, 9)
        SettingRow("THEME", theme.title, colors, onTheme)
        SettingRow("PERF", profile.title, colors, onProfile)
        SettingRow("EV", if (exposureIndex >= 0) "+$exposureIndex" else "$exposureIndex", colors, onExposure)
        SettingRow("GAIN", String.format(Locale.US, "%.1fx", digitalGain), colors, onGain)
        SettingRow("B/W", if (monochrome) "ON" else "OFF", colors, onMonochrome)
        SettingRow("AUTO NV", if (autoNightVision) "ON" else "OFF", colors, onAutoNv)
        SettingRow("TRAIL", if (trailsEnabled) "ON" else "OFF", colors, onTrails)
        SettingRow("TRAIL MEM", "CLEAR", colors, onClearTrails)
        SettingRow("PANEL", "CLOSE", colors, onClose)
    }
}

@Composable
private fun SettingRow(name: String, value: String, colors: ThemePalette, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(0.5.dp, colors.dim.copy(alpha = 0.45f)).clickable(onClick = onClick).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        MonoText(name, colors.dim, 8)
        MonoText(value, colors.primary, 8)
    }
}

@Composable
private fun TargetPanel(theme: UiTheme, modifier: Modifier, bitmap: ImageBitmap?, target: DetectionTarget?, colors: ThemePalette, onUnlock: () -> Unit) {
    val line = statusColor(target?.status, colors)
    val width = when (theme) { UiTheme.MINOS -> 205.dp; UiTheme.FUTURE -> 176.dp; UiTheme.STANDARD -> 185.dp }
    val imageHeight = when (theme) { UiTheme.MINOS -> 128.dp; UiTheme.FUTURE -> 108.dp; UiTheme.STANDARD -> 118.dp }
    Column(modifier.width(width).background(Color.Black.copy(alpha = 0.82f)).border(1.dp, line).clickable { onUnlock() }.padding(5.dp)) {
        MonoText(when (theme) {
            UiTheme.MINOS -> "LIVE RGB CROP // TARGET"
            UiTheme.FUTURE -> "TARGET INSPECTOR"
            UiTheme.STANDARD -> "TARGET VIEW"
        }, line, 8)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(imageHeight).background(Color.Black).border(0.5.dp, colors.dim.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, "Locked target", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else MonoText("NO SIGNAL", colors.danger, 9)
        }
        Spacer(Modifier.height(3.dp))
        when (theme) {
            UiTheme.MINOS -> {
                MonoText("X/Y TRACK // ID:${target?.trackingId ?: "--"}", colors.dim, 7)
                MonoText("${target?.label ?: "UNKNOWN"} ${(target?.confidence?.times(100))?.toInt() ?: 0}% // ${target?.status?.name ?: "LOST"}", line, 7)
            }
            UiTheme.FUTURE -> {
                MonoText("ID ${target?.trackingId ?: "--"} // ${target?.label ?: "UNKNOWN"}", line, 8)
                MonoText("CONF ${(target?.confidence?.times(100))?.toInt() ?: 0}% // ${target?.status?.name ?: "LOST"}", colors.dim, 7)
            }
            UiTheme.STANDARD -> MonoText("${target?.label ?: "UNKNOWN"} #${target?.trackingId ?: "--"} // ${target?.status?.name ?: "LOST"}", line, 8)
        }
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
                    val analysisBuilder = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
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
private fun ThemeHud(
    theme: UiTheme,
    colors: ThemePalette,
    frame: DetectionFrame,
    lockedId: Int?,
    trails: Map<Int, List<MotionTrailPoint>>,
    onTargetTapped: (Int) -> Unit
) {
    val labelPaint = remember(theme) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, if (theme == UiTheme.FUTURE) Typeface.BOLD else Typeface.NORMAL)
            textSize = if (theme == UiTheme.MINOS) 18f else 20f
        }
    }

    Canvas(Modifier.fillMaxSize().pointerInput(frame.targets, frame.imageWidth, frame.imageHeight, lockedId, theme) {
        detectTapGestures { tap ->
            frame.targets.asReversed().firstOrNull {
                mapTargetRect(it.normalizedBox, size.width.toFloat(), size.height.toFloat(), frame.imageWidth, frame.imageHeight).contains(tap.x, tap.y)
            }?.let { onTargetTapped(it.trackingId) }
        }
    }) {
        drawTrails(theme, colors, trails, frame, lockedId)
        when (theme) {
            UiTheme.STANDARD -> drawStandardReticle(colors)
            UiTheme.FUTURE -> drawFutureReticle(colors)
            UiTheme.MINOS -> drawMinosReticle(colors)
        }

        frame.targets.forEach { target ->
            val rect = mapTargetRect(target.normalizedBox, size.width, size.height, frame.imageWidth, frame.imageHeight)
            val locked = target.trackingId == lockedId
            val targetColor = if (locked) colors.accent else statusColor(target.status, colors)
            val dashed = target.status == TrackStatus.PREDICTED || target.status == TrackStatus.LOST
            val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null

            when (theme) {
                UiTheme.STANDARD -> drawRect(targetColor, Offset(rect.left, rect.top), Size(rect.width(), rect.height()), style = Stroke(if (locked) 2.5f else 1.5f, pathEffect = dash))
                UiTheme.FUTURE -> drawFutureBox(rect, targetColor, locked, dashed)
                UiTheme.MINOS -> drawRect(targetColor, Offset(rect.left, rect.top), Size(rect.width(), rect.height()), style = Stroke(if (locked) 2f else 1.2f, pathEffect = dash))
            }

            if (locked) drawLeader(theme, targetColor, rect)

            val conf = if (target.confidence > 0f) " ${(target.confidence * 100).toInt()}%" else ""
            val label = when (theme) {
                UiTheme.STANDARD -> "${target.label}$conf // #${target.trackingId}"
                UiTheme.FUTURE -> "${target.status.name} // ${target.label}$conf // ID ${target.trackingId}"
                UiTheme.MINOS -> "YOLO // ${target.label}$conf // #${target.trackingId}"
            }
            labelPaint.color = targetColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(label, rect.left, (rect.top - 6f).coerceAtLeast(24f), labelPaint)
        }

        drawRadar(theme, colors, frame.targets, lockedId)
    }
}

private fun DrawScope.drawFutureBox(rect: RectF, c: Color, locked: Boolean, dashed: Boolean) {
    if (dashed) {
        drawRect(c, Offset(rect.left, rect.top), Size(rect.width(), rect.height()), style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))))
        return
    }
    val len = max(14f, min(rect.width(), rect.height()) * 0.23f)
    val w = if (locked) 3f else 2f
    drawLine(c, Offset(rect.left, rect.top), Offset(rect.left + len, rect.top), w)
    drawLine(c, Offset(rect.left, rect.top), Offset(rect.left, rect.top + len), w)
    drawLine(c, Offset(rect.right, rect.top), Offset(rect.right - len, rect.top), w)
    drawLine(c, Offset(rect.right, rect.top), Offset(rect.right, rect.top + len), w)
    drawLine(c, Offset(rect.left, rect.bottom), Offset(rect.left + len, rect.bottom), w)
    drawLine(c, Offset(rect.left, rect.bottom), Offset(rect.left, rect.bottom - len), w)
    drawLine(c, Offset(rect.right, rect.bottom), Offset(rect.right - len, rect.bottom), w)
    drawLine(c, Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - len), w)
    if (locked) drawCircle(c.copy(alpha = 0.65f), 9f, Offset(rect.centerX(), rect.centerY()), style = Stroke(1.5f))
}

private fun DrawScope.drawLeader(theme: UiTheme, c: Color, rect: RectF) {
    val anchor = when (theme) {
        UiTheme.STANDARD -> Offset(size.width - 205f, 125f)
        UiTheme.FUTURE -> Offset(size.width - 192f, 70f)
        UiTheme.MINOS -> Offset(size.width - 220f, 82f)
    }
    val start = Offset(rect.right, rect.top + rect.height() * 0.30f)
    val elbow = Offset((start.x + anchor.x) * 0.55f, start.y)
    drawLine(c.copy(alpha = 0.72f), start, elbow, 1.2f)
    drawLine(c.copy(alpha = 0.72f), elbow, anchor, 1.2f)
    if (theme == UiTheme.MINOS) {
        drawCircle(c.copy(alpha = 0.8f), 4f, start, style = Stroke(1f))
        drawCircle(c.copy(alpha = 0.8f), 3f, anchor, style = Stroke(1f))
    }
}

private fun DrawScope.drawStandardReticle(colors: ThemePalette) {
    val cx = size.width / 2f; val cy = size.height / 2f; val c = colors.primary.copy(alpha = 0.45f)
    drawLine(c, Offset(cx - 34f, cy), Offset(cx - 8f, cy), 1f)
    drawLine(c, Offset(cx + 8f, cy), Offset(cx + 34f, cy), 1f)
    drawLine(c, Offset(cx, cy - 34f), Offset(cx, cy - 8f), 1f)
    drawLine(c, Offset(cx, cy + 8f), Offset(cx, cy + 34f), 1f)
    drawCircle(c, 2f, Offset(cx, cy))
}

private fun DrawScope.drawFutureReticle(colors: ThemePalette) {
    val cx = size.width / 2f; val cy = size.height / 2f; val c = colors.primary.copy(alpha = 0.62f)
    drawCircle(c, 54f, Offset(cx, cy), style = Stroke(1.3f))
    drawCircle(c.copy(alpha = 0.7f), 7f, Offset(cx, cy), style = Stroke(1.2f))
    drawLine(c, Offset(cx - 88f, cy), Offset(cx - 16f, cy), 1.2f)
    drawLine(c, Offset(cx + 16f, cy), Offset(cx + 88f, cy), 1.2f)
    drawLine(c, Offset(cx, cy - 88f), Offset(cx, cy - 16f), 1.2f)
    drawLine(c, Offset(cx, cy + 16f), Offset(cx, cy + 88f), 1.2f)
    val pad = 22f; val len = 48f
    drawLine(c, Offset(pad, pad), Offset(pad + len, pad), 2f); drawLine(c, Offset(pad, pad), Offset(pad, pad + len), 2f)
    drawLine(c, Offset(size.width-pad,pad), Offset(size.width-pad-len,pad), 2f); drawLine(c, Offset(size.width-pad,pad), Offset(size.width-pad,pad+len), 2f)
}

private fun DrawScope.drawMinosReticle(colors: ThemePalette) {
    val cx = size.width / 2f; val cy = size.height / 2f; val c = colors.primary.copy(alpha = 0.52f)
    drawCircle(c, 112f, Offset(cx, cy), style = Stroke(1f))
    drawCircle(c, 10f, Offset(cx, cy), style = Stroke(1f))
    drawLine(c, Offset(cx - 138f, cy), Offset(cx - 16f, cy), 1f)
    drawLine(c, Offset(cx + 16f, cy), Offset(cx + 138f, cy), 1f)
    drawLine(c, Offset(cx, cy - 138f), Offset(cx, cy - 16f), 1f)
    drawLine(c, Offset(cx, cy + 16f), Offset(cx, cy + 138f), 1f)
    for (r in listOf(42f, 76f)) drawCircle(c.copy(alpha = 0.22f), r, Offset(cx, cy), style = Stroke(1f))
}

private fun DrawScope.drawTrails(theme: UiTheme, colors: ThemePalette, trails: Map<Int, List<MotionTrailPoint>>, frame: DetectionFrame, lockedId: Int?) {
    val now = SystemClock.elapsedRealtime()
    trails.forEach { (id, points) ->
        if (points.size < 2) return@forEach
        val tc = if (id == lockedId) colors.accent else colors.primary
        for (i in 1 until points.size) {
            val a = points[i-1]; val b = points[i]; val age = now - b.at
            if (age > TRAIL_MAX_AGE_MS) continue
            val alpha = (1f - age.toFloat()/TRAIL_MAX_AGE_MS).coerceIn(0.04f, if (theme == UiTheme.FUTURE) 0.62f else 0.38f)
            val s = mapNormalizedPoint(a.x,a.y,size.width,size.height,frame.imageWidth,frame.imageHeight)
            val e = mapNormalizedPoint(b.x,b.y,size.width,size.height,frame.imageWidth,frame.imageHeight)
            drawLine(tc.copy(alpha=alpha),s,e,if(theme==UiTheme.FUTURE)2f else 1f)
            if(theme==UiTheme.MINOS && i%5==0) drawCircle(tc.copy(alpha=alpha),2f,e)
        }
    }
}

private fun DrawScope.drawRadar(theme: UiTheme, colors: ThemePalette, targets: List<DetectionTarget>, lockedId: Int?) {
    val radius = when(theme){UiTheme.STANDARD->44f;UiTheme.FUTURE->64f;UiTheme.MINOS->52f}
    val center = Offset(size.width-(radius+20f), size.height-(if(theme==UiTheme.MINOS)110f else 100f))
    drawCircle(Color.Black.copy(alpha=0.48f),radius+8f,center)
    drawCircle(colors.primary.copy(alpha=0.62f),radius,center,style=Stroke(1.2f))
    drawCircle(colors.primary.copy(alpha=0.24f),radius/2f,center,style=Stroke(1f))
    drawLine(colors.primary.copy(alpha=0.25f),Offset(center.x-radius,center.y),Offset(center.x+radius,center.y),1f)
    drawLine(colors.primary.copy(alpha=0.25f),Offset(center.x,center.y-radius),Offset(center.x,center.y+radius),1f)
    if(theme==UiTheme.MINOS) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=colors.primary.toArgb();textSize=15f;typeface=Typeface.MONOSPACE}
        drawContext.canvas.nativeCanvas.drawText("RADAR",center.x-radius,center.y-radius-7f,p)
    }
    targets.forEach{t->
        val x=center.x+(t.normalizedBox.centerX()-0.5f)*radius*1.6f
        val y=center.y+(t.normalizedBox.centerY()-0.5f)*radius*1.6f
        drawCircle(if(t.trackingId==lockedId)colors.accent else statusColor(t.status,colors),if(t.trackingId==lockedId)4f else 2.5f,Offset(x,y))
    }
}

private fun updateMotionTrails(current: Map<Int, List<MotionTrailPoint>>, frame: DetectionFrame): Map<Int, List<MotionTrailPoint>> {
    val now = SystemClock.elapsedRealtime()
    val next = current.mapValues { (_, p) -> p.filter { now - it.at <= TRAIL_MAX_AGE_MS } }.filterValues { it.isNotEmpty() }.toMutableMap()
    frame.targets.forEach { target ->
        if (target.status == TrackStatus.LOST) return@forEach
        if (targetSpeed(target) < 0.006f && next[target.trackingId].isNullOrEmpty()) return@forEach
        val point = MotionTrailPoint(target.normalizedBox.centerX(), target.normalizedBox.centerY(), now)
        val old = next[target.trackingId].orEmpty(); val last = old.lastOrNull()
        if (last == null || hypot(point.x-last.x, point.y-last.y) >= 0.0045f) next[target.trackingId]=(old+point).takeLast(TRAIL_MAX_POINTS)
    }
    return next
}

private fun statusColor(status: TrackStatus?, colors: ThemePalette): Color = when(status){
    TrackStatus.PREDICTED -> colors.accent.copy(alpha=0.86f)
    TrackStatus.LOST -> colors.danger
    TrackStatus.ACQUIRING -> colors.dim
    TrackStatus.TRACKING -> colors.primary
    null -> colors.primary
}

private fun targetSpeed(target: DetectionTarget): Float = hypot(target.velocityX,target.velocityY)
private fun fastestMovingTarget(targets: List<DetectionTarget>): DetectionTarget? = targets.filter{it.status!=TrackStatus.LOST}.maxByOrNull(::targetSpeed)?.takeIf{targetSpeed(it)>0.015f}
private fun nearestTargetToCenter(targets: List<DetectionTarget>): DetectionTarget? = targets.minByOrNull{hypot(it.normalizedBox.centerX()-0.5f,it.normalizedBox.centerY()-0.5f)}

private fun mapNormalizedPoint(x:Float,y:Float,viewWidth:Float,viewHeight:Float,imageWidth:Int,imageHeight:Int):Offset{
    if(imageWidth<=0||imageHeight<=0)return Offset.Zero
    val scale=max(viewWidth/imageWidth.toFloat(),viewHeight/imageHeight.toFloat());val dw=imageWidth*scale;val dh=imageHeight*scale
    return Offset((viewWidth-dw)/2f+x*dw,(viewHeight-dh)/2f+y*dh)
}

private fun mapTargetRect(normalizedBox:RectF,viewWidth:Float,viewHeight:Float,imageWidth:Int,imageHeight:Int):RectF{
    if(imageWidth<=0||imageHeight<=0)return RectF()
    val scale=max(viewWidth/imageWidth.toFloat(),viewHeight/imageHeight.toFloat());val dw=imageWidth*scale;val dh=imageHeight*scale;val ox=(viewWidth-dw)/2f;val oy=(viewHeight-dh)/2f
    return RectF(ox+normalizedBox.left*dw,oy+normalizedBox.top*dh,ox+normalizedBox.right*dw,oy+normalizedBox.bottom*dh)
}

private fun cropTarget(source:Bitmap,target:DetectionTarget,frame:DetectionFrame):Bitmap?{
    val mapped=mapTargetRect(target.normalizedBox,source.width.toFloat(),source.height.toFloat(),frame.imageWidth,frame.imageHeight)
    val mx=mapped.width()*0.16f;val my=mapped.height()*0.16f
    val l=(mapped.left-mx).toInt().coerceIn(0,source.width-1);val t=(mapped.top-my).toInt().coerceIn(0,source.height-1)
    val r=(mapped.right+mx).toInt().coerceIn(l+1,source.width);val b=(mapped.bottom+my).toInt().coerceIn(t+1,source.height)
    return runCatching{Bitmap.createBitmap(source,l,t,r-l,b-t)}.getOrNull()
}
