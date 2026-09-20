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

// One friendly family keeps numbers, labels and controls visually related.
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

/** Large, tabular distance figures, fitted to available width by HeroDistance. */
val TrekHero = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.Bold,
    fontSize = 76.sp,
    lineHeight = 82.sp,
    letterSpacing = (-2.6).sp,
    fontFeatureSettings = TabularNums,
)

/** Same face, stepped down for long numbers so the hero never wraps or clips. */
val TrekHeroTight = TrekHero.copy(
    fontSize = 48.sp,
    lineHeight = 54.sp,
    letterSpacing = (-2.2).sp,
)

/** The unit ("km", "m") set as a separate, quieter mark beside the figure. */
val TrekUnit = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.Medium,
    fontSize = 22.sp,
    lineHeight = 24.sp,
    letterSpacing = (-0.2).sp,
)

/** Quiet captions and chart labels. */
val TrekOverline = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.SemiBold,
    fontSize = 12.sp,
    lineHeight = 17.sp,
    letterSpacing = 0.2.sp,
)

/** Figures in a column: ranks, day totals, leaderboard scores. */
val TrekFigure = TextStyle(
    fontFamily = Body,
    fontWeight = FontWeight.Medium,
    fontSize = 16.sp,
    lineHeight = 20.sp,
    letterSpacing = (-0.1).sp,
    fontFeatureSettings = TabularNums,
)

val ThumbTrekTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, lineHeight = 48.sp, letterSpacing = (-1.6).sp,
        fontFeatureSettings = TabularNums,
    ),
    displayMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1.1).sp,
        fontFeatureSettings = TabularNums,
    ),
    displaySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.7).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.7).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 21.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 23.sp, letterSpacing = (-0.2).sp,
        fontFeatureSettings = TabularNums,
    ),
    titleLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp,
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
