package com.scamguard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scamguard.judge.Band

val Green = Color(0xFF1B7F3B)
val Amber = Color(0xFFB26A00)
val Red = Color(0xFFC62828)
val Neutral = Color(0xFF5F6B7A)
val Ink = Color(0xFF14202B)

fun Band.color() = when (this) {
    Band.GREEN -> Green
    Band.AMBER -> Amber
    Band.RED -> Red
    Band.LISTENING -> Neutral
}

private val Scheme = lightColorScheme(
    primary = Color(0xFF15577A),
    onPrimary = Color.White,
    background = Color(0xFFF5F7F9),
    surface = Color.White,
    onBackground = Ink,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE9EEF2),
)

@Composable
fun ScamGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

/** A scrolling page with a title and an optional Back link. Text is large on purpose. */
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (onBack != null) TextButton(onClick = onBack) { Text("‹ Back", fontSize = 17.sp) }
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        content()
    }
}

@Composable
fun InfoCard(modifier: Modifier = Modifier, tint: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.compositeOver(Color.White)),   // solid: a see-through card shows its own shadow
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun Body(text: String, color: Color = Ink, bold: Boolean = false) {
    Text(text, fontSize = 17.sp, lineHeight = 24.sp, color = color, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
}

@Composable
fun StatusRow(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (ok) "✓" else "!", color = if (ok) Green else Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Body(text)
    }
}
