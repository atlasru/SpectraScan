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
import android.view.Surface
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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.pow

class ScannerActivity071 : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App071() } }
    }
}

private enum class UiTheme071(val title: String) {
    STANDARD("STANDARD"), FUTURE("FUTURE"), MINOS("MINOS");
    fun next(): UiTheme071 = entries[(ordinal + 1) % entries.size]
}

private data class Palette071(val primary: Color, val dim: Color, val accent: Color, val danger: Color)
private fun palette071(theme: UiTheme071) = when (theme) {
    UiTheme071.STANDARD -> Palette071(Color(0xFF61FFB2), Color(0xFFB8CFC2), Color(0xFFFFD64A), Color(0xFFFF5353))
    UiTheme071.FUTURE -> Palette071(Color(0xFF48F7E5), Color(0xFF76C7D2), Color(0xFFFFC857), Color(0xFFFF5D73))
    UiTheme071.MINOS -> Palette071(Color(0xFF24E68D), Color(0xFF65A982), Color(0xFFE9B83D), Color(0xFFFF694A))
}

private const val TRAIL_AGE_071 = 4_500L
private const val TRAIL_POINTS_071 = 54
private data class Trail071(val x: Float, val y: Float, val at: Long)

@Composable
private fun App071() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) Scanner071() else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { Text("Разреши доступ к камере", color = Color.White) }
}

@Composable
private fun Scanner071() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("spectrascan_ui", Context.MODE_PRIVATE) }
    var theme by remember {
        mutableStateOf(runCatching { UiTheme071.valueOf(prefs.getString("theme071", UiTheme071.STANDARD.name)!!) }.getOrDefault(UiTheme071.STANDARD))
    }
    val colors = palette071(theme)

    var profile by remember { mutableStateOf(TrackingProfile.BALANCED) }
    var filter by remember { mutableStateOf(TargetFilter.ALL) }
    var frame by remember { mutableStateOf(DetectionFrame()) }
    var lockedId by remember { mutableStateOf<Int?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var zoomBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var trailsEnabled by remember { mutableStateOf(true) }
    var trails by remember { mutableStateOf<Map<Int, List<Trail071>>>(emptyMap()) }
    var zoom by remember { mutableStateOf(1f) }
    var minZoom by remember { mutableStateOf(1f) }
    var maxZoom by remember { mutableStateOf(1f) }
    var exposure by remember { mutableStateOf(0) }
    var monochrome by remember { mutableStateOf(false) }
    var gain by remember { mutableStateOf(1f) }
    var autoNv by remember { mutableStateOf(true) }
    var motionDetectionEnabled by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    val latestFrame by rememberUpdatedState(frame)
    val locked = frame.targets.firstOrNull { it.trackingId == lockedId }
    val nightVision = autoNv && frame.nightVisionSuggested
    val globalStatus = when {
        lockedId != null && locked == null -> "TARGET LOST"
        locked != null -> locked.status.name
        frame.targets.isEmpty() -> "ACQUIRING"
        else -> "TRACKING"
    }

    LaunchedEffect(lockedId, previewView) {
        while (lockedId != null) {
            val f = latestFrame
            val target = f.targets.firstOrNull { it.trackingId == lockedId }
            val source = previewView?.bitmap
            if (target != null && source != null) crop071(source, target, f)?.let { zoomBitmap = it.asImageBitmap() }
            delay(if (theme == UiTheme071.MINOS) 130 else 180)
        }
        zoomBitmap = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Camera071(
            modifier = Modifier.fillMaxSize(), profile = profile, filter = filter, zoom = zoom,
            exposure = exposure, monochrome = monochrome, gain = gain, nightVision = nightVision,
            motionDetectionEnabled = motionDetectionEnabled,
            onZoomRange = { min, max -> minZoom = min; maxZoom = max; zoom = zoom.coerceIn(min, max) },
            onPreview = { previewView = it },
            onFrame = { next ->
                frame = if (next.detectionThrottled && next.targets.isEmpty()) next.copy(targets = frame.targets) else next
                trails = updateTrails071(trails, frame)
            }
        )

        Hud071(
            frame = frame, lockedId = lockedId, theme = theme, colors = colors,
            trails = if (trailsEnabled) trails else emptyMap(),
            minZoom = minZoom, maxZoom = maxZoom,
            onTargetTap = { id -> lockedId = if (lockedId == id) null else id },
            onZoomGesture = { factor -> zoom = (zoom * factor).coerceIn(minZoom, maxZoom) }
        )

        TopHud071(
            modifier = when (theme) {
                UiTheme071.MINOS -> Modifier.align(Alignment.TopStart).padding(top = 24.dp, start = 8.dp)
                else -> Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 14.dp, start = 16.dp, end = 16.dp)
            },
            theme = theme, colors = colors, frame = frame, profile = profile, globalStatus = globalStatus,
            zoom = zoom, locked = locked, motionDetectionEnabled = motionDetectionEnabled, nightVision = nightVision
        )

        if (lockedId != null) TargetPanel071(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = if (theme == UiTheme071.MINOS) 18.dp else 96.dp, end = 14.dp),
            theme = theme, colors = colors, bitmap = zoomBitmap, target = locked
        ) { lockedId = null }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(if (theme == UiTheme071.FUTURE) 5.dp else 0.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Quick071("LOCK", frame.targets.isNotEmpty(), theme = theme, colors = colors) { lockedId = nearest071(frame.targets)?.trackingId }
            Quick071(if (theme == UiTheme071.MINOS) "MOTION" else "M-LOCK", frame.targets.any { speed071(it) > .015f }, theme = theme, colors = colors) { lockedId = fastest071(frame.targets)?.trackingId }
            Quick071("F:${filter.title}", true, selected = true, theme = theme, colors = colors) { lockedId = null; trails = emptyMap(); filter = filter.next() }
            Quick071(fmtZoom071(zoom), true, selected = zoom != 1f, theme = theme, colors = colors) { zoom = 1f.coerceIn(minZoom, maxZoom) }
            Quick071(if (theme == UiTheme071.MINOS) "MENU" else "SET", true, selected = settingsOpen, theme = theme, colors = colors) { settingsOpen = !settingsOpen }
        }

        if (settingsOpen) Settings071(
            theme = theme, colors = colors,
            zoom = zoom, minZoom = minZoom, maxZoom = maxZoom,
            exposure = exposure, gain = gain, monochrome = monochrome, autoNv = autoNv,
            profile = profile, trailsEnabled = trailsEnabled, trailsPresent = trails.isNotEmpty(),
            motionDetectionEnabled = motionDetectionEnabled,
            onTheme = {
                val next = theme.next(); theme = next; prefs.edit().putString("theme071", next.name).apply()
            },
            onClose = { settingsOpen = false }, onExposure = { exposure = it }, onGain = { gain = it },
            onMonochrome = { monochrome = !monochrome }, onAutoNv = { autoNv = !autoNv }, onProfile = { profile = profile.next() },
            onMotionDetection = { motionDetectionEnabled = !motionDetectionEnabled; lockedId = null; trails = emptyMap() },
            onTrails = { trailsEnabled = !trailsEnabled; if (!trailsEnabled) trails = emptyMap() }, onClear = { trails = emptyMap() }
        )
    }
}

@Composable
private fun TopHud071(
    modifier: Modifier, theme: UiTheme071, colors: Palette071, frame: DetectionFrame, profile: TrackingProfile,
    globalStatus: String, zoom: Float, locked: DetectionTarget?, motionDetectionEnabled: Boolean, nightVision: Boolean
) {
    when (theme) {
        UiTheme071.STANDARD -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SPECTRASCAN ${BuildConfig.VERSION_NAME}", color = colors.primary, fontSize = 15.sp)
            Text("YOLO ${frame.inferenceFps.toString().padStart(2, '0')} FPS  ${frame.inferenceMs} MS  TGT ${frame.targets.size.toString().padStart(2, '0')}  ${fmtZoom071(zoom)}", color = colors.primary.copy(alpha = .88f), fontSize = 10.sp)
            Text("$globalStatus // ${frame.targetFilter.title} // ${profile.title} // LUMA ${frame.meanLuma.toInt()}", color = status071(locked?.status, colors), fontSize = 10.sp)
            if (motionDetectionEnabled) Text("MOTION DETECTION ON", color = colors.primary.copy(alpha = .72f), fontSize = 9.sp)
            if (frame.lowLight) Text(if (nightVision) "LOW LIGHT // AUTO NIGHT VISION" else "LOW LIGHT", color = colors.accent, fontSize = 11.sp)
            if (zoom > 10f) Text("HIGH ZOOM // DETECTION MAY DEGRADE", color = colors.accent, fontSize = 9.sp)
        }
        UiTheme071.FUTURE -> Column(
            modifier.background(Color.Black.copy(alpha = .32f)).border(1.dp, colors.primary.copy(alpha = .30f)).padding(horizontal = 14.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("// SPECTRASCAN ${BuildConfig.VERSION_NAME} //", color = colors.primary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text("AI ${frame.inferenceFps}FPS | ${frame.inferenceMs}MS | TGT ${frame.targets.size} | ${fmtZoom071(zoom)}", color = colors.dim, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
            Text("$globalStatus :: ${frame.targetFilter.title} :: ${profile.title}", color = status071(locked?.status, colors), fontFamily = FontFamily.Monospace, fontSize = 8.sp)
        }
        UiTheme071.MINOS -> Column(
            modifier.width(245.dp).background(Color.Black.copy(alpha = .48f)).border(1.dp, colors.primary.copy(alpha = .55f)).padding(5.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            MinosText071("SPECTRASCAN // EXPERIMENTAL SENSOR SUITE", colors.primary, 8)
            MinosText071("SCAN MODE:${frame.targetFilter.title}  RENDER:LIVE RGB", colors.primary, 7)
            MinosText071("CAM ZOOM:${fmtZoom071(zoom)}  LOCK:${locked?.trackingId ?: "--"}", colors.dim, 7)
            MinosText071("AI:YOLO11  RATE:${frame.inferenceFps}FPS  LAT:${frame.inferenceMs}MS", colors.dim, 7)
            MinosText071("STATUS:$globalStatus  PROFILE:${profile.title}", colors.dim, 7)
            MinosText071("TARGETS:${frame.targets.size}  LUMA:${frame.meanLuma.toInt()}  ISP:ON", colors.dim, 7)
            MinosText071("HEAT:OFF  MOTION:${if (motionDetectionEnabled) "ON" else "OFF"}  NV:${if (nightVision) "ON" else "OFF"}", colors.dim, 7)
        }
    }
}

@Composable
private fun MinosText071(text: String, color: Color, size: Int) {
    Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = size.sp)
}

@Composable
private fun Settings071(
    theme: UiTheme071, colors: Palette071,
    zoom: Float, minZoom: Float, maxZoom: Float, exposure: Int, gain: Float,
    monochrome: Boolean, autoNv: Boolean, profile: TrackingProfile,
    trailsEnabled: Boolean, trailsPresent: Boolean, motionDetectionEnabled: Boolean,
    onTheme: () -> Unit, onClose: () -> Unit, onExposure: (Int) -> Unit, onGain: (Float) -> Unit,
    onMonochrome: () -> Unit, onAutoNv: () -> Unit, onProfile: () -> Unit,
    onMotionDetection: () -> Unit, onTrails: () -> Unit, onClear: () -> Unit
) {
    val square = theme == UiTheme071.MINOS
    Box(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(226.dp).background(Color.Black.copy(alpha = .90f)).border(1.dp, colors.primary.copy(alpha = .65f))) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 96.dp, start = 12.dp, end = 12.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(if (square) "SYSTEM // CONTROL" else "SETTINGS", color = colors.primary, fontFamily = if (square) FontFamily.Monospace else FontFamily.Default, fontSize = 15.sp)
            Chip071("THEME ${theme.title}", true, selected = true, theme = theme, colors = colors, onClick = onTheme)
            Text("ZOOM ${fmtZoom071(zoom)}", color = Color.White, fontSize = 11.sp)
            Text("RANGE ${fmtZoom071(minZoom)} — ${fmtZoom071(maxZoom)}", color = Color.White.copy(alpha = .55f), fontSize = 9.sp)
            Text("Pinch with two fingers", color = Color.White.copy(alpha = .55f), fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip071("EV -", true, theme = theme, colors = colors) { onExposure((exposure - 1).coerceAtLeast(-6)) }
                Chip071("EV ${if (exposure >= 0) "+" else ""}$exposure", true, theme = theme, colors = colors) { onExposure(0) }
                Chip071("EV +", true, theme = theme, colors = colors) { onExposure((exposure + 1).coerceAtMost(6)) }
            }
            Chip071("GAIN ${String.format(Locale.US, "%.1f", gain)}x", true, gain > 1.01f, theme, colors) { onGain(when { gain < 1.2f -> 1.35f; gain < 1.5f -> 1.70f; gain < 2f -> 2.20f; else -> 1f }) }
            Chip071(if (monochrome) "B/W ON" else "B/W OFF", true, monochrome, theme, colors, onMonochrome)
            Chip071(if (autoNv) "AUTO NIGHT VISION ON" else "AUTO NIGHT VISION OFF", true, autoNv, theme, colors, onAutoNv)
            Text("PERFORMANCE", color = colors.primary.copy(alpha=.72f), fontSize = 8.sp)
            Chip071(profile.title, true, profile != TrackingProfile.BALANCED, theme, colors, onProfile)
            Text(when(profile){TrackingProfile.SMOOTH->"LOW POWER // LONG YOLO IDLE";TrackingProfile.RESPONSIVE->"MAX YOLO // MINIMUM LATENCY";else->"ADAPTIVE YOLO // HYBRID TRACK"}, color = Color.White.copy(alpha=.48f), fontSize=8.sp)
            Chip071(if (motionDetectionEnabled) "MOTION DETECTION ON" else "MOTION DETECTION OFF", true, motionDetectionEnabled, theme, colors, onMotionDetection)
            Chip071(if (trailsEnabled) "MOTION TRAIL ON" else "MOTION TRAIL OFF", true, trailsEnabled, theme, colors, onTrails)
            Chip071("CLEAR TRAILS", trailsPresent, theme = theme, colors = colors, onClick = onClear)
            Text("HUD: ${theme.title}", color = colors.primary.copy(alpha = .7f), fontSize = 8.sp)
            Text("ISP: SHARPEN + DNR + STABILIZATION", color = colors.primary.copy(alpha = .7f), fontSize = 8.sp)
        }
        val shape = RoundedCornerShape(if (square) 0.dp else 10.dp)
        Box(modifier = Modifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 12.dp).border(1.dp, colors.primary, shape).background(Color.Black.copy(alpha = .92f), shape).clickable(onClick = onClose).padding(horizontal = 18.dp, vertical = 12.dp)) { Text("CLOSE", color = colors.primary, fontSize = 11.sp) }
    }
}

@Composable
private fun Quick071(text: String, enabled: Boolean, selected: Boolean = false, theme: UiTheme071, colors: Palette071, onClick: () -> Unit) {
    val c = when { !enabled -> Color.Gray; selected -> colors.primary; else -> Color.White.copy(alpha = .82f) }
    val radius = when (theme) { UiTheme071.STANDARD -> 8.dp; UiTheme071.FUTURE -> 2.dp; UiTheme071.MINOS -> 0.dp }
    val bg = if (theme == UiTheme071.FUTURE && selected) colors.primary.copy(alpha = .10f) else Color.Black.copy(alpha = .75f)
    Box(Modifier.border(1.dp, c, RoundedCornerShape(radius)).background(bg, RoundedCornerShape(radius)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 7.dp, vertical = 9.dp)) {
        Text(text, color = c, fontFamily = if (theme == UiTheme071.MINOS) FontFamily.Monospace else FontFamily.Default, fontSize = 8.sp)
    }
}

@Composable
private fun Chip071(text: String, enabled: Boolean, selected: Boolean = false, theme: UiTheme071, colors: Palette071, onClick: () -> Unit) {
    val c = when { !enabled -> Color.Gray; selected -> colors.primary; else -> Color.White.copy(alpha = .82f) }
    val radius = when (theme) { UiTheme071.STANDARD -> 8.dp; UiTheme071.FUTURE -> 2.dp; UiTheme071.MINOS -> 0.dp }
    Box(Modifier.border(1.dp, c, RoundedCornerShape(radius)).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(radius)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp)) {
        Text(text, color = c, fontFamily = if (theme == UiTheme071.MINOS) FontFamily.Monospace else FontFamily.Default, fontSize = 9.sp)
    }
}

@Composable
private fun Camera071(
    modifier: Modifier, profile: TrackingProfile, filter: TargetFilter, zoom: Float,
    exposure: Int, monochrome: Boolean, gain: Float, nightVision: Boolean,
    motionDetectionEnabled: Boolean,
    onZoomRange: (Float, Float) -> Unit, onPreview: (PreviewView) -> Unit,
    onFrame: (DetectionFrame) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestFrame by rememberUpdatedState(onFrame)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember { ObjectTrackingAnalyzer(mainExecutor) { latestFrame(it) } }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(profile) { analyzer.setProfile(profile) }
    LaunchedEffect(filter) { analyzer.setTargetFilter(filter) }
    LaunchedEffect(gain) { analyzer.setDigitalGain(gain) }
    LaunchedEffect(motionDetectionEnabled) { analyzer.setMotionDetectionEnabled(motionDetectionEnabled) }

    LaunchedEffect(camera, zoom) {
        val c = camera ?: return@LaunchedEffect
        val state = c.cameraInfo.zoomState.value
        val min = state?.minZoomRatio ?: 1f; val max = state?.maxZoomRatio ?: 1f
        onZoomRange(min, max); c.cameraControl.setZoomRatio(zoom.coerceIn(min, max))
    }

    LaunchedEffect(camera, exposure) {
        val c = camera ?: return@LaunchedEffect
        val state = c.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) { val r = state.exposureCompensationRange; c.cameraControl.setExposureCompensationIndex(exposure.coerceIn(r.lower, r.upper)) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            var previewUseCase: Preview? = null; var analysisUseCase: ImageAnalysis? = null
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE; onPreview(this)
                fun syncTargetRotation() {
                    val currentRotation = display?.rotation ?: Surface.ROTATION_0
                    if (previewUseCase?.targetRotation != currentRotation) previewUseCase?.targetRotation = currentRotation
                    if (analysisUseCase?.targetRotation != currentRotation) analysisUseCase?.targetRotation = currentRotation
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncTargetRotation() }
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val p = future.get(); provider = p
                    val selectedInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).first()
                    val targetRotation = display?.rotation ?: Surface.ROTATION_0
                    val pb = Preview.Builder().setTargetRotation(targetRotation)
                    CameraEnhancements.configurePreview(pb, selectedInfo, sharpen = true, denoise = true, stabilization = true)
                    val preview = pb.build().also { it.setSurfaceProvider(surfaceProvider) }; previewUseCase = preview
                    val ab = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480)).setTargetRotation(targetRotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    CameraEnhancements.configureAnalysis(ab, sharpen = true, denoise = true)
                    val analysis = ab.build().also { it.setAnalyzer(analysisExecutor, analyzer) }; analysisUseCase = analysis
                    p.unbindAll(); camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis); syncTargetRotation()
                    camera?.cameraInfo?.zoomState?.value?.let { onZoomRange(it.minZoomRatio, it.maxZoomRatio) }
                }, mainExecutor)
            }
        }, update = { applyFilter071(it, monochrome, gain, nightVision) }
    )
    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); analyzer.close(); analysisExecutor.shutdown() } }
}

private fun applyFilter071(preview: PreviewView, monochrome: Boolean, gain: Float, nightVision: Boolean) {
    val g = gain.coerceIn(1f, 2.4f)
    val matrix = when {
        nightVision -> ColorMatrix(floatArrayOf(.04f*g,.08f*g,.02f*g,0f,0f, .30f*g,.88f*g,.18f*g,0f,4f, .03f*g,.10f*g,.03f*g,0f,0f, 0f,0f,0f,1f,0f))
        monochrome -> ColorMatrix(floatArrayOf(.299f*g,.587f*g,.114f*g,0f,0f, .299f*g,.587f*g,.114f*g,0f,0f, .299f*g,.587f*g,.114f*g,0f,0f, 0f,0f,0f,1f,0f))
        else -> ColorMatrix(floatArrayOf(g,0f,0f,0f,0f, 0f,g,0f,0f,0f, 0f,0f,g,0f,0f, 0f,0f,0f,1f,0f))
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    preview.setLayerType(View.LAYER_TYPE_HARDWARE, p); if (preview.childCount > 0) preview.getChildAt(0)?.setLayerType(View.LAYER_TYPE_HARDWARE, p)
}

@Composable
private fun Hud071(
    frame: DetectionFrame, lockedId: Int?, theme: UiTheme071, colors: Palette071,
    trails: Map<Int, List<Trail071>>, minZoom: Float, maxZoom: Float,
    onTargetTap: (Int) -> Unit, onZoomGesture: (Float) -> Unit
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 20f } }
    val latestZoomHandler by rememberUpdatedState(onZoomGesture)
    Canvas(Modifier.fillMaxSize().pointerInput(frame.targets, frame.imageWidth, frame.imageHeight, lockedId) {
        detectTapGestures { tap -> frame.targets.asReversed().firstOrNull { rect071(it.normalizedBox, size.width.toFloat(), size.height.toFloat(), frame.imageWidth, frame.imageHeight).contains(tap.x, tap.y) }?.let { onTargetTap(it.trackingId) } }
    }.pointerInput(minZoom, maxZoom) {
        detectTransformGestures { _, _, zoomChange, _ -> if (zoomChange > 0f && zoomChange != 1f) latestZoomHandler(zoomChange.toDouble().pow(1.8).toFloat().coerceIn(.70f, 1.45f)) }
    }) {
        when (theme) {
            UiTheme071.STANDARD -> { drawTacticalFrame071(colors); drawReticleStandard071(colors) }
            UiTheme071.FUTURE -> { drawFutureFrame071(colors); drawReticleFuture071(colors) }
            UiTheme071.MINOS -> { drawMinosFrame071(colors); drawReticleMinos071(colors) }
        }
        drawTrails071(trails, frame, lockedId, colors)
        val cx = size.width/2f; val cy = size.height/2f
        frame.targets.forEach { t ->
            val r = rect071(t.normalizedBox, size.width, size.height, frame.imageWidth, frame.imageHeight)
            val isLocked = t.trackingId == lockedId
            val c = if (isLocked) colors.accent else status071(t.status, colors)
            when (theme) {
                UiTheme071.STANDARD -> drawFullBox071(r, c, t.status, isLocked)
                UiTheme071.FUTURE -> drawCornerBox071(r, c, t.status, isLocked)
                UiTheme071.MINOS -> drawMinosBox071(r, c, t.status, isLocked)
            }
            val center = Offset(r.centerX(), r.centerY())
            if (isLocked) {
                when (theme) {
                    UiTheme071.STANDARD -> drawLine(c.copy(alpha=.42f), Offset(cx,cy), center, 1.2f)
                    UiTheme071.FUTURE -> { drawLine(c.copy(alpha=.50f), Offset(cx,cy), center, 1f); drawCircle(c.copy(alpha=.70f), 12f, center, style = Stroke(1f)) }
                    UiTheme071.MINOS -> {
                        val anchor = Offset(size.width - 220f, 110f)
                        val elbow = Offset((center.x + anchor.x) * .55f, center.y)
                        drawLine(c.copy(alpha=.62f), center, elbow, 1f)
                        drawLine(c.copy(alpha=.62f), elbow, anchor, 1f)
                    }
                }
            }
            val pct = if(t.confidence>0f)" ${(t.confidence*100).toInt()}%" else ""
            paint.color = c.toArgb()
            paint.textSize = if (theme == UiTheme071.MINOS) 17f else 20f
            val label = when(theme) {
                UiTheme071.MINOS -> "YOLO // ${t.label}$pct // #${t.trackingId}"
                UiTheme071.FUTURE -> "${t.label}$pct :: #${t.trackingId} :: ${t.status.name}"
                UiTheme071.STANDARD -> "${t.label}$pct // #${t.trackingId}"
            }
            drawContext.canvas.nativeCanvas.drawText(label, r.left, (r.top-7f).coerceAtLeast(28f), paint)
        }
        drawRadar071(frame.targets, lockedId, theme, colors)
    }
}

private fun DrawScope.drawTacticalFrame071(colors: Palette071){
    val c=colors.primary.copy(alpha=.50f);val m=24f;val l=46f
    drawLine(c,Offset(m,m),Offset(m+l,m),1.4f);drawLine(c,Offset(m,m),Offset(m,m+l),1.4f)
    drawLine(c,Offset(size.width-m-l,m),Offset(size.width-m,m),1.4f);drawLine(c,Offset(size.width-m,m),Offset(size.width-m,m+l),1.4f)
    drawLine(c,Offset(m,size.height-m),Offset(m+l,size.height-m),1.4f);drawLine(c,Offset(m,size.height-m-l),Offset(m,size.height-m),1.4f)
    drawLine(c,Offset(size.width-m-l,size.height-m),Offset(size.width-m,size.height-m),1.4f);drawLine(c,Offset(size.width-m,size.height-m-l),Offset(size.width-m,size.height-m),1.4f)
    val y=size.height*.18f;drawLine(c.copy(alpha=.18f),Offset(size.width*.18f,y),Offset(size.width*.82f,y),1f,pathEffect=PathEffect.dashPathEffect(floatArrayOf(10f,14f)))
}

private fun DrawScope.drawFutureFrame071(colors: Palette071) {
    val c = colors.primary.copy(alpha=.48f); val m=20f; val l=70f
    drawLine(c,Offset(m,m),Offset(m+l,m),1f);drawLine(c,Offset(m,m),Offset(m,m+l),1f)
    drawLine(c,Offset(size.width-m-l,m),Offset(size.width-m,m),1f);drawLine(c,Offset(size.width-m,m),Offset(size.width-m,m+l),1f)
    drawLine(c,Offset(m,size.height-m),Offset(m+l,size.height-m),1f);drawLine(c,Offset(m,size.height-m-l),Offset(m,size.height-m),1f)
    drawLine(c,Offset(size.width-m-l,size.height-m),Offset(size.width-m,size.height-m),1f);drawLine(c,Offset(size.width-m,size.height-m-l),Offset(size.width-m,size.height-m),1f)
    drawCircle(c.copy(alpha=.16f), size.minDimension*.31f, center, style=Stroke(1f))
}

private fun DrawScope.drawMinosFrame071(colors: Palette071) {
    val c=colors.primary.copy(alpha=.55f); val m=22f; val l=38f
    drawLine(c,Offset(m,m),Offset(m+l,m),1f);drawLine(c,Offset(m,m),Offset(m,m+l),1f)
    drawLine(c,Offset(size.width-m-l,m),Offset(size.width-m,m),1f);drawLine(c,Offset(size.width-m,m),Offset(size.width-m,m+l),1f)
    drawLine(c,Offset(m,size.height-m),Offset(m+l,size.height-m),1f);drawLine(c,Offset(m,size.height-m-l),Offset(m,size.height-m),1f)
    drawLine(c,Offset(size.width-m-l,size.height-m),Offset(size.width-m,size.height-m),1f);drawLine(c,Offset(size.width-m,size.height-m-l),Offset(size.width-m,size.height-m),1f)
}

private fun DrawScope.drawFullBox071(r:RectF,c:Color,status:TrackStatus,locked:Boolean){
    val predicted=status==TrackStatus.PREDICTED||status==TrackStatus.LOST
    drawRect(color=if(predicted)c.copy(alpha=.72f) else c.copy(alpha=.92f),topLeft=Offset(r.left,r.top),size=Size(r.width(),r.height()),style=Stroke(if(locked)2.8f else 1.8f,pathEffect=if(predicted)PathEffect.dashPathEffect(floatArrayOf(12f,8f))else null))
}

private fun DrawScope.drawCornerBox071(r:RectF,c:Color,status:TrackStatus,locked:Boolean) {
    val predicted=status==TrackStatus.PREDICTED||status==TrackStatus.LOST
    val col=if(predicted)c.copy(alpha=.65f)else c.copy(alpha=.92f); val sw=if(locked)2.2f else 1.4f
    val x=max(12f,r.width()*.23f); val y=max(12f,r.height()*.23f); val pe=if(predicted)PathEffect.dashPathEffect(floatArrayOf(9f,7f))else null
    drawLine(col,Offset(r.left,r.top),Offset(r.left+x,r.top),sw,pathEffect=pe);drawLine(col,Offset(r.left,r.top),Offset(r.left,r.top+y),sw,pathEffect=pe)
    drawLine(col,Offset(r.right-x,r.top),Offset(r.right,r.top),sw,pathEffect=pe);drawLine(col,Offset(r.right,r.top),Offset(r.right,r.top+y),sw,pathEffect=pe)
    drawLine(col,Offset(r.left,r.bottom),Offset(r.left+x,r.bottom),sw,pathEffect=pe);drawLine(col,Offset(r.left,r.bottom-y),Offset(r.left,r.bottom),sw,pathEffect=pe)
    drawLine(col,Offset(r.right-x,r.bottom),Offset(r.right,r.bottom),sw,pathEffect=pe);drawLine(col,Offset(r.right,r.bottom-y),Offset(r.right,r.bottom),sw,pathEffect=pe)
}

private fun DrawScope.drawMinosBox071(r:RectF,c:Color,status:TrackStatus,locked:Boolean) {
    val predicted=status==TrackStatus.PREDICTED||status==TrackStatus.LOST
    drawRect(color=c.copy(alpha=if(predicted).64f else .88f),topLeft=Offset(r.left,r.top),size=Size(r.width(),r.height()),style=Stroke(if(locked)2f else 1.15f,pathEffect=if(predicted)PathEffect.dashPathEffect(floatArrayOf(8f,6f))else null))
    if(locked){drawCircle(c.copy(alpha=.65f),max(r.width(),r.height())*.62f,Offset(r.centerX(),r.centerY()),style=Stroke(1f))}
}

private fun DrawScope.drawReticleStandard071(colors: Palette071) { val cx=size.width/2f;val cy=size.height/2f;drawCircle(colors.primary.copy(alpha=.42f),60f,Offset(cx,cy),style=Stroke(1.2f));drawCircle(colors.primary.copy(alpha=.78f),5f,Offset(cx,cy),style=Stroke(1.2f));drawLine(colors.primary.copy(alpha=.72f),Offset(cx-95f,cy),Offset(cx-16f,cy),1.2f);drawLine(colors.primary.copy(alpha=.72f),Offset(cx+16f,cy),Offset(cx+95f,cy),1.2f);drawLine(colors.primary.copy(alpha=.72f),Offset(cx,cy-95f),Offset(cx,cy-16f),1.2f);drawLine(colors.primary.copy(alpha=.72f),Offset(cx,cy+16f),Offset(cx,cy+95f),1.2f) }
private fun DrawScope.drawReticleFuture071(colors: Palette071) { val c=colors.primary;val p=center;drawCircle(c.copy(alpha=.35f),74f,p,style=Stroke(1f));drawCircle(c.copy(alpha=.18f),120f,p,style=Stroke(1f));drawCircle(c.copy(alpha=.85f),4f,p,style=Stroke(1f));drawLine(c.copy(alpha=.68f),Offset(p.x-145f,p.y),Offset(p.x-18f,p.y),1f);drawLine(c.copy(alpha=.68f),Offset(p.x+18f,p.y),Offset(p.x+145f,p.y),1f);drawLine(c.copy(alpha=.68f),Offset(p.x,p.y-145f),Offset(p.x,p.y-18f),1f);drawLine(c.copy(alpha=.68f),Offset(p.x,p.y+18f),Offset(p.x,p.y+145f),1f) }
private fun DrawScope.drawReticleMinos071(colors: Palette071) { val c=colors.primary;val p=center;drawCircle(c.copy(alpha=.34f),105f,p,style=Stroke(1f));drawCircle(c.copy(alpha=.72f),7f,p,style=Stroke(1f));drawLine(c.copy(alpha=.55f),Offset(p.x-135f,p.y),Offset(p.x-12f,p.y),1f);drawLine(c.copy(alpha=.55f),Offset(p.x+12f,p.y),Offset(p.x+135f,p.y),1f);drawLine(c.copy(alpha=.55f),Offset(p.x,p.y-135f),Offset(p.x,p.y-12f),1f);drawLine(c.copy(alpha=.55f),Offset(p.x,p.y+12f),Offset(p.x,p.y+135f),1f) }

private fun DrawScope.drawTrails071(trails: Map<Int,List<Trail071>>, frame: DetectionFrame, lockedId: Int?, colors: Palette071) { val now=SystemClock.elapsedRealtime();trails.forEach { (id,pts)->if(pts.size<2)return@forEach;val c=if(id==lockedId)colors.accent else colors.primary;for(i in 1 until pts.size){val a=pts[i-1];val b=pts[i];val age=now-b.at;if(age>TRAIL_AGE_071)continue;val alpha=(1f-age.toFloat()/TRAIL_AGE_071).coerceIn(.08f,.60f);drawLine(c.copy(alpha=alpha),point071(a.x,a.y,size.width,size.height,frame.imageWidth,frame.imageHeight),point071(b.x,b.y,size.width,size.height,frame.imageWidth,frame.imageHeight),if(id==lockedId)3f else 1.5f)}} }

private fun DrawScope.drawRadar071(targets: List<DetectionTarget>, lockedId: Int?, theme: UiTheme071, colors: Palette071) {
    val radius=if(theme==UiTheme071.FUTURE)68f else 58f;val center=Offset(size.width-(radius+18f),size.height-(radius+72f))
    if(theme!=UiTheme071.MINOS)drawCircle(Color.Black.copy(alpha=.50f),radius+8f,center) else drawRect(Color.Black.copy(alpha=.48f),Offset(center.x-radius-7f,center.y-radius-7f),Size((radius+7f)*2,(radius+7f)*2))
    drawCircle(colors.primary.copy(alpha=.82f),radius,center,style=Stroke(if(theme==UiTheme071.MINOS)1f else 1.4f));drawCircle(colors.primary.copy(alpha=.30f),radius/2,center,style=Stroke(1f));drawLine(colors.primary.copy(alpha=.24f),Offset(center.x-radius,center.y),Offset(center.x+radius,center.y),1f);drawLine(colors.primary.copy(alpha=.24f),Offset(center.x,center.y-radius),Offset(center.x,center.y+radius),1f)
    if(theme==UiTheme071.FUTURE)drawCircle(colors.primary.copy(alpha=.16f),radius*.75f,center,style=Stroke(1f))
    targets.forEach{t->val x=center.x+(t.normalizedBox.centerX()-.5f)*radius*1.6f;val y=center.y+(t.normalizedBox.centerY()-.5f)*radius*1.6f;drawCircle(if(t.trackingId==lockedId)colors.accent else status071(t.status,colors),if(t.trackingId==lockedId)5f else 3.5f,Offset(x,y))}
}

@Composable
private fun TargetPanel071(modifier: Modifier, theme: UiTheme071, colors: Palette071, bitmap: ImageBitmap?, target: DetectionTarget?, onUnlock: () -> Unit) {
    val line=status071(target?.status,colors);val width=when(theme){UiTheme071.MINOS->205.dp;UiTheme071.FUTURE->176.dp;UiTheme071.STANDARD->170.dp};val imageH=when(theme){UiTheme071.MINOS->125.dp;UiTheme071.FUTURE->105.dp;UiTheme071.STANDARD->110.dp}
    val radius=when(theme){UiTheme071.STANDARD->6.dp;UiTheme071.FUTURE->2.dp;UiTheme071.MINOS->0.dp}
    Column(modifier.width(width).background(Color.Black.copy(alpha=.82f),RoundedCornerShape(radius)).border(1.dp,line,RoundedCornerShape(radius)).clickable(onClick=onUnlock).padding(5.dp)) {
        if(theme!=UiTheme071.STANDARD) MinosText071(if(theme==UiTheme071.MINOS)"LIVE RGB CROP // TARGET" else "TARGET INSPECTOR",line,8)
        Box(Modifier.fillMaxWidth().height(imageH).background(Color.Black),contentAlignment=Alignment.Center){if(bitmap!=null)Image(bitmap,"Locked target",Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Text("TARGET LOST",color=colors.danger,fontSize=10.sp)}
        when(theme){
            UiTheme071.MINOS->{MinosText071("ID:${target?.trackingId ?: "--"}  CLASS:${target?.label ?: "UNKNOWN"}",line,7);MinosText071("CONF:${target?.let{"${(it.confidence*100).toInt()}%"} ?: "--"}  STATE:${target?.status?.name ?: "LOST"}",colors.dim,7)}
            UiTheme071.FUTURE->MinosText071("${target?.label ?: "TARGET"} :: #${target?.trackingId ?: "--"} :: ${target?.status?.name ?: "LOST"}",line,7)
            UiTheme071.STANDARD->Text("${target?.label ?: "TARGET"} #${target?.trackingId ?: "--"}",color=line,fontSize=9.sp,modifier=Modifier.padding(top=4.dp))
        }
    }
}

private fun updateTrails071(current: Map<Int,List<Trail071>>, frame: DetectionFrame): Map<Int,List<Trail071>> { val now=SystemClock.elapsedRealtime();val next=current.mapValues{(_,p)->p.filter{now-it.at<=TRAIL_AGE_071}}.filterValues{it.isNotEmpty()}.toMutableMap();frame.targets.forEach{t->if(t.status==TrackStatus.LOST)return@forEach;if(speed071(t)<.006f&&next[t.trackingId].isNullOrEmpty())return@forEach;val p=Trail071(t.normalizedBox.centerX(),t.normalizedBox.centerY(),now);val old=next[t.trackingId].orEmpty();val last=old.lastOrNull();if(last==null||hypot(p.x-last.x,p.y-last.y)>=.0045f)next[t.trackingId]=(old+p).takeLast(TRAIL_POINTS_071)};return next }
private fun status071(s:TrackStatus?,colors:Palette071):Color=when(s){TrackStatus.PREDICTED->colors.accent.copy(alpha=.88f);TrackStatus.LOST->colors.danger;TrackStatus.ACQUIRING->Color(0xFF7CEBFF);else->colors.primary}
private fun speed071(t:DetectionTarget)=hypot(t.velocityX,t.velocityY)
private fun fastest071(ts:List<DetectionTarget>)=ts.filter{it.status!=TrackStatus.LOST}.maxByOrNull(::speed071)?.takeIf{speed071(it)>.015f}
private fun nearest071(ts:List<DetectionTarget>)=ts.minByOrNull{hypot(it.normalizedBox.centerX()-.5f,it.normalizedBox.centerY()-.5f)}
private fun fmtZoom071(z:Float)=if(z<10f)String.format(Locale.US,"%.1fx",z)else String.format(Locale.US,"%.0fx",z)
private fun point071(x:Float,y:Float,vw:Float,vh:Float,iw:Int,ih:Int):Offset{if(iw<=0||ih<=0)return Offset.Zero;val s=max(vw/iw.toFloat(),vh/ih.toFloat());val dw=iw*s;val dh=ih*s;return Offset((vw-dw)/2+x*dw,(vh-dh)/2+y*dh)}
private fun rect071(b:RectF,vw:Float,vh:Float,iw:Int,ih:Int):RectF{if(iw<=0||ih<=0)return RectF();val s=max(vw/iw.toFloat(),vh/ih.toFloat());val dw=iw*s;val dh=ih*s;val ox=(vw-dw)/2;val oy=(vh-dh)/2;return RectF(ox+b.left*dw,oy+b.top*dh,ox+b.right*dw,oy+b.bottom*dh)}
private fun crop071(source:Bitmap,target:DetectionTarget,frame:DetectionFrame):Bitmap?{val r=rect071(target.normalizedBox,source.width.toFloat(),source.height.toFloat(),frame.imageWidth,frame.imageHeight);val mx=r.width()*.16f;val my=r.height()*.16f;val l=(r.left-mx).toInt().coerceIn(0,source.width-1);val t=(r.top-my).toInt().coerceIn(0,source.height-1);val rr=(r.right+mx).toInt().coerceIn(l+1,source.width);val bb=(r.bottom+my).toInt().coerceIn(t+1,source.height);return runCatching{Bitmap.createBitmap(source,l,t,rr-l,bb-t)}.getOrNull()}
