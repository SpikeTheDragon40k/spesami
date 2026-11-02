package it.spesami.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Dracula palette (https://draculatheme.com/contribute), also used by the dark PDF exports. */
object Dracula {
    val Background = Color(0xFF282A36)
    val CurrentLine = Color(0xFF44475A)
    val Foreground = Color(0xFFF8F8F2)
    val Comment = Color(0xFF6272A4)
    val Cyan = Color(0xFF8BE9FD)
    val Green = Color(0xFF50FA7B)
    val Orange = Color(0xFFFFB86C)
    val Pink = Color(0xFFFF79C6)
    val Purple = Color(0xFFBD93F9)
    val Red = Color(0xFFFF5555)
    val Yellow = Color(0xFFF1FA8C)
}

private val DraculaColors = darkColorScheme(
    primary = Dracula.Purple,
    onPrimary = Dracula.Background,
    primaryContainer = Color(0xFF4F4475),
    onPrimaryContainer = Dracula.Foreground,
    secondary = Dracula.Pink,
    onSecondary = Dracula.Background,
    secondaryContainer = Dracula.CurrentLine,
    onSecondaryContainer = Dracula.Foreground,
    tertiary = Dracula.Green,
    onTertiary = Dracula.Background,
    tertiaryContainer = Color(0xFF2F5B40),
    onTertiaryContainer = Dracula.Foreground,
    background = Dracula.Background,
    onBackground = Dracula.Foreground,
    surface = Dracula.Background,
    onSurface = Dracula.Foreground,
    surfaceVariant = Dracula.CurrentLine,
    onSurfaceVariant = Color(0xFFC3C7DB),
    surfaceContainerLowest = Color(0xFF21222C),
    surfaceContainerLow = Color(0xFF2B2D3A),
    surfaceContainer = Color(0xFF303241),
    surfaceContainerHigh = Color(0xFF383A4B),
    surfaceContainerHighest = Dracula.CurrentLine,
    inverseSurface = Dracula.Foreground,
    inverseOnSurface = Dracula.Background,
    inversePrimary = Color(0xFF6D4BC3),
    outline = Dracula.Comment,
    outlineVariant = Dracula.CurrentLine,
    error = Dracula.Red,
    onError = Dracula.Background,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D4BC3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF24005A),
    secondary = Color(0xFFB0306F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E6),
    onSecondaryContainer = Color(0xFF3E0021),
    tertiary = Color(0xFF2E7D4F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F1C6),
    onTertiaryContainer = Color(0xFF00210F),
    background = Color(0xFFFDF8FF),
    surface = Color(0xFFFDF8FF),
)

@Composable
fun SpesamiTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DraculaColors else LightColors, content = content)
}
