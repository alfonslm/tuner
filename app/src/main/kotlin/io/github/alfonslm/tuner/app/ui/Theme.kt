package io.github.alfonslm.tuner.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A tuner is read at a glance, often in a dim room: one dark palette, large type, colour carries the state. */
object TunerColors {
    val Background = Color(0xFF0F1216)
    val Panel = Color(0xFF171C22)
    val Line = Color(0xFF2A323C)
    val Text = Color(0xFFE8EDF2)
    val Muted = Color(0xFF8B97A5)
    val Accent = Color(0xFF5CC8FF)
    val OnAccent = Color(0xFF06202C)
    val Good = Color(0xFF4ADE80)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)
}

private val Scheme = darkColorScheme(
    primary = TunerColors.Accent,
    onPrimary = TunerColors.OnAccent,
    secondaryContainer = TunerColors.Accent,
    onSecondaryContainer = TunerColors.OnAccent,
    background = TunerColors.Background,
    onBackground = TunerColors.Text,
    surface = TunerColors.Panel,
    onSurface = TunerColors.Text,
    surfaceVariant = TunerColors.Panel,
    onSurfaceVariant = TunerColors.Muted,
    surfaceContainer = TunerColors.Panel,
    surfaceContainerHigh = TunerColors.Panel,
    surfaceContainerHighest = TunerColors.Line,
    outline = TunerColors.Line,
    outlineVariant = TunerColors.Line,
    error = TunerColors.Bad,
)

@Composable
fun TunerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
