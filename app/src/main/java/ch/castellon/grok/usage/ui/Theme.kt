package ch.castellon.grok.usage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Bg = Color(0xFF070708)
val CardBg = Color(0xFF121214)
val Ink = Color(0xFFF4F4F5)
val Muted = Color(0xFFA1A1AA)
val Line = Color(0xFF2A2A30)
val Track = Color(0xFF2A2A30)
val BotBlue = Color(0xFF3B82F6)
val Meter = Color(0xFFE4E4E7)
val Warning = Color(0xFFF5C16C)

fun segmentColor(label: String): Color = when (label) {
    "Grok Build" -> Color(0xFFFFB020)
    "Chat" -> Color(0xFF6EA8FE)
    "Imagine" -> Color(0xFFC084FC)
    "Voice" -> Color(0xFF34D399)
    "API" -> Color(0xFFFB7185)
    else -> Color(0xFFA1A1AA)
}

@Composable
fun GrokUsageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink,
            onPrimary = Bg,
            background = Bg,
            onBackground = Ink,
            surface = CardBg,
            onSurface = Ink,
            secondary = BotBlue,
            onSecondary = Color.White,
            error = Warning,
        ),
        content = content,
    )
}
