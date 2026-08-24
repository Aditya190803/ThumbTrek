package com.thumbtrek.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Trail palette: a warm-black ground (never pure #000) with one brand accent — moss
 * green, the color the app is named after. Amber ([TrekAmber]) is a semantic "streak
 * is alive" signal only, never a second brand color competing with green.
 */
private val TrekColors = darkColorScheme(
    primary = Color(0xFF7BD88A),
    onPrimary = Color(0xFF0A2213),
    primaryContainer = Color(0xFF1C3324),
    onPrimaryContainer = Color(0xFFA9EBB5),
    secondary = Color(0xFF9FC3A9),
    onSecondary = Color(0xFF0F1F15),
    background = Color(0xFF12140F),
    onBackground = Color(0xFFEAEEE6),
    surface = Color(0xFF181B14),
    onSurface = Color(0xFFEAEEE6),
    surfaceVariant = Color(0xFF23281C),
    onSurfaceVariant = Color(0xFFB9C2AC),
    outline = Color(0xFF3A4131),
    outlineVariant = Color(0xFF272C20),
    error = Color(0xFFE8798C),
    onError = Color(0xFF3A0E14),
)

/** The one place amber shows up: streak fire, earned badges, "you're on a roll" states. */
val TrekAmber = Color(0xFFE8A33D)

/** Card ground, one notch lighter than [TrekColors.surface] — used instead of Card's shadow. */
val TrekCardSurface = Color(0xFF1D2117)

private val TrekShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ThumbTrekTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TrekColors,
        typography = ThumbTrekTypography,
        shapes = TrekShapes,
        content = content,
    )
}
