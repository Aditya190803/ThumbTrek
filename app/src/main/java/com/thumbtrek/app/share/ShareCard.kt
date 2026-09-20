package com.thumbtrek.app.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import androidx.compose.ui.graphics.toArgb
import com.thumbtrek.app.R
import com.thumbtrek.app.stats.formatDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1350
private const val MARGIN = 64f
private const val CARD_DIR = "share"
private const val CARD_FILE = "thumbtrek-card.png"
private const val MAX_APP_ROWS = 3

private val GROUND = 0xFFF3F1E8.toInt()
private val SURFACE = 0xFFFBFAF4.toInt()
private val MOSS = 0xFF1E6B3E.toInt()
private val MOSS_WASH = 0xFFDFEEE2.toInt()
private val INK = 0xFF171A10.toInt()
private val MUTED = 0xFF5A6151.toInt()
private val TRACK = 0xFFE6E3D6.toInt()

/** Rendering the share bitmap is too heavy for a click handler, so it hops off the main thread. */
private val cardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * The shareable stat card. Drawn with plain [Canvas] (no composition needed), written to the
 * cache dir and handed to the system share sheet.
 *
 * [perApp] pairs an already-resolved display label with metres, in any order. Callers
 * resolve names themselves so this file never needs a PackageManager lookup.
 */
fun shareTrekCard(
    context: Context,
    distanceMeters: Double,
    comparisonLine: String,
    streak: Int,
    perApp: List<Pair<String, Double>>,
    weekMeters: Double = 0.0,
    periodLabel: String = "Today's trek",
) {
    val appContext = context.applicationContext
    cardScope.launch {
        val uri = runCatching {
            val bitmap = renderCard(
                appContext, periodLabel, distanceMeters, comparisonLine, streak,
                perApp, weekMeters,
            )
            writeCard(appContext, bitmap)
        }.getOrNull() ?: return@launch

        withContext(Dispatchers.Main) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "My thumb trekked ${formatDistance(distanceMeters)}. $comparisonLine",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share your trek").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}

private fun renderCard(
    context: Context,
    periodLabel: String,
    distanceMeters: Double,
    comparisonLine: String,
    streak: Int,
    perApp: List<Pair<String, Double>>,
    weekMeters: Double,
): Bitmap {
    val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val content = CARD_WIDTH - 2 * MARGIN
    val fonts = CardFonts(context)
    canvas.drawColor(GROUND)
    context.getDrawable(R.drawable.trek_mark)?.apply {
        setBounds(64, 64, 148, 148)
        draw(canvas)
    }
    canvas.drawText("ThumbTrek", 172f, 122f, fonts.text(44f, INK, fonts.body, weight = 700))

    canvas.drawRoundRect(RectF(MARGIN, 210f, CARD_WIDTH - MARGIN, 640f), 52f, 52f, fill(MOSS_WASH))
    canvas.drawText(periodLabel, MARGIN + 48f, 288f, fonts.text(32f, MOSS, fonts.body, weight = 600))
    val distance = formatDistance(distanceMeters)
    val figure = fonts.text(150f, INK, fonts.body, weight = 700, spacing = -0.04f)
    while (figure.measureText(distance) > content - 96f && figure.textSize > 36f) figure.textSize -= 4f
    canvas.drawText(distance, MARGIN + 48f, 450f, figure)
    val comparisonPaint = fonts.text(34f, MUTED, fonts.body, weight = 500)
    val comparisonLayout = StaticLayout.Builder
        .obtain(comparisonLine, 0, comparisonLine.length, comparisonPaint, (content - 96f).toInt())
        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6f, 1f).build()
    canvas.withTranslation(MARGIN + 48f, 504f) { comparisonLayout.draw(this) }

    canvas.drawRoundRect(RectF(MARGIN, 672f, CARD_WIDTH - MARGIN, 824f), 36f, 36f, fill(SURFACE))
    canvas.drawText("This week", MARGIN + 36f, 724f, fonts.text(27f, MUTED, fonts.body))
    canvas.drawText(formatDistance(weekMeters), MARGIN + 36f, 786f, fonts.text(45f, INK, fonts.body, weight = 700))
    canvas.drawText("Your streak", 590f, 724f, fonts.text(27f, MUTED, fonts.body))
    canvas.drawText(if (streak > 0) "$streak days" else "A fresh start", 590f, 786f, fonts.text(42f, MOSS, fonts.body, weight = 700))

    val rows = perApp.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(MAX_APP_ROWS)
    canvas.drawText("Your apps", MARGIN, 900f, fonts.text(32f, INK, fonts.body, weight = 700))
    val busiest = rows.firstOrNull()?.second ?: 1.0
    rows.forEachIndexed { index, (label, meters) ->
        val y = 962f + index * 94f
        val key = com.thumbtrek.app.data.TRACKED_APPS.entries.firstOrNull { it.value == label }?.key ?: label
        val color = com.thumbtrek.app.ui.chartColorFor(key, false).toArgb()
        val namePaint = fonts.text(30f, INK, fonts.body, weight = 500)
        val valuePaint = fonts.text(30f, MUTED, fonts.body, weight = 600).apply { textAlign = Paint.Align.RIGHT }
        val value = formatDistance(meters)
        val clipped = TextUtils.ellipsize(label, namePaint, content - valuePaint.measureText(value) - 36f, TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, MARGIN, y, namePaint)
        canvas.drawText(value, CARD_WIDTH - MARGIN, y, valuePaint)
        canvas.drawRoundRect(RectF(MARGIN, y + 20f, CARD_WIDTH - MARGIN, y + 32f), 6f, 6f, fill(TRACK))
        canvas.drawRoundRect(RectF(MARGIN, y + 20f, MARGIN + (content * meters / busiest).toFloat().coerceAtLeast(12f), y + 32f), 6f, 6f, fill(color))
    }
    if (rows.isEmpty()) canvas.drawText("A fresh start. A little perspective.", MARGIN, 974f, fonts.text(30f, MUTED, fonts.body))
    canvas.drawText("A little perspective on your screen time.", MARGIN, 1280f, fonts.text(28f, MUTED, fonts.body))
    return bitmap
}

private fun writeCard(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, CARD_DIR).apply { mkdirs() }
    val file = File(dir, CARD_FILE)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** The same Manrope family as the app, with native variable-weight support. */
private class CardFonts(context: Context) {
    val body: Typeface = ResourcesCompat.getFont(context, R.font.manrope)
        ?: Typeface.SANS_SERIF

    fun text(
        size: Float,
        color: Int,
        family: Typeface,
        weight: Int = 400,
        spacing: Float = 0f,
    ): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        letterSpacing = spacing
        typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(family, weight, false)
        } else {
            family
        }
    }
}

private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
