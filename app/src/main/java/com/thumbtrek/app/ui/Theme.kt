package com.thumbtrek.app.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Production green neutrals, moss actions, and separate colors for status and chart data. */
@Immutable
data class TrekPalette(
    val isDark: Boolean,
    /** Page ground. */
    val ground: Color,
    /** Panels that genuinely are objects: banners, rows, tiles. */
    val groundRaised: Color,
    /** Progress tracks and input fields. */
    val groundSunken: Color,
    val hairline: Color,
    val hairlineSoft: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val accent: Color,
    val onAccent: Color,
    val accentWash: Color,
    val amber: Color,
    val amberWash: Color,
    val slate: Color,
    val slateWash: Color,
    val success: Color,
    val successWash: Color,
    val danger: Color,
    val dangerWash: Color,
    /** Gold, silver, bronze for the top three, tuned per theme. */
    val medals: List<Color>,
)

private val DarkPalette = TrekPalette(
    isDark = true,
    ground = Color(0xFF0C0F0B),
    groundRaised = Color(0xFF13170F),
    groundSunken = Color(0xFF080A07),
    hairline = Color(0xFF262C1D),
    hairlineSoft = Color(0xFF1A1F15),
    ink = Color(0xFFEDF1E6),
    inkMuted = Color(0xFFA9B39C),
    inkFaint = Color(0xFF79836D),
    accent = Color(0xFF8BE6A0),
    onAccent = Color(0xFF05210F),
    accentWash = Color(0xFF17281B),
    amber = Color(0xFFF2B455),
    amberWash = Color(0xFF2A2113),
    slate = Color(0xFF8FB6CC),
    slateWash = Color(0xFF16212A),
    success = Color(0xFF8BE6A0),
    successWash = Color(0xFF17281B),
    danger = Color(0xFFF08497),
    dangerWash = Color(0xFF2B1319),
    medals = listOf(Color(0xFFF2C55C), Color(0xFFC9D2C2), Color(0xFFD09A66)),
)

private val LightPalette = TrekPalette(
    isDark = false,
    // Bone paper, not white: warm enough to read in direct sun without glare.
    ground = Color(0xFFF3F1E8),
    groundRaised = Color(0xFFFBFAF4),
    groundSunken = Color(0xFFE6E3D6),
    hairline = Color(0xFFDCD8C8),
    hairlineSoft = Color(0xFFEAE7DA),
    ink = Color(0xFF171A10),
    inkMuted = Color(0xFF5A6151),
    inkFaint = Color(0xFF6B7160),
    accent = Color(0xFF1E6B3E),
    onAccent = Color(0xFFFBFAF4),
    accentWash = Color(0xFFDFEEE2),
    amber = Color(0xFF8A5806),
    amberWash = Color(0xFFF6E9CE),
    slate = Color(0xFF1D5F80),
    slateWash = Color(0xFFDDEAF1),
    success = Color(0xFF1E6B3E),
    successWash = Color(0xFFDFEEE2),
    danger = Color(0xFFA32639),
    dangerWash = Color(0xFFF7DFE2),
    medals = listOf(Color(0xFF9A6E12), Color(0xFF6E7668), Color(0xFF8A5230)),
)

val LocalTrekPalette = staticCompositionLocalOf { DarkPalette }

/** Shorthand: `Trek.accent` instead of `LocalTrekPalette.current.accent`. */
val Trek: TrekPalette
    @Composable @ReadOnlyComposable get() = LocalTrekPalette.current

/**
 * False when the user has turned system animations off, which is what both Developer
 * options and the accessibility "remove animations" toggle do. Everything that moves in
 * this app checks it first; reduced motion still gets the state change, just instantly.
 */
val LocalTrekMotion = staticCompositionLocalOf { true }

/** Exponential ease-out. Fast commit, long settle, no overshoot. */
val TrekEase: Easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

/** Shorter ease for micro feedback, where the long tail would read as lag. */
val TrekEaseShort: Easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

object TrekDur {
    const val MICRO = 110
    const val SMALL = 220
    const val MEDIUM = 220
    const val LARGE = 280

    /** Initial data reveal, also disabled by the system animation preference. */
    const val REVEAL = 350
}

/** Collapses a duration to zero when the user has asked for no animation. */
@Composable
fun trekDuration(millis: Int): Int = if (LocalTrekMotion.current) millis else 0

private fun schemeFor(p: TrekPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentWash,
        onPrimaryContainer = p.accent,
        secondary = p.slate,
        onSecondary = Color(0xFF08131A),
        secondaryContainer = p.slateWash,
        onSecondaryContainer = p.slate,
        tertiary = p.amber,
        onTertiary = Color(0xFF231704),
        tertiaryContainer = p.amberWash,
        onTertiaryContainer = p.amber,
        background = p.ground,
        onBackground = p.ink,
        surface = p.groundRaised,
        onSurface = p.ink,
        surfaceVariant = p.groundSunken,
        onSurfaceVariant = p.inkMuted,
        surfaceTint = p.accent,
        inverseSurface = p.ink,
        inverseOnSurface = p.ground,
        inversePrimary = Color(0xFF1E6B3E),
        error = p.danger,
        onError = Color(0xFF2B0810),
        errorContainer = p.dangerWash,
        onErrorContainer = p.danger,
        outline = Color(0xFF3B4331),
        outlineVariant = p.hairline,
        scrim = Color(0xFF000000),
        // M3 1.3 routes dialogs, sheets, switches and the nav bar through these. Left at
        // their defaults they fall back to Material's baseline purple-grey neutrals, which
        // is why the old dialogs never quite matched the app.
        surfaceBright = Color(0xFF2A3024),
        surfaceDim = p.groundSunken,
        surfaceContainerLowest = p.groundSunken,
        surfaceContainerLow = p.ground,
        surfaceContainer = p.groundRaised,
        surfaceContainerHigh = Color(0xFF1A1F15),
        surfaceContainerHighest = Color(0xFF222819),
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentWash,
        onPrimaryContainer = Color(0xFF0C3A1F),
        secondary = p.slate,
        onSecondary = Color(0xFFF6FAFC),
        secondaryContainer = p.slateWash,
        onSecondaryContainer = Color(0xFF0D3549),
        tertiary = p.amber,
        onTertiary = Color(0xFFFDF8EC),
        tertiaryContainer = p.amberWash,
        onTertiaryContainer = Color(0xFF4A2F02),
        background = p.ground,
        onBackground = p.ink,
        surface = p.groundRaised,
        onSurface = p.ink,
        surfaceVariant = p.groundSunken,
        onSurfaceVariant = p.inkMuted,
        surfaceTint = p.accent,
        inverseSurface = Color(0xFF1C2016),
        inverseOnSurface = p.ground,
        inversePrimary = Color(0xFF8BE6A0),
        error = p.danger,
        onError = Color(0xFFFFF7F7),
        errorContainer = p.dangerWash,
        onErrorContainer = Color(0xFF54121E),
        outline = Color(0xFFA9A594),
        outlineVariant = p.hairline,
        scrim = Color(0xFF000000),
        surfaceBright = Color(0xFFFDFCF7),
        surfaceDim = Color(0xFFDEDBCD),
        surfaceContainerLowest = Color(0xFFFFFFFE),
        surfaceContainerLow = Color(0xFFF8F6ED),
        surfaceContainer = p.groundRaised,
        surfaceContainerHigh = Color(0xFFF0EDE1),
        surfaceContainerHighest = p.groundSunken,
    )
}

/**
 * Corners are deliberately tighter than Material's defaults. This is an instrument, and
 * 28dp pills on data panels read as a consumer toy.
 */
private val TrekShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun ThumbTrekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val context = LocalContext.current
    val motionOk = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f) > 0f
    }

    CompositionLocalProvider(
        LocalTrekPalette provides palette,
        LocalTrekMotion provides motionOk,
    ) {
        MaterialTheme(
            colorScheme = schemeFor(palette),
            typography = ThumbTrekTypography,
            shapes = TrekShapes,
            content = content,
        )
    }
}
