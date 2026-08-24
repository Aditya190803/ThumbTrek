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
 * Space Grotesk carries the numbers and headlines — it has a technical, trail-marker
 * character that plain system sans lacks. One variable-font file covers every weight
 * via [FontVariation]; Android renders the axis at draw time, so there's no per-weight
 * asset to keep in sync.
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

/** Tabular lining figures so stacked distances (e.g. leaderboard columns) don't jitter. */
private const val TabularNums = "tnum"

val ThumbTrekTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1.2).sp,
        fontFeatureSettings = TabularNums,
    ),
    displayMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.8).sp,
        fontFeatureSettings = TabularNums,
    ),
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.4).sp,
        fontFeatureSettings = TabularNums,
    ),
    headlineLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 19.sp, lineHeight = 24.sp,
        fontFeatureSettings = TabularNums,
    ),
    titleLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.15.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Body, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp,
    ),
)
