@file:OptIn(ExperimentalTextApi::class)

package com.thumbtrek.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.thumbtrek.app.R

/**
 * Space Grotesk carries the numbers and headlines: it has a technical, trail-marker
 * character that plain system sans lacks. One variable-font file covers every weight via
 * [FontVariation]; Android renders the axis at draw time, so there is no per-weight asset
 * to keep in sync.
 */
private fun display(weight: Int) = Font(
    R.font.space_grotesk,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Display = FontFamily(
    display(400), display(500), display(600), display(700),
)

private fun body(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

private val Body = FontFamily(
    body(400), body(500), body(600), body(700),
)

/** Tabular lining figures, so stacked distances never jitter as they tick. */
private const val TabularNums = "tnum"

/**
 * The hero readout. Set far larger than any Material slot on purpose: on the dashboard the
 * distance is not "a stat", it is the entire reason the screen exists, and the jump from
 * here to the next step down ([ThumbTrekTypography].headlineLarge, 26sp) is what makes the
 * hierarchy legible in a glance from across a room.
 */
val TrekHero = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Bold,
    fontSize = 76.sp,
    lineHeight = 78.sp,
    letterSpacing = (-3.4).sp,
    fontFeatureSettings = TabularNums,
)

/** Same face, stepped down for long numbers so the hero never wraps or clips. */
val TrekHeroTight = TrekHero.copy(
    fontSize = 58.sp,
    lineHeight = 60.sp,
    letterSpacing = (-2.2).sp,
)

/** The unit ("km", "m") set as a separate, quieter mark beside the figure. */
val TrekUnit = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.2).sp,
)

/** Section overlines and axis captions. Wide tracking is what makes them read as survey marks. */
val TrekOverline = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.7.sp,
)

/** Figures in a column: ranks, day totals, leaderboard scores. */
val TrekFigure = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.1).sp,
    fontFeatureSettings = TabularNums,
)

val ThumbTrekTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.6).sp,
        fontFeatureSettings = TabularNums,
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp,
        fontFeatureSettings = TabularNums,
    ),
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.2).sp,
        fontFeatureSettings = TabularNums,
    ),
    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 17.sp, lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    // 16/25 at ~62 characters per line on a phone keeps body inside the readable band.
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 25.sp, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp,
    ),
)
