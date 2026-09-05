package com.thumbtrek.app.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import com.thumbtrek.app.R
import com.thumbtrek.app.stats.formatDistance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val CARD_WIDTH = 1080
private const val CARD_HEIGHT = 1920
private const val MARGIN = 88f
private const val CARD_DIR = "share"
private const val CARD_FILE = "thumbtrek-card.png"
private const val MAX_APP_ROWS = 3

// The card always wears the dark field-survey look, whatever theme the app is in. A shared
// image is its own artefact: it needs one recognisable identity, not the sharer's setting.
private val BG_TOP = 0xFF12160F.toInt()
private val BG_BOTTOM = 0xFF070906.toInt()
private val CONTOUR = 0xFF1B2114.toInt()
private val MOSS = 0xFF8BE6A0.toInt()
private val INK = 0xFFEDF1E6.toInt()
private val MUTED = 0xFFA9B39C.toInt()
private val FAINT = 0xFF79836D.toInt()
private val TRACK = 0xFF191E12.toInt()
private val HAIRLINE = 0xFF262C1D.toInt()
private val AMBER = 0xFFF2B455.toInt()

/** Same categorical ramp as the in-app charts, so a shared card matches the screenshot. */
private val SERIES = intArrayOf(
    0xFF8BE6A0.toInt(),
    0xFF77C4E8.toInt(),
    0xFFE9C063.toInt(),
    0xFFE79BB4.toInt(),
    0xFFB6A2F0.toInt(),
    0xFF6FD3C4.toInt(),
)

// The dial, matching the app's hero: an open-bottom instrument, not a closed donut.
private const val DIAL_START = 130f
private const val DIAL_SWEEP = 280f
private const val DIAL_TICKS = 12

/** Rendering a 1080x1920 bitmap is too heavy for a click handler, so it hops off the main thread. */
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

    // ---- ground -----------------------------------------------------------------------
    canvas.drawPaint(
        Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, CARD_HEIGHT.toFloat(),
                BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP,
            )
        },
    )
    drawContours(canvas)

    // ---- top rail ---------------------------------------------------------------------
    canvas.drawText(
        "THUMBTREK", MARGIN, 152f,
        fonts.text(38f, MOSS, fonts.display, weight = 700, spacing = 0.3f),
    )
    canvas.drawText(
        periodLabel.uppercase(Locale.US),
        MARGIN + content,
        152f,
        fonts.text(30f, FAINT, fonts.body, weight = 600, spacing = 0.2f).apply {
            textAlign = Paint.Align.RIGHT
        },
    )
    canvas.drawRect(MARGIN, 186f, MARGIN + content, 188f, fill(HAIRLINE))

    // ---- dial -------------------------------------------------------------------------
    val cx = CARD_WIDTH / 2f
    val cy = 690f
    val radius = 330f
    val stroke = 30f
    drawDial(canvas, cx, cy, radius, stroke, perApp)

    // Hero figure and unit, set as two marks like the app's readout.
    val distance = formatDistance(distanceMeters)
    val cut = distance.lastIndexOf(' ')
    val figure = if (cut < 0) distance else distance.substring(0, cut)
    val unit = if (cut < 0) "" else distance.substring(cut + 1)

    val figurePaint = fonts.text(184f, INK, fonts.display, weight = 700, spacing = -0.04f)
    val unitPaint = fonts.text(56f, MUTED, fonts.display, weight = 500)
    val inner = (radius - stroke) * 2f - 56f
    fitHero(figurePaint, unitPaint, figure, unit, inner)

    val figureWidth = figurePaint.measureText(figure)
    val unitWidth = if (unit.isEmpty()) 0f else unitPaint.measureText(unit) + 18f
    val heroLeft = cx - (figureWidth + unitWidth) / 2f
    val heroBaseline = cy + 56f
    canvas.drawText(figure, heroLeft, heroBaseline, figurePaint)
    if (unit.isNotEmpty()) {
        canvas.drawText(unit, heroLeft + figureWidth + 18f, heroBaseline, unitPaint)
    }

    val overline = fonts.text(30f, FAINT, fonts.body, weight = 600, spacing = 0.22f).apply {
        textAlign = Paint.Align.CENTER
    }
    canvas.drawText("DISTANCE SCROLLED", cx, cy - 118f, overline)

    // Streak sits in the break the dial leaves open at the bottom, exactly as on screen.
    drawStreakChip(canvas, fonts, cx, cy + radius - 6f, streak)

    // ---- comparison -------------------------------------------------------------------
    val comparisonPaint = fonts.text(46f, MUTED, fonts.body, weight = 500)
    val comparisonLayout = StaticLayout.Builder
        .obtain(comparisonLine, 0, comparisonLine.length, comparisonPaint, content.toInt())
        .setLineSpacing(8f, 1.05f)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setMaxLines(2)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()
    var y = cy + radius + 96f
    canvas.withTranslation(MARGIN, y) { comparisonLayout.draw(this) }
    y += comparisonLayout.height + 88f

    // ---- split ------------------------------------------------------------------------
    val rows = perApp.filter { it.second > 0.0 }.sortedByDescending { it.second }.take(MAX_APP_ROWS)
    if (rows.isNotEmpty()) {
        canvas.drawText(
            "WHERE IT WENT", MARGIN, y,
            fonts.text(28f, FAINT, fonts.body, weight = 600, spacing = 0.22f),
        )
        y += 54f

        val namePaint = fonts.text(40f, INK, fonts.body, weight = 500)
        val valuePaint = fonts.text(40f, MUTED, fonts.display, weight = 500).apply {
            textAlign = Paint.Align.RIGHT
        }
        val busiest = rows.first().second
        rows.forEachIndexed { index, (label, meters) ->
            val value = formatDistance(meters)
            val valueWidth = valuePaint.measureText(value)
            val clipped = TextUtils.ellipsize(
                label, namePaint, content - valueWidth - 40f, TextUtils.TruncateAt.END,
            )
            val baseline = y - namePaint.fontMetrics.ascent
            canvas.drawText(clipped, 0, clipped.length, MARGIN, baseline, namePaint)
            canvas.drawText(value, MARGIN + content, baseline, valuePaint)

            val barTop = baseline + namePaint.fontMetrics.descent + 18f
            canvas.drawRoundRect(
                RectF(MARGIN, barTop, MARGIN + content, barTop + 14f), 7f, 7f, fill(TRACK),
            )
            val barWidth = (content * (meters / busiest)).toFloat().coerceAtLeast(28f)
            canvas.drawRoundRect(
                RectF(MARGIN, barTop, MARGIN + barWidth, barTop + 14f), 7f, 7f,
                fill(SERIES[index % SERIES.size]),
            )
            y = barTop + 78f
        }
    }

    // ---- footer -----------------------------------------------------------------------
    val ruleY = CARD_HEIGHT - 188f
    canvas.drawRect(MARGIN, ruleY, MARGIN + content, ruleY + 2f, fill(HAIRLINE))
    val footerBaseline = CARD_HEIGHT - 108f
    canvas.drawText(
        "Strava for scrolling.", MARGIN, footerBaseline,
        fonts.text(34f, FAINT, fonts.body, weight = 500),
    )
    if (weekMeters > 0.0) {
        canvas.drawText(
            "This week ${formatDistance(weekMeters)}",
            MARGIN + content,
            footerBaseline,
            fonts.text(34f, MUTED, fonts.display, weight = 500).apply {
                textAlign = Paint.Align.RIGHT
            },
        )
    }

    return bitmap
}

/** Nested contour rings, the same survey texture the dashboard hero sits on. */
private fun drawContours(canvas: Canvas) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = CONTOUR
    }
    val cx = CARD_WIDTH * 0.86f
    val cy = CARD_HEIGHT * 0.08f
    val step = 96f
    for (i in 1..14) {
        val r = step * i
        val drift = i * step * 0.14f
        canvas.drawOval(
            RectF(cx - r * 1.3f - drift, cy - r + drift * 0.4f, cx + r * 1.3f - drift, cy + r + drift * 0.4f),
            paint,
        )
    }
}

private fun drawDial(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    stroke: Float,
    perApp: List<Pair<String, Double>>,
) {
    val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

    val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HAIRLINE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }
    val tickInner = radius + stroke / 2f + 20f
    for (i in 0..DIAL_TICKS) {
        val angle = (DIAL_START + DIAL_SWEEP * i / DIAL_TICKS) * PI.toFloat() / 180f
        val dx = cos(angle)
        val dy = sin(angle)
        val len = if (i % 3 == 0) 22f else 12f
        canvas.drawLine(
            cx + dx * tickInner, cy + dy * tickInner,
            cx + dx * (tickInner + len), cy + dy * (tickInner + len),
            tickPaint,
        )
    }

    val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }

    val rows = perApp.filter { it.second > 0.0 }.sortedByDescending { it.second }
    val total = rows.sumOf { it.second }
    if (rows.isEmpty() || total <= 0.0) {
        arcPaint.color = HAIRLINE
        arcPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(box, DIAL_START, DIAL_SWEEP, false, arcPaint)
        return
    }

    arcPaint.color = TRACK
    arcPaint.strokeCap = Paint.Cap.ROUND
    canvas.drawArc(box, DIAL_START, DIAL_SWEEP, false, arcPaint)

    arcPaint.strokeCap = if (rows.size == 1) Paint.Cap.ROUND else Paint.Cap.BUTT
    val gap = if (rows.size > 1) 2.5f else 0f
    var start = DIAL_START
    rows.forEachIndexed { index, (_, meters) ->
        val full = (meters / total * DIAL_SWEEP).toFloat()
        arcPaint.color = SERIES[index % SERIES.size]
        canvas.drawArc(box, start + gap / 2f, (full - gap).coerceAtLeast(1f), false, arcPaint)
        start += full
    }
}

private fun drawStreakChip(
    canvas: Canvas,
    fonts: CardFonts,
    cx: Float,
    cy: Float,
    streak: Int,
) {
    val live = streak > 0
    val text = if (live) "$streak day streak" else "No streak yet"
    val paint = fonts.text(34f, if (live) AMBER else FAINT, fonts.body, weight = 600)
    val textWidth = paint.measureText(text)
    val dot = 14f
    val padH = 34f
    val width = textWidth + padH * 2 + dot + 16f
    val height = 76f
    val box = RectF(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)

    canvas.drawRoundRect(box, height / 2f, height / 2f, fill(if (live) 0xFF2A2113.toInt() else TRACK))
    canvas.drawRoundRect(
        box, height / 2f, height / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = if (live) AMBER else HAIRLINE
            alpha = 110
        },
    )
    canvas.drawCircle(box.left + padH + dot / 2f, cy, dot / 2f, fill(if (live) AMBER else FAINT))
    canvas.drawText(
        text,
        box.left + padH + dot + 16f,
        cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f,
        paint,
    )
}

private fun writeCard(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, CARD_DIR).apply { mkdirs() }
    val file = File(dir, CARD_FILE)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * The card uses the app's own faces, so a posted card and a screenshot of the dashboard
 * read as the same product. Both files are variable fonts; picking a weight off a variable
 * face needs API 28, so older devices fall back to the regular cut rather than a fake bold.
 */
private class CardFonts(context: Context) {
    val display: Typeface = ResourcesCompat.getFont(context, R.font.space_grotesk)
        ?: Typeface.SANS_SERIF
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

/** Scales the hero figure and its unit together until the pair fits inside the dial. */
private fun fitHero(
    figurePaint: Paint,
    unitPaint: Paint,
    figure: String,
    unit: String,
    maxWidth: Float,
) {
    fun width() = figurePaint.measureText(figure) +
        if (unit.isEmpty()) 0f else unitPaint.measureText(unit) + 18f

    while (width() > maxWidth && figurePaint.textSize > 90f) {
        figurePaint.textSize -= 4f
        unitPaint.textSize -= 1.2f
    }
}
