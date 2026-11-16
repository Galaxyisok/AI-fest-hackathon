package com.scamguard.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun Eyebrow(text: String, color: Color = Ocean) {
    Text(text, color = color, fontSize = 11.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun ShieldArtwork(modifier: Modifier = Modifier, active: Boolean = false) {
    val phase = ambientPhase(active, 4500)
    Canvas(modifier) {
        val w = minOf(size.width, size.height * 1.1f)
        val h = size.height
        translate(left = (size.width - w) / 2f) {
        val cx = w / 2f
        val cy = h / 2f
        if (w > 70) {
            drawCircle(Brush.radialGradient(listOf(Mint.copy(alpha = .75f), Mint.copy(alpha = 0f)), center = Offset(cx, cy), radius = w * .5f), radius = w * .5f, center = Offset(cx, cy))
        }
        val shield = Path().apply {
            moveTo(cx, h * .12f)
            cubicTo(w * .65f, h * .22f, w * .73f, h * .22f, w * .79f, h * .25f)
            cubicTo(w * .81f, h * .59f, w * .72f, h * .76f, cx, h * .88f)
            cubicTo(w * .28f, h * .76f, w * .19f, h * .59f, w * .21f, h * .25f)
            cubicTo(w * .27f, h * .22f, w * .35f, h * .22f, cx, h * .12f)
            close()
        }
        drawPath(shield, Brush.linearGradient(listOf(Color.White.copy(alpha = .88f), Mint.copy(alpha = .8f), Teal.copy(alpha = .16f)), start = Offset(w * .2f, 0f), end = Offset(w * .8f, h)))
        drawPath(shield, Teal.copy(alpha = .65f), style = Stroke((w * .012f).coerceAtLeast(1f)))
        for (i in -4..4) {
            val envelope = (1f - kotlin.math.abs(i) / 5f)
            val length = h * (.035f + envelope * .13f) * (if (active) 1f + .2f * sin(phase + i) else 1f)
            val x = cx + i * w * .047f
            drawLine(Teal, Offset(x, cy - length / 2f), Offset(x, cy + length / 2f), strokeWidth = (w * .017f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        }
        }
    }
}

@Composable
private fun ambientPhase(active: Boolean, duration: Int): Float {
    if (!active) return 0f
    val transition = rememberInfiniteTransition(label = "ambient")
    val phase by transition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(duration, easing = LinearEasing)), label = "phase")
    return phase
}

@Composable
fun WaveTrace(modifier: Modifier = Modifier, color: Color = Teal, active: Boolean = false) {
    val phase = ambientPhase(active, 2600)
    Canvas(modifier) {
        for (layer in 0..2) {
            val path = Path()
            for (i in 0..100) {
                val x = size.width * i / 100f
                val envelope = sin(PI * i / 100).toFloat()
                val angle = i / 100f * 5f * PI.toFloat() + layer * .6f + phase
                val y = size.height / 2 + sin(angle) * envelope * size.height * (.26f - layer * .055f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(alpha = .75f - layer * .22f), style = Stroke(1.5f + layer * .2f))
        }
    }
}
