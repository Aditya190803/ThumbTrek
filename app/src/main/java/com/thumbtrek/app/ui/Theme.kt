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

// The track carries its own ground, so these five are the same in both themes (DESIGN.md).
/** The lap oval's lanes. */
val TrackBlue = Color(0xFF2340E0)
/** The oval's infield and pressed track surfaces. */
val TrackDeep = Color(0xFF1A2FB0)
/** Lane lines, lane numbers and text on the track. */
val Chalk = Color(0xFFFFFFFF)
/** The total lane, the runner. Only ever a fill on blue or on the dark ground. */
val Volt = Color(0xFFD7FF3A)
/** Text on volt. */
val OnVolt = Color(0xFF0A0F2E)
/** Secondary text on the track: the oval's caption line. */
val ChalkDim = Color(0xFFD5DCFF)

// State colours as they appear on the track. The oval always uses the night set, because
// its ground is track blue in both themes.
val TrackWarn = Color(0xFFFFC83D)
val TrackDanger = Color(0xFFFF5C7A)

/** App lanes on the track, lane 2 first. Volt is the total and is never an app's colour. */
val TrackSeries = listOf(
    Color(0xFF7FD4FF),
    Color(0xFFFF8FB3),
    Color(0xFF5BE3A0),
    Color(0xFFC4A8FF),
    Color(0xFFFFC83D),
    Color(0xFFFFFFFF),
)

/** Series colours for charts drawn on the light page ground. */
val LightSeries = listOf(
    Color(0xFF2340E0),
    Color(0xFF0E8FB8),
    Color(0xFFC4326B),
    Color(0xFF12805A),
    Color(0xFF6B3FC2),
    Color(0xFF8A5C00),
)

/** The DESIGN.md tokens: night meet in dark, day meet in light. */
@Immutable
data class TrekPalette(
    val isDark: Boolean,
    /** Page ground. */
    val bg: Color,
    /** Panels, sheets, rows. */
    val surface: Color,
    /** Pressed states and nested chips. */
    val surface2: Color,
    /** Progress tracks and inputs. */
    val sunken: Color,
    /** Borders and lane dividers. */
    val line: Color,
    /** Row separators inside a panel. */
    val lineSoft: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    /** Primary action and selection. Volt at night, track blue by day. */
    val accent: Color,
    val onAccent: Color,
    /** Selected chip, own leaderboard row. */
    val accentWash: Color,
    /** Under the limit, clean day. */
    val good: Color,
    val goodWash: Color,
    /** Streaks, 80% of the limit. */
    val warn: Color,
    val warnWash: Color,
    /** Over the limit, destructive. */
    val danger: Color,
    val dangerWash: Color,
    /** Browser source, neutral highlights. */
    val info: Color,
    val infoWash: Color,
    /** Gold, silver, bronze for the podium. */
    val medals: List<Color>,
) {
    val track: Color get() = TrackBlue
}

private val DarkPalette = TrekPalette(
    isDark = true,
    bg = Color(0xFF0A0F2E),
    surface = Color(0xFF121A4A),
    surface2 = Color(0xFF1A2460),
    sunken = Color(0xFF070B22),
    line = Color(0xFF26317A),
    lineSoft = Color(0xFF1B2459),
    text = Color(0xFFF5F7FF),
    textDim = Color(0xFFAEB6E0),
    textFaint = Color(0xFF8891C4),
    accent = Color(0xFFD7FF3A),
    onAccent = Color(0xFF0A0F2E),
    accentWash = Color(0xFF323F47),
    good = Color(0xFF5BE3A0),
    goodWash = Color(0xFF12303F),
    warn = Color(0xFFFFC83D),
    warnWash = Color(0xFF2E2A33),
    danger = Color(0xFFFF5C7A),
    dangerWash = Color(0xFF33173F),
    info = Color(0xFF7FD4FF),
    infoWash = Color(0xFF14294F),
    medals = listOf(Color(0xFFFFD34D), Color(0xFFC9D1EE), Color(0xFFE0A070)),
)

private val LightPalette = TrekPalette(
    isDark = false,
    bg = Color(0xFFF3F5FB),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE9EDF8),
    sunken = Color(0xFFE3E8F5),
    line = Color(0xFFD3DAEE),
    lineSoft = Color(0xFFE4E9F6),
    text = Color(0xFF0B1030),
    textDim = Color(0xFF454E7A),
    textFaint = Color(0xFF626B96),
    accent = Color(0xFF2340E0),
    onAccent = Color(0xFFFFFFFF),
    accentWash = Color(0xFFE3E8FD),
    good = Color(0xFF12754A),
    goodWash = Color(0xFFE0F3EA),
    warn = Color(0xFF8A5800),
    warnWash = Color(0xFFFBEFD6),
    danger = Color(0xFFB4233A),
    dangerWash = Color(0xFFFBE6E9),
    info = Color(0xFF0E6E94),
    infoWash = Color(0xFFE0F1F8),
    medals = listOf(Color(0xFF8A6200), Color(0xFF5B648C), Color(0xFF8A5230)),
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

/** Ease-out for state changes. Fast commit, long settle, no overshoot. */
val TrekEase: Easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

object TrekDur {
    /** Ordinary state changes: 160 to 240 ms. */
    const val SMALL = 160
    const val MEDIUM = 200
    const val LARGE = 240

    /** The oval's one-time run-in, per lane. */
    const val LAP = 700
}

/** Collapses a duration to zero when the user has asked for no animation. */
@Composable
fun trekDuration(millis: Int): Int = if (LocalTrekMotion.current) millis else 0

private fun schemeFor(p: TrekPalette) = if (p.isDark) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentWash,
        onPrimaryContainer = p.text,
        secondary = p.info,
        onSecondary = p.bg,
        secondaryContainer = p.infoWash,
        onSecondaryContainer = p.text,
        tertiary = p.warn,
        onTertiary = p.bg,
        tertiaryContainer = p.warnWash,
        onTertiaryContainer = p.text,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.sunken,
        onSurfaceVariant = p.textDim,
        // No tonal elevation anywhere: a transparent tint keeps every surface flat.
        surfaceTint = Color.Transparent,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        inversePrimary = TrackBlue,
        error = p.danger,
        onError = p.bg,
        errorContainer = p.dangerWash,
        onErrorContainer = p.text,
        outline = p.line,
        outlineVariant = p.lineSoft,
        scrim = Color(0xFF000000),
        // Dialogs, sheets, switches and menus read these. Left at their defaults they
        // fall back to Material's baseline neutrals instead of the track palette.
        surfaceBright = p.surface2,
        surfaceDim = p.sunken,
        surfaceContainerLowest = p.sunken,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface2,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentWash,
        onPrimaryContainer = p.text,
        secondary = p.info,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = p.infoWash,
        onSecondaryContainer = p.text,
        tertiary = p.warn,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = p.warnWash,
        onTertiaryContainer = p.text,
        background = p.bg,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.sunken,
        onSurfaceVariant = p.textDim,
        surfaceTint = Color.Transparent,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        inversePrimary = Color(0xFFAEB6E0),
        error = p.danger,
        onError = Color(0xFFFFFFFF),
        errorContainer = p.dangerWash,
        onErrorContainer = p.text,
        outline = p.line,
        outlineVariant = p.lineSoft,
        scrim = Color(0xFF000000),
        surfaceBright = p.surface,
        surfaceDim = p.sunken,
        surfaceContainerLowest = p.surface,
        surfaceContainerLow = p.surface,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.surface,
        surfaceContainerHighest = p.surface2,
    )
}

/** Panels and sheets take 20dp, inputs 12dp. Buttons and chips are stadiums, set per component. */
private val TrekShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
