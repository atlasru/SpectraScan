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

private val H071 = Color(0xFF61FFB2)
private const val TRAIL_AGE_071 = 4_500L
private const val TRAIL_POINTS_071 = 54
private data class Trail071(val x: Float, val y: Float, val at: Long)

@Composable
private fun App071() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) Scanner071() else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Разреши доступ к камере", color = Color.White)
    }
}

@Composable
private fun Scanner071() {
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
            delay(180)
        }
        zoomBitmap = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Camera071(
            modifier = Modifier.fillMaxSize(), profile = profile, filter = filter, zoom = zoom,
            exposure = exposure, monochrome = monochrome, gain = gain, nightVision = nightVision,
            motionDetectionEnabled = motionDetectionEnabled,
            onZoomRange = { min, max ->
                minZoom = min
                maxZoom = max
                zoom = zoom.coerceIn(min, max)
            },
            onPreview = { previewView = it },
            onFrame = { next ->
                frame = if (next.detectionThrottled && next.targets.isEmpty()) next.copy(targets = frame.targets) else next
                trails = updateTrails071(trails, frame)
            }
        )

        Hud071(
            frame = frame, lockedId = lockedId,
            trails = if (trailsEnabled) trails else emptyMap(),
            minZoom = minZoom, maxZoom = maxZoom,
            onTargetTap = { id -> lockedId = if (lockedId == id) null else id },
            onZoomGesture = { factor -> zoom = (zoom * factor).coerceIn(minZoom, maxZoom) }
        )

        Column(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 14.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SPECTRASCAN ${BuildConfig.VERSION_NAME}", color = H071, fontSize = 15.sp)
            Text(
                "YOLO ${frame.inferenceFps.toString().padStart(2, '0')} FPS  ${frame.inferenceMs} MS  " +
                    "TGT ${frame.targets.size.toString().padStart(2, '0')}  ${fmtZoom071(zoom)}",
                color = H071.copy(alpha = .88f), fontSize = 10.sp
            )
            Text("$globalStatus // ${frame.targetFilter.title} // LUMA ${frame.meanLuma.toInt()}", color = status071(locked?.status), fontSize = 10.sp)
            if (motionDetectionEnabled) Text("MOTION DETECTION ON", color = H071.copy(alpha = .72f), fontSize = 9.sp)
            if (frame.lowLight) Text(if (nightVision) "LOW LIGHT // AUTO NIGHT VISION" else "LOW LIGHT", color = Color(0xFFFFB347), fontSize = 11.sp)
            if (zoom > 10f) Text("HIGH ZOOM // DETECTION MAY DEGRADE", color = Color(0xFFFFB347), fontSize = 9.sp)
        }

        if (lockedId != null) TargetPanel071(
            Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 14.dp), zoomBitmap, locked
        ) { lockedId = null }

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Quick071("LOCK", frame.targets.isNotEmpty()) { lockedId = nearest071(frame.targets)?.trackingId }
            Quick071("M-LOCK", frame.targets.any { speed071(it) > .015f }) { lockedId = fastest071(frame.targets)?.trackingId }
            Quick071("F:${filter.title}", true, selected = true) {
                lockedId = null; trails = emptyMap(); filter = filter.next()
            }
            Quick071(fmtZoom071(zoom), true, selected = zoom != 1f) { zoom = 1f.coerceIn(minZoom, maxZoom) }
            Quick071("SET", true, selected = settingsOpen) { settingsOpen = !settingsOpen }
        }

        if (settingsOpen) Settings071(
            zoom = zoom, minZoom = minZoom, maxZoom = maxZoom,
            exposure = exposure, gain = gain, monochrome = monochrome, autoNv = autoNv,
            profile = profile, trailsEnabled = trailsEnabled, trailsPresent = trails.isNotEmpty(),
            motionDetectionEnabled = motionDetectionEnabled,
            onClose = { settingsOpen = false },
            onExposure = { exposure = it },
            onGain = { gain = it },
            onMonochrome = { monochrome = !monochrome },
            onAutoNv = { autoNv = !autoNv },
            onProfile = { profile = profile.next() },
            onMotionDetection = {
                motionDetectionEnabled = !motionDetectionEnabled
                lockedId = null
                trails = emptyMap()
            },
            onTrails = { trailsEnabled = !trailsEnabled; if (!trailsEnabled) trails = emptyMap() },
            onClear = { trails = emptyMap() }
        )
    }
}

@Composable
private fun Settings071(
    zoom: Float, minZoom: Float, maxZoom: Float, exposure: Int, gain: Float,
    monochrome: Boolean, autoNv: Boolean, profile: TrackingProfile,
    trailsEnabled: Boolean, trailsPresent: Boolean, motionDetectionEnabled: Boolean,
    onClose: () -> Unit, onExposure: (Int) -> Unit, onGain: (Float) -> Unit,
    onMonochrome: () -> Unit, onAutoNv: () -> Unit, onProfile: () -> Unit,
    onMotionDetection: () -> Unit, onTrails: () -> Unit, onClear: () -> Unit
) {
    Box(
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(226.dp)
            .background(Color.Black.copy(alpha = .90f)).border(1.dp, H071.copy(alpha = .65f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = 96.dp, start = 12.dp, end = 12.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("SETTINGS", color = H071, fontSize = 15.sp)
            Text("ZOOM ${fmtZoom071(zoom)}", color = Color.White, fontSize = 11.sp)
            Text("RANGE ${fmtZoom071(minZoom)} — ${fmtZoom071(maxZoom)}", color = Color.White.copy(alpha = .55f), fontSize = 9.sp)
            Text("Pinch with two fingers", color = Color.White.copy(alpha = .55f), fontSize = 9.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip071("EV -", true) { onExposure((exposure - 1).coerceAtLeast(-6)) }
                Chip071("EV ${if (exposure >= 0) "+" else ""}$exposure", true) { onExposure(0) }
                Chip071("EV +", true) { onExposure((exposure + 1).coerceAtMost(6)) }
            }
            Chip071("GAIN ${String.format(Locale.US, "%.1f", gain)}x", true, gain > 1.01f) {
                onGain(when { gain < 1.2f -> 1.35f; gain < 1.5f -> 1.70f; gain < 2f -> 2.20f; else -> 1f })
            }
            Chip071(if (monochrome) "B/W ON" else "B/W OFF", true, monochrome, onClick = onMonochrome)
            Chip071(if (autoNv) "AUTO NIGHT VISION ON" else "AUTO NIGHT VISION OFF", true, autoNv, onClick = onAutoNv)
            Chip071("PROFILE ${profile.title}", true, onClick = onProfile)
            Chip071(
                if (motionDetectionEnabled) "MOTION DETECTION ON" else "MOTION DETECTION OFF",
                true,
                motionDetectionEnabled,
                onClick = onMotionDetection
            )
            Chip071(if (trailsEnabled) "MOTION TRAIL ON" else "MOTION TRAIL OFF", true, trailsEnabled, onClick = onTrails)
            Chip071("CLEAR TRAILS", trailsPresent, onClick = onClear)
            Text("ISP: SHARPEN + DNR + STABILIZATION", color = H071.copy(alpha = .7f), fontSize = 8.sp)
        }
        Box(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 42.dp, end = 12.dp)
                .border(1.dp, H071, RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = .92f), RoundedCornerShape(10.dp))
                .clickable(onClick = onClose).padding(horizontal = 18.dp, vertical = 12.dp)
        ) { Text("CLOSE", color = H071, fontSize = 11.sp) }
    }
}

@Composable
private fun Quick071(text: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val c = when { !enabled -> Color.Gray; selected -> H071; else -> Color.White.copy(alpha = .82f) }
    Box(
        Modifier.border(1.dp, c, RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .75f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 7.dp, vertical = 9.dp)
    ) { Text(text, color = c, fontSize = 8.sp) }
}

@Composable
private fun Chip071(text: String, enabled: Boolean, selected: Boolean = false, onClick: () -> Unit) {
    val c = when { !enabled -> Color.Gray; selected -> H071; else -> Color.White.copy(alpha = .82f) }
    Box(
        Modifier.border(1.dp, c, RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 9.dp)
    ) { Text(text, color = c, fontSize = 9.sp) }
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
        val min = state?.minZoomRatio ?: 1f
        val max = state?.maxZoomRatio ?: 1f
        onZoomRange(min, max)
        c.cameraControl.setZoomRatio(zoom.coerceIn(min, max))
    }

    LaunchedEffect(camera, exposure) {
        val c = camera ?: return@LaunchedEffect
        val state = c.cameraInfo.exposureState
        if (state.isExposureCompensationSupported) {
            val r = state.exposureCompensationRange
            c.cameraControl.setExposureCompensationIndex(exposure.coerceIn(r.lower, r.upper))
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            var previewUseCase: Preview? = null
            var analysisUseCase: ImageAnalysis? = null
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                onPreview(this)

                fun syncTargetRotation() {
                    val currentRotation = display?.rotation ?: Surface.ROTATION_0
                    if (previewUseCase?.targetRotation != currentRotation) {
                        previewUseCase?.targetRotation = currentRotation
                    }
                    if (analysisUseCase?.targetRotation != currentRotation) {
                        analysisUseCase?.targetRotation = currentRotation
                    }
                }

                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncTargetRotation() }

                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    val p = future.get(); provider = p
                    val selectedInfo = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos).first()
                    val targetRotation = display?.rotation ?: Surface.ROTATION_0
                    val pb = Preview.Builder().setTargetRotation(targetRotation)
                    CameraEnhancements.configurePreview(pb, selectedInfo, sharpen = true, denoise = true, stabilization = true)
                    val preview = pb.build().also { it.setSurfaceProvider(surfaceProvider) }
                    previewUseCase = preview
                    val ab = ImageAnalysis.Builder().setTargetResolution(android.util.Size(640, 480))
                        .setTargetRotation(targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    CameraEnhancements.configureAnalysis(ab, sharpen = true, denoise = true)
                    val analysis = ab.build().also { it.setAnalyzer(analysisExecutor, analyzer) }
                    analysisUseCase = analysis
                    p.unbindAll()
                    camera = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    syncTargetRotation()
                    camera?.cameraInfo?.zoomState?.value?.let { onZoomRange(it.minZoomRatio, it.maxZoomRatio) }
                }, mainExecutor)
            }
        },
        update = { applyFilter071(it, monochrome, gain, nightVision) }
    )
    DisposableEffect(Unit) { onDispose { provider?.unbindAll(); analyzer.close(); analysisExecutor.shutdown() } }
}

private fun applyFilter071(preview: PreviewView, monochrome: Boolean, gain: Float, nightVision: Boolean) {
    val g = gain.coerceIn(1f, 2.4f)
    val matrix = when {
        nightVision -> ColorMatrix(floatArrayOf(
            .04f*g,.08f*g,.02f*g,0f,0f, .30f*g,.88f*g,.18f*g,0f,4f,
            .03f*g,.10f*g,.03f*g,0f,0f, 0f,0f,0f,1f,0f))
        monochrome -> ColorMatrix(floatArrayOf(
            .299f*g,.587f*g,.114f*g,0f,0f, .299f*g,.587f*g,.114f*g,0f,0f,
            .299f*g,.587f*g,.114f*g,0f,0f, 0f,0f,0f,1f,0f))
        else -> ColorMatrix(floatArrayOf(g,0f,0f,0f,0f, 0f,g,0f,0f,0f, 0f,0f,g,0f,0f, 0f,0f,0f,1f,0f))
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
    preview.setLayerType(View.LAYER_TYPE_HARDWARE, p)
    if (preview.childCount > 0) preview.getChildAt(0)?.setLayerType(View.LAYER_TYPE_HARDWARE, p)
}

@Composable
private fun Hud071(
    frame: DetectionFrame, lockedId: Int?, trails: Map<Int, List<Trail071>>,
    minZoom: Float, maxZoom: Float, onTargetTap: (Int) -> Unit, onZoomGesture: (Float) -> Unit
) {
    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = 22f } }
    val latestZoomHandler by rememberUpdatedState(onZoomGesture)
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(frame.targets, frame.imageWidth, frame.imageHeight, lockedId) {
                detectTapGestures { tap ->
                    frame.targets.asReversed().firstOrNull {
                        rect071(it.normalizedBox, size.width.toFloat(), size.height.toFloat(), frame.imageWidth, frame.imageHeight).contains(tap.x, tap.y)
                    }?.let { onTargetTap(it.trackingId) }
                }
            }
            .pointerInput(minZoom, maxZoom) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange > 0f && zoomChange != 1f) {
                        val accelerated = zoomChange.toDouble().pow(1.8).toFloat().coerceIn(.70f, 1.45f)
                        latestZoomHandler(accelerated)
                    }
                }
            }
    ) {
        drawTrails071(trails, frame, lockedId)
        drawReticle071()
        val cx = size.width/2f; val cy = size.height/2f
        frame.targets.forEach { t ->
            val r = rect071(t.normalizedBox, size.width, size.height, frame.imageWidth, frame.imageHeight)
            val isLocked = t.trackingId == lockedId
            val c = if (isLocked) Color(0xFFFFD64A) else status071(t.status)
            val dashed = t.status == TrackStatus.PREDICTED || t.status == TrackStatus.LOST
            drawRect(c, Offset(r.left,r.top), Size(r.width(),r.height()), style = Stroke(if (isLocked) 5f else 3f,
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(16f,10f)) else null))
            if (isLocked) drawLine(c.copy(alpha=.7f), Offset(cx,cy), Offset(r.centerX(),r.centerY()),2f)
            val pct = if (t.confidence > 0f) " ${(t.confidence*100).toInt()}%" else ""
            paint.color = c.toArgb()
            drawContext.canvas.nativeCanvas.drawText("${t.label}$pct // #${t.trackingId}", r.left, (r.top-8f).coerceAtLeast(30f), paint)
        }
        drawRadar071(frame.targets, lockedId)
    }
}

private fun DrawScope.drawReticle071() {
    val cx=size.width/2f; val cy=size.height/2f
    drawCircle(H071.copy(alpha=.7f),60f,Offset(cx,cy),style=Stroke(2f)); drawCircle(H071,6f,Offset(cx,cy),style=Stroke(2f))
    drawLine(H071,Offset(cx-95f,cy),Offset(cx-16f,cy),2f); drawLine(H071,Offset(cx+16f,cy),Offset(cx+95f,cy),2f)
    drawLine(H071,Offset(cx,cy-95f),Offset(cx,cy-16f),2f); drawLine(H071,Offset(cx,cy+16f),Offset(cx,cy+95f),2f)
}

private fun DrawScope.drawTrails071(trails: Map<Int,List<Trail071>>, frame: DetectionFrame, lockedId: Int?) {
    val now=SystemClock.elapsedRealtime()
    trails.forEach { (id,pts) ->
        if (pts.size<2) return@forEach
        val c=if(id==lockedId) Color(0xFFFFD64A) else H071
        for(i in 1 until pts.size) {
            val a=pts[i-1]; val b=pts[i]; val age=now-b.at
            if(age>TRAIL_AGE_071) continue
            val alpha=(1f-age.toFloat()/TRAIL_AGE_071).coerceIn(.08f,.82f)
            drawLine(c.copy(alpha=alpha), point071(a.x,a.y,size.width,size.height,frame.imageWidth,frame.imageHeight),
                point071(b.x,b.y,size.width,size.height,frame.imageWidth,frame.imageHeight), if(id==lockedId)5f else 3f)
        }
    }
}

private fun DrawScope.drawRadar071(targets: List<DetectionTarget>, lockedId: Int?) {
    val radius=58f; val center=Offset(size.width-76f,size.height-130f)
    drawCircle(Color.Black.copy(alpha=.55f),radius+8f,center); drawCircle(H071,radius,center,style=Stroke(2f)); drawCircle(H071.copy(alpha=.5f),radius/2,center,style=Stroke(1.5f))
    targets.forEach { t ->
        val x=center.x+(t.normalizedBox.centerX()-.5f)*radius*1.6f; val y=center.y+(t.normalizedBox.centerY()-.5f)*radius*1.6f
        drawCircle(if(t.trackingId==lockedId)Color(0xFFFFD64A) else status071(t.status), if(t.trackingId==lockedId)6f else 4f, Offset(x,y))
    }
}

@Composable
private fun TargetPanel071(modifier: Modifier, bitmap: ImageBitmap?, target: DetectionTarget?, onUnlock: () -> Unit) {
    Column(modifier.width(170.dp).background(Color.Black.copy(alpha=.8f)).border(1.dp,status071(target?.status)).clickable(onClick=onUnlock).padding(5.dp)) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(Color.Black), contentAlignment=Alignment.Center) {
            if(bitmap!=null) Image(bitmap,"Locked target",Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else Text("TARGET LOST",color=Color(0xFFFF5353),fontSize=10.sp)
        }
        Text("${target?.label ?: "TARGET"} #${target?.trackingId ?: "--"}",color=status071(target?.status),fontSize=9.sp,modifier=Modifier.padding(top=4.dp))
    }
}

private fun updateTrails071(current: Map<Int,List<Trail071>>, frame: DetectionFrame): Map<Int,List<Trail071>> {
    val now=SystemClock.elapsedRealtime(); val next=current.mapValues{(_,p)->p.filter{now-it.at<=TRAIL_AGE_071}}.filterValues{it.isNotEmpty()}.toMutableMap()
    frame.targets.forEach { t ->
        if(t.status==TrackStatus.LOST) return@forEach
        if(speed071(t)<.006f && next[t.trackingId].isNullOrEmpty()) return@forEach
        val p=Trail071(t.normalizedBox.centerX(),t.normalizedBox.centerY(),now); val old=next[t.trackingId].orEmpty(); val last=old.lastOrNull()
        if(last==null || hypot(p.x-last.x,p.y-last.y)>=.0045f) next[t.trackingId]=(old+p).takeLast(TRAIL_POINTS_071)
    }
    return next
}

private fun status071(s:TrackStatus?):Color=when(s){TrackStatus.PREDICTED->Color(0xFFFFA33C);TrackStatus.LOST->Color(0xFFFF5353);TrackStatus.ACQUIRING->Color(0xFF7CEBFF);else->H071}
private fun speed071(t:DetectionTarget)=hypot(t.velocityX,t.velocityY)
private fun fastest071(ts:List<DetectionTarget>)=ts.filter{it.status!=TrackStatus.LOST}.maxByOrNull(::speed071)?.takeIf{speed071(it)>.015f}
private fun nearest071(ts:List<DetectionTarget>)=ts.minByOrNull{hypot(it.normalizedBox.centerX()-.5f,it.normalizedBox.centerY()-.5f)}
private fun fmtZoom071(z:Float)=if(z<10f)String.format(Locale.US,"%.1fx",z) else String.format(Locale.US,"%.0fx",z)
private fun point071(x:Float,y:Float,vw:Float,vh:Float,iw:Int,ih:Int):Offset{if(iw<=0||ih<=0)return Offset.Zero;val s=max(vw/iw.toFloat(),vh/ih.toFloat());val dw=iw*s;val dh=ih*s;return Offset((vw-dw)/2+x*dw,(vh-dh)/2+y*dh)}
private fun rect071(b:RectF,vw:Float,vh:Float,iw:Int,ih:Int):RectF{if(iw<=0||ih<=0)return RectF();val s=max(vw/iw.toFloat(),vh/ih.toFloat());val dw=iw*s;val dh=ih*s;val ox=(vw-dw)/2;val oy=(vh-dh)/2;return RectF(ox+b.left*dw,oy+b.top*dh,ox+b.right*dw,oy+b.bottom*dh)}
private fun crop071(source:Bitmap,target:DetectionTarget,frame:DetectionFrame):Bitmap?{val r=rect071(target.normalizedBox,source.width.toFloat(),source.height.toFloat(),frame.imageWidth,frame.imageHeight);val mx=r.width()*.16f;val my=r.height()*.16f;val l=(r.left-mx).toInt().coerceIn(0,source.width-1);val t=(r.top-my).toInt().coerceIn(0,source.height-1);val rr=(r.right+mx).toInt().coerceIn(l+1,source.width);val bb=(r.bottom+my).toInt().coerceIn(t+1,source.height);return runCatching{Bitmap.createBitmap(source,l,t,rr-l,bb-t)}.getOrNull()}
