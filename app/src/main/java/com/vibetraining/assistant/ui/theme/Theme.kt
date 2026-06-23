package com.vibetraining.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val RunnerOrange = Color(0xFFE8611A)
private val RunnerOrangeContainer = Color(0xFFFFDBCC)
private val DarkSurface = Color(0xFF1A1A1A)

private val LightColors = lightColorScheme(
    primary = RunnerOrange,
    onPrimary = Color.White,
    primaryContainer = RunnerOrangeContainer,
    secondary = Color(0xFF555F71),
    surface = Color(0xFFF8F8F8),
    background = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB691),
    onPrimary = Color(0xFF4D1E00),
    primaryContainer = Color(0xFF6F2D00),
    secondary = Color(0xFFBAC7DC),
    surface = DarkSurface,
    background = Color(0xFF111111)
)

@Composable
fun VibeTrainingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
