package dev.aniforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF4D6D),
    onPrimary = Color(0xFFFFF0F4),
    secondary = Color(0xFF7EE3C5),
    background = Color(0xFF10141C),
    surface = Color(0xFF16202B),
    onSurface = Color(0xFFE8EEF7)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFB31E45),
    background = Color(0xFFFDF7F9)
)

@Composable
fun AniForgeTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content
    )
}
