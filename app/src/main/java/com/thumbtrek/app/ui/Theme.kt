package com.thumbtrek.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TrekColors = darkColorScheme(
    primary = Color(0xFF7BD88A),
    onPrimary = Color(0xFF0B2415),
    secondary = Color(0xFF9FC3A9),
    background = Color(0xFF0E1512),
    onBackground = Color(0xFFE3EAE5),
    surface = Color(0xFF16201B),
    onSurface = Color(0xFFE3EAE5),
    surfaceVariant = Color(0xFF223028),
    onSurfaceVariant = Color(0xFFB7C6BC),
)

@Composable
fun ThumbTrekTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TrekColors, content = content)
}
