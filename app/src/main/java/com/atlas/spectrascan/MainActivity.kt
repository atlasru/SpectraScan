package com.atlas.spectrascan

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SpectraScanApp() } }
    }
}

private enum class ScanMode(val title: String, val tint: Color) {
    TAC("TACTICAL", Color(0xFF61FFB2)),
    THM("THERMAL", Color(0xFFFF6A3D)),
    NVG("NIGHT", Color(0xFF8CFF4F)),
    SNR("SONAR", Color(0xFF42D9FF))
}

@Composable
private fun SpectraScanApp() {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        ScannerScreen()
    } else {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text("Разреши доступ к камере", color = Color.White)
        }
    }
}

@Composable
private fun ScannerScreen() {
    var mode by remember { mutableStateOf(ScanMode.TAC) }
    var frame by remember { mutableStateOf(DetectionFrame()) }
    var lockedId by remember { mutableStateOf<Int?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var zoomBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val latestFrame by rememberUpdatedState(frame)
    val lockedTarget = frame.targets.firstOrNull { it.trackingId == lockedId }

    LaunchedEffect(lockedId, previewView) {
        while (lockedId != null) {
            val currentFrame = latestFrame
            val target = currentFrame.targets.firstOrNull { it.trackingId == lockedId }
            val source = previewView?.bitmap
            if (target != null && source != null) {
                cropTarget(source, target, currentFrame)?.let { zoomBitmap = it.asImageBitmap() }
            }
            delay(220)
        }
        zoomBitmap = null
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onPreviewReady = { previewView = it },
            onFrame = { frame = it }
        )

        Box(
            Modifier
                .fillMaxSize()
                .background(mode.tint.copy(alpha = if (mode == ScanMode.THM) 0.16f else 0.055f))
        )

        TrackingHud(
            color = mode.tint,
            frame = frame,
            lockedId = lockedId,
            onTargetTapped = { tappedId ->
                lockedId = if (lockedId == tappedId) null else tappedId
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 14.dp, start = 14.dp, end = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SPECTRASCAN // ${mode.title}", color = mode.tint, fontSize = 15.sp)
            Text(
                "FPS ${frame.inferenceFps.toString().padStart(2, '0')}   " +
                    "TARGETS ${frame.targets.size.toString().padStart(2, '0')}   " +
                    if (lockedTarget != null) "LOCKED #${lockedTarget.trackingId}" else "SCAN ACTIVE",
                color = mode.tint.copy(alpha = 0.86f),
                fontSize = 11.sp
            )
        }

        if (lockedId != null) {
            TargetZoomPanel(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 14.dp),
                bitmap = zoomBitmap,
                target = lockedTarget,
                color = mode.tint,
                onUnlock = { lockedId = null }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            ScanMode.entries.forEach { item ->
                Box(
                    modifier = Modifier
                        .border(
                            1.dp,
                            if (item == mode) item.tint else Color.White.copy(alpha = 0.35f),
                            RoundedCornerShape(8.dp)
                        )
                        .background(Color.Black.copy(alpha = 0.64f), RoundedCornerShape(8.dp))
                        .clickable { mode = item }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        item.name,
                        color = if (item == mode) item.tint else Color.White,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier = Modifier,
    onPreviewReady: (PreviewView) -> Unit,
    onFrame: (DetectionFrame) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFrame by rememberUpdatedState(onFrame)
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analyzer = remember {
        ObjectTrackingAnalyzer(mainExecutor) { latestOnFrame(it) }
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

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
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }, mainExecutor)
            }
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            cameraProvider?.unbindAll()
            analyzer.close()
            analysisExecutor.shutdown()
        }
    }
}

@Composable
private fun TrackingHud(
    color: Color,
    frame: DetectionFrame,
    lockedId: Int?,
    onTargetTapped: (Int) -> Unit
) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 25f
        }
    }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(frame.targets, frame.imageWidth, frame.imageHeight, lockedId) {
                detectTapGestures { tap ->
                    frame.targets
                        .asReversed()
                        .firstOrNull {
                            mapTargetRect(
                                it.normalizedBox,
                                size.width.toFloat(),
                                size.height.toFloat(),
                                frame.imageWidth,
                                frame.imageHeight
                            ).contains(tap.x, tap.y)
                        }
                        ?.let { onTargetTapped(it.trackingId) }
                }
            }
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f

        drawCircle(color.copy(alpha = 0.7f), radius = 72f, center = Offset(cx, cy), style = Stroke(2f))
        drawCircle(color.copy(alpha = 0.8f), radius = 8f, center = Offset(cx, cy), style = Stroke(2f))
        drawLine(color, Offset(cx - 115f, cy), Offset(cx - 18f, cy), 2f)
        drawLine(color, Offset(cx + 18f, cy), Offset(cx + 115f, cy), 2f)
        drawLine(color, Offset(cx, cy - 115f), Offset(cx, cy - 18f), 2f)
        drawLine(color, Offset(cx, cy + 18f), Offset(cx, cy + 115f), 2f)

        val pad = 34f
        val len = 80f
        drawLine(color, Offset(pad, pad), Offset(pad + len, pad), 3f)
        drawLine(color, Offset(pad, pad), Offset(pad, pad + len), 3f)
        drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad - len, pad), 3f)
        drawLine(color, Offset(size.width - pad, pad), Offset(size.width - pad, pad + len), 3f)
        drawLine(color, Offset(pad, size.height - pad), Offset(pad + len, size.height - pad), 3f)
        drawLine(color, Offset(pad, size.height - pad), Offset(pad, size.height - pad - len), 3f)
        drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - len, size.height - pad), 3f)
        drawLine(color, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - len), 3f)

        frame.targets.forEach { target ->
            val rect = mapTargetRect(
                target.normalizedBox,
                size.width,
                size.height,
                frame.imageWidth,
                frame.imageHeight
            )
            val isLocked = target.trackingId == lockedId
            val targetColor = if (isLocked) Color(0xFFFFD64A) else color
            val stroke = if (isLocked) 5f else 3f

            drawRect(
                color = targetColor,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width(), rect.height()),
                style = Stroke(stroke)
            )

            val corner = max(18f, minOf(rect.width(), rect.height()) * 0.16f)
            drawLine(targetColor, Offset(rect.left, rect.top), Offset(rect.left + corner, rect.top), 7f)
            drawLine(targetColor, Offset(rect.left, rect.top), Offset(rect.left, rect.top + corner), 7f)
            drawLine(targetColor, Offset(rect.right, rect.bottom), Offset(rect.right - corner, rect.bottom), 7f)
            drawLine(targetColor, Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - corner), 7f)

            if (isLocked) {
                drawLine(
                    targetColor.copy(alpha = 0.75f),
                    Offset(cx, cy),
                    Offset(rect.centerX(), rect.centerY()),
                    2f
                )
                drawCircle(
                    targetColor,
                    radius = 16f,
                    center = Offset(rect.centerX(), rect.centerY()),
                    style = Stroke(3f)
                )
            }

            val percent = if (target.confidence > 0f) {
                " ${(target.confidence * 100).toInt()}%"
            } else {
                ""
            }
            val label = "${target.label}$percent // #${target.trackingId}"
            labelPaint.color = targetColor.toArgb()
            drawContext.canvas.nativeCanvas.drawText(
                label,
                rect.left,
                (rect.top - 9f).coerceAtLeast(32f),
                labelPaint
            )
        }

        drawRadar(color, frame.targets, lockedId)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadar(
    color: Color,
    targets: List<DetectionTarget>,
    lockedId: Int?
) {
    val radius = 70f
    val center = Offset(size.width - 92f, size.height - 165f)
    drawCircle(Color.Black.copy(alpha = 0.48f), radius + 10f, center)
    drawCircle(color, radius, center, style = Stroke(3f))
    drawCircle(color.copy(alpha = 0.65f), radius / 2f, center, style = Stroke(2f))
    drawLine(color.copy(alpha = 0.65f), Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 2f)
    drawLine(color.copy(alpha = 0.65f), Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 2f)

    targets.forEach { target ->
        val x = center.x + (target.normalizedBox.centerX() - 0.5f) * radius * 1.65f
        val y = center.y + (target.normalizedBox.centerY() - 0.5f) * radius * 1.65f
        drawCircle(
            if (target.trackingId == lockedId) Color(0xFFFFD64A) else color,
            radius = if (target.trackingId == lockedId) 7f else 4f,
            center = Offset(x, y)
        )
    }
}

@Composable
private fun TargetZoomPanel(
    modifier: Modifier,
    bitmap: ImageBitmap?,
    target: DetectionTarget?,
    color: Color,
    onUnlock: () -> Unit
) {
    Column(
        modifier = modifier
            .width(190.dp)
            .background(Color.Black.copy(alpha = 0.76f))
            .border(1.dp, color)
            .clickable { onUnlock() }
            .padding(5.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(125.dp)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Locked target",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("ACQUIRING...", color = color, fontSize = 11.sp)
            }
        }
        Text(
            text = "LOCKED // ${target?.label ?: "TARGET"} #${target?.trackingId ?: "--"}",
            color = Color(0xFFFFD64A),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text("TAP PANEL TO UNLOCK", color = color.copy(alpha = 0.8f), fontSize = 9.sp)
    }
}

private fun mapTargetRect(
    normalized: RectF,
    viewWidth: Float,
    viewHeight: Float,
    imageWidth: Int,
    imageHeight: Int
): RectF {
    if (imageWidth <= 0 || imageHeight <= 0) return RectF()
    val scale = max(viewWidth / imageWidth.toFloat(), viewHeight / imageHeight.toFloat())
    val scaledWidth = imageWidth * scale
    val scaledHeight = imageHeight * scale
    val offsetX = (viewWidth - scaledWidth) / 2f
    val offsetY = (viewHeight - scaledHeight) / 2f

    return RectF(
        offsetX + normalized.left * scaledWidth,
        offsetY + normalized.top * scaledHeight,
        offsetX + normalized.right * scaledWidth,
        offsetY + normalized.bottom * scaledHeight
    )
}

private fun cropTarget(
    source: Bitmap,
    target: DetectionTarget,
    frame: DetectionFrame
): Bitmap? {
    val rect = mapTargetRect(
        target.normalizedBox,
        source.width.toFloat(),
        source.height.toFloat(),
        frame.imageWidth,
        frame.imageHeight
    )
    val expansion = max(rect.width(), rect.height()) * 0.22f
    val left = (rect.left - expansion).toInt().coerceIn(0, source.width - 1)
    val top = (rect.top - expansion).toInt().coerceIn(0, source.height - 1)
    val right = (rect.right + expansion).toInt().coerceIn(left + 1, source.width)
    val bottom = (rect.bottom + expansion).toInt().coerceIn(top + 1, source.height)

    return runCatching {
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }.getOrNull()
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
