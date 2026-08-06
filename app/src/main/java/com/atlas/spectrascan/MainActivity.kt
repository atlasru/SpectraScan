package com.atlas.spectrascan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

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
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (granted) ScannerScreen() else Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("Разреши доступ к камере", color = Color.White)
    }
}

@Composable
private fun ScannerScreen() {
    var mode by remember { mutableStateOf(ScanMode.TAC) }
    var fps by remember { mutableIntStateOf(60) }
    LaunchedEffect(Unit) {
        while (true) {
            fps = (55..60).random()
            delay(800)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraPreview(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(mode.tint.copy(alpha = if (mode == ScanMode.THM) 0.15f else 0.07f)))
        HudOverlay(mode.tint)

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SPECTRASCAN // ${mode.title}", color = mode.tint, fontSize = 15.sp)
            Text("FPS $fps   TARGETS 00   LOCAL", color = mode.tint.copy(alpha = 0.8f), fontSize = 11.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            ScanMode.entries.forEach { item ->
                Box(
                    modifier = Modifier
                        .border(1.dp, if (item == mode) item.tint else Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(8.dp))
                        .clickable { mode = item }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(item.name, color = if (item == mode) item.tint else Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                }, ContextCompat.getMainExecutor(context))
            }
        }
    )
    DisposableEffect(Unit) { onDispose { } }
}

@Composable
private fun HudOverlay(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(color, radius = 72f, center = Offset(cx, cy), style = Stroke(2f))
        drawCircle(color.copy(alpha = 0.65f), radius = 8f, center = Offset(cx, cy), style = Stroke(2f))
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
    }
}
