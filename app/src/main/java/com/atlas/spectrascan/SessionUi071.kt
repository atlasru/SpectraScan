package com.atlas.spectrascan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

@Composable
fun SessionSideControls071(
    primary: Color,
    accent: Color,
    running: Boolean,
    elapsedMs: Long,
    uniqueTargets: Int,
    onSnap: () -> Unit,
    onSessionToggle: () -> Unit,
    onOpenStats: () -> Unit
) {
    Column(
        modifier = Modifier.padding(start = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start
    ) {
        SideButton071("SNAP", primary, onSnap)
        SideButton071(if (running) "REC ■" else "SESSION", if (running) accent else primary, onSessionToggle)
        if (running) {
            Text(
                "${formatSessionTime071(elapsedMs)} // TGT $uniqueTargets",
                color = primary.copy(alpha = .82f),
                fontFamily = FontFamily.Monospace,
                fontSize = 7.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = .56f)).padding(horizontal = 5.dp, vertical = 3.dp).clickable(onClick = onOpenStats)
            )
        }
    }
}

@Composable
private fun SideButton071(text: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = .76f))
            .border(1.dp, color.copy(alpha = .9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 10.dp)
    ) {
        Text(text, color = color, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
    }
}

@Composable
fun SessionStatsPanel071(
    primary: Color,
    dim: Color,
    accent: Color,
    summary: SessionStore071.Summary,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .width(290.dp)
            .fillMaxHeight(.62f)
            .background(Color.Black.copy(alpha = .93f))
            .border(1.dp, primary.copy(alpha = .75f))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SESSION // ${if (summary.running) "RECORDING" else "SUMMARY"}", color = if (summary.running) accent else primary, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text("CLOSE", color = primary, fontFamily = FontFamily.Monospace, fontSize = 8.sp, modifier = Modifier.clickable(onClick = onClose).padding(6.dp))
        }
        Text("TIME ${formatSessionTime071(summary.elapsedMs)}", color = primary, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        Text("UNIQUE ${summary.uniqueTargets} // ACTIVE ${summary.activeTargets}", color = primary, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
        val classes = if (summary.classCounts.isEmpty()) "NO TARGETS" else summary.classCounts.entries.joinToString("  ") { "${it.key}:${it.value}" }
        Text(classes, color = dim, fontFamily = FontFamily.Monospace, fontSize = 8.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            summary.targets.asReversed().forEach { target ->
                Text(
                    "#${target.trackingId} ${target.label.uppercase()}  PEAK ${(target.peakConfidence * 100).toInt()}%  AGE ${formatSessionTime071(target.visibleMs())}",
                    color = primary.copy(alpha = .86f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
        }
    }
}

fun formatSessionTime071(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}
