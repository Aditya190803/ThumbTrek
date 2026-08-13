package com.thumbtrek.app.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.withTranslation
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.stats.formatDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1920
private const val MARGIN = 88f
private const val CARD_DIR = "share"
private const val CARD_FILE = "thumbtrek-card.png"
private const val MAX_APP_ROWS = 4

private val BG_TOP = 0xFF16201B.toInt()
private val BG_BOTTOM = 0xFF0A100D.toInt()
private val GLOW = 0x2E7BD88A.toInt()
private val ACCENT = 0xFF7BD88A.toInt()
private val TEXT = 0xFFE3EAE5.toInt()
private val MUTED = 0xFFB7C6BC.toInt()
private val SURFACE = 0xFF223028.toInt()

/** Rendering an 1080×1920 bitmap is too heavy for the click handler, so it hops off the main thread. */
private val cardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * PRD Roadmap v1.2: shareable stat cards. Draws the card with plain [Canvas] (no composition
 * needed), drops the PNG in the cache dir and hands it to the system share sheet.
 *
 * [perApp] maps app package name — or an already-resolved label — to meters, in any order.
 */
fun shareTrekCard(
    context: Context,
    distanceMeters: Double,
    comparisonLine: String,
    streak: Int,
    perApp: List<Pair<String, Double>>,
    periodLabel: String = "Today's trek",
) {
    val appContext = context.applicationContext
    cardScope.launch {
        val uri = runCatching {
            val bitmap = renderCard(periodLabel, distanceMeters, comparisonLine, streak, perApp)
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
    periodLabel: String,
    distanceMeters: Double,
    comparisonLine: String,
    streak: Int,
    perApp: List<Pair<String, Double>>,
): Bitmap {
    val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val content = CARD_WIDTH - 2 * MARGIN

    canvas.drawPaint(
        Paint().apply {
            shader = LinearGradient(
                0f, 0f, CARD_WIDTH.toFloat(), CARD_HEIGHT.toFloat(),
                BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP,
            )
        },
    )
    val glowX = CARD_WIDTH * 0.86f
    val glowY = CARD_HEIGHT * 0.07f
    canvas.drawCircle(
        glowX, glowY, 460f,
        Paint().apply {
            isAntiAlias = true
            shader = RadialGradient(glowX, glowY, 460f, GLOW, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        },
    )

    canvas.drawText("THUMBTREK", MARGIN, 150f, textPaint(44f, ACCENT, bold = true, spacing = 0.28f))
    canvas.drawText(
        periodLabel.uppercase(Locale.US), MARGIN, 380f,
        textPaint(42f, MUTED, spacing = 0.16f),
    )

    val distance = formatDistance(distanceMeters)
    val distancePaint = textPaint(200f, TEXT, black = true)
    shrinkToFit(distancePaint, distance, content, minSize = 96f)

    var y = 410f - distancePaint.fontMetrics.ascent
    canvas.drawText(distance, MARGIN, y, distancePaint)
    y += distancePaint.fontMetrics.descent + 20f

    val comparisonPaint = textPaint(50f, MUTED)
    val comparisonLayout = StaticLayout.Builder
        .obtain(comparisonLine, 0, comparisonLine.length, comparisonPaint, content.toInt())
        .setLineSpacing(6f, 1.05f)
        .setMaxLines(3)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    canvas.withTranslation(MARGIN, y) { comparisonLayout.draw(this) }
    y += comparisonLayout.height + 44f

    val streakText = when {
        streak <= 0 -> "No streak yet"
        streak == 1 -> "1 day trek streak"
        else -> "$streak day trek streak"
    }
    val streakPaint = textPaint(46f, ACCENT, bold = true)
    val pillHeight = 104f
    val pillWidth = (streakPaint.measureText(streakText) + 88f).coerceAtMost(content)
    canvas.drawRoundRect(
        RectF(MARGIN, y, MARGIN + pillWidth, y + pillHeight),
        pillHeight / 2, pillHeight / 2, fill(SURFACE),
    )
    canvas.drawText(
        streakText,
        MARGIN + 44f,
        y + pillHeight / 2 - (streakPaint.fontMetrics.ascent + streakPaint.fontMetrics.descent) / 2,
        streakPaint,
    )
    y += pillHeight + 64f

    val rows = perApp.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(MAX_APP_ROWS)
    if (rows.isNotEmpty()) {
        canvas.drawText("WHERE IT WENT", MARGIN, y + 34f, textPaint(38f, MUTED, spacing = 0.18f))
        y += 90f

        val namePaint = textPaint(44f, TEXT)
        val valuePaint = textPaint(44f, MUTED)
        val busiest = rows.first().second
        rows.forEach { (key, meters) ->
            val value = formatDistance(meters)
            val valueWidth = valuePaint.measureText(value)
            val label = TextUtils.ellipsize(
                appName(key), namePaint, content - valueWidth - 32f, TextUtils.TruncateAt.END,
            )
            val baseline = y - namePaint.fontMetrics.ascent
            canvas.drawText(label, 0, label.length, MARGIN, baseline, namePaint)
            canvas.drawText(value, MARGIN + content - valueWidth, baseline, valuePaint)

            val barTop = baseline + namePaint.fontMetrics.descent + 16f
            canvas.drawRoundRect(RectF(MARGIN, barTop, MARGIN + content, barTop + 16f), 8f, 8f, fill(SURFACE))
            val barWidth = (content * (meters / busiest)).toFloat().coerceAtLeast(24f)
            canvas.drawRoundRect(RectF(MARGIN, barTop, MARGIN + barWidth, barTop + 16f), 8f, 8f, fill(ACCENT))
            y = barTop + 60f
        }
    }

    val ruleY = CARD_HEIGHT - 196f
    canvas.drawRect(MARGIN, ruleY, MARGIN + content, ruleY + 2f, fill(SURFACE))
    canvas.drawText("Strava for scrolling.", MARGIN, CARD_HEIGHT - 116f, textPaint(38f, MUTED))

    return bitmap
}

private fun writeCard(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, CARD_DIR).apply { mkdirs() }
    val file = File(dir, CARD_FILE)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun textPaint(
    size: Float,
    color: Int,
    bold: Boolean = false,
    black: Boolean = false,
    spacing: Float = 0f,
): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = size
    this.color = color
    letterSpacing = spacing
    typeface = when {
        black -> Typeface.create("sans-serif-black", Typeface.BOLD)
        bold -> Typeface.create("sans-serif-medium", Typeface.BOLD)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
}

private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

/** Keeps a headline on one line whatever the number turns out to be. */
private fun shrinkToFit(paint: Paint, text: String, maxWidth: Float, minSize: Float) {
    while (paint.measureText(text) > maxWidth && paint.textSize > minSize) {
        paint.textSize -= 4f
    }
}
