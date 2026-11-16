package com.scamguard.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scamguard.judge.Band

val Green = Color(0xFF237E64)
val Amber = Color(0xFFA45D13)
val Red = Color(0xFFC74444)
val Neutral = Color(0xFF61747A)
val Ink = Color(0xFF183B43)
val Ocean = Color(0xFF15577A)
val Teal = Color(0xFF208A87)
val Mint = Color(0xFFBBEBDC)
val Pearl = Color(0xFFF7FAF9)
val Mist = Color(0xFFE5F1EE)

fun Band.color() = when (this) {
    Band.GREEN -> Green
    Band.AMBER -> Amber
    Band.RED -> Red
    Band.LISTENING -> Neutral
}

private val Scheme = lightColorScheme(
    primary = Ocean, onPrimary = Color.White,
    secondary = Teal, onSecondary = Color.White,
    secondaryContainer = Mint, onSecondaryContainer = Ink,
    primaryContainer = Mist, onPrimaryContainer = Ink,
    background = Pearl, surface = Color.White,
    onBackground = Ink, onSurface = Ink,
    surfaceVariant = Mist, onSurfaceVariant = Neutral,
    outline = Color(0xFFABC5C4), outlineVariant = Color(0xFFDCE8E5), error = Red,
)

@Composable
fun ScamGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        shapes = Shapes(small = RoundedCornerShape(14.dp), medium = RoundedCornerShape(22.dp), large = RoundedCornerShape(30.dp)),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 33.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
        ),
    ) {
        Surface(Modifier.fillMaxSize(), color = Pearl, content = content)
    }
}

@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val collapse = (scroll.value / with(density) { 180.dp.toPx() }).coerceIn(0f, 1f)
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFEAF5F1), Pearl, Pearl)))
            .safeDrawingPadding().verticalScroll(scroll)
            .padding(horizontal = 22.dp).padding(top = 10.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (onBack != null) TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 10.dp)) {
                Text("‹", fontSize = 29.sp)
                Spacer(Modifier.width(7.dp))
                Text("Back", fontSize = 16.sp)
            } else Text("Scam Guard", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.size(30.dp).background(Color.White.copy(alpha = .7f), CircleShape), contentAlignment = Alignment.Center) {
                ShieldArtwork(Modifier.size(23.dp))
            }
        }
        if (title != "Scam Guard") Text(title, style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.graphicsLayer {
                alpha = 1f - collapse * .2f
                translationY = collapse * -8f
            })
        content()
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    var revealed by remember { mutableStateOf(false) }
    val viewport = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val progress by animateFloatAsState(if (revealed) 1f else 0f, tween(420), label = "cardReveal")
    Card(
        modifier.fillMaxWidth().onGloballyPositioned {
            if (it.boundsInWindow().top < viewport && it.boundsInWindow().bottom > 0) revealed = true
        }.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 20.dp.toPx()
        },
        shape = RoundedCornerShape(26.dp),
        border = BorderStroke(1.dp, Color(0xFFDDEAE6)),
        colors = CardDefaults.cardColors(containerColor = tint.compositeOver(Color.White)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun Body(text: String, color: Color = Ink, bold: Boolean = false) {
    Text(text, fontSize = 17.sp, lineHeight = 25.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background((if (ok) Green else Amber).copy(alpha = .1f), CircleShape), contentAlignment = Alignment.Center) {
            Text(if (ok) "✓" else "!", color = if (ok) Green else Amber, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Body(text)
    }
}
