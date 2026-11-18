@file:OptIn(ExperimentalTextApi::class)

package com.thumbtrek.app.ui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.thumbtrek.app.R

// Archivo, one variable family, two widths. Wide (125) for numbers and headings, the way a
// scoreboard and a painted lane number are wide. Normal (100) for everything read as a
// sentence.
private const val WIDTH_WIDE = 125f
private const val WIDTH_NORMAL = 100f

private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.width(width),
    ),
)

/** Display, Title and lane numbers. */
val ArchivoWide = FontFamily(archivo(700, WIDTH_WIDE), archivo(800, WIDTH_WIDE))

/** Headings, body and captions. */
val Archivo = FontFamily(
    archivo(400, WIDTH_NORMAL),
    archivo(500, WIDTH_NORMAL),
    archivo(600, WIDTH_NORMAL),
    archivo(650, WIDTH_NORMAL),
)

private val Heading = FontWeight(650)

/** Tabular lining figures, so stacked distances never jitter as they tick. */
private const val TabularNums = "tnum"

/** Display: the oval's number and other headline figures. */
val TrekDisplay = TextStyle(
    fontFamily = ArchivoWide,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 44.sp,
    lineHeight = 46.sp,
    letterSpacing = (-0.03).em,
    fontFeatureSettings = TabularNums,
)

/** Quiet captions, axis labels and the text beside a figure. */
val TrekCaption = TextStyle(
    fontFamily = Archivo,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
)

/** Distances in a column: lane rows, day totals, leaderboard scores. */
val TrekFigure = TextStyle(
    fontFamily = ArchivoWide,
    fontWeight = FontWeight.Bold,
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = TabularNums,
)

/** The painted number at the left edge of a lane row. */
val TrekLaneNumber = TextStyle(
    fontFamily = ArchivoWide,
    fontWeight = FontWeight.ExtraBold,
    fontSize = 20.sp,
    lineHeight = 22.sp,
    fontFeatureSettings = TabularNums,
)

val ThumbTrekTypography = Typography(
    // Display 44 to 72, weight 800, wide.
    displayLarge = TrekDisplay.copy(fontSize = 52.sp, lineHeight = 54.sp),
    displayMedium = TrekDisplay,
    displaySmall = TrekDisplay.copy(fontSize = 34.sp, lineHeight = 38.sp),
    // Title 24 to 30, weight 700, wide: screen and section headings.
    headlineLarge = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNums,
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNums,
    ),
    headlineSmall = TextStyle(
        fontFamily = ArchivoWide, fontWeight = FontWeight.Bold,
        fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.02).em,
        fontFeatureSettings = TabularNums,
    ),
    // Heading 16 to 17, weight 650, normal width: panel titles.
    titleLarge = TextStyle(
        fontFamily = Archivo, fontWeight = Heading,
        fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.005).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Archivo, fontWeight = Heading,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.005).em,
    ),
    titleSmall = TextStyle(
        fontFamily = Archivo, fontWeight = Heading,
        fontSize = 14.sp, lineHeight = 19.sp,
    ),
    // Body 15 to 16, weight 400.
    bodyLarge = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp,
    ),
    // Caption 12 to 13, weight 500.
    bodySmall = TrekCaption,
    labelLarge = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Archivo, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp,
    ),
)
