package com.thumbtrek.app.share

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import androidx.compose.ui.graphics.toArgb
import com.thumbtrek.app.R
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.lapsText
import com.thumbtrek.app.stats.OvalModel
import com.thumbtrek.app.stats.ovalModel
import com.thumbtrek.app.ui.Chalk
import com.thumbtrek.app.ui.ChalkDim
import com.thumbtrek.app.ui.TrackBlue
import com.thumbtrek.app.ui.TrackDeep
import com.thumbtrek.app.ui.laneColor
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

// The card is the track itself (DESIGN.md): track blue ground, chalk text, the lap oval.
private val TRACK = TrackBlue.toArgb()
private val TRACK_DEEP = TrackDeep.toArgb()
private val CHALK = Chalk.toArgb()
private val CHALK_DIM = ChalkDim.toArgb()
private val LANE_LINE = Chalk.copy(alpha = 0.45f).toArgb()

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
    periodLabel: String = "Today",
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
                    "My thumb scrolled ${formatDistance(distanceMeters)}. $comparisonLine",
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share your distance").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }
}

/** A stadium outline, from the left end of the top straight, clockwise. */
private fun stadiumPath(box: RectF): Path {
    val radius = box.height() / 2f
    val left = box.left + radius
    val right = box.right - radius
    return Path().apply {
        moveTo(left, box.top)
        lineTo(right, box.top)
        arcTo(RectF(right - radius, box.top, right + radius, box.bottom), -90f, 180f, false)
        lineTo(left, box.bottom)
        arcTo(RectF(left - radius, box.top, left + radius, box.bottom), 90f, 180f, false)
    }
}

internal fun RectF.inset(by: Float) = RectF(left + by, top + by, right - by, bottom - by)

/**
 * Draws the lap oval into [oval]: infield, lane lines, each lane's progress, and the finish
 * line. Shared by the share card and the home-screen widget, so the two cannot drift.
 * Returns the lane band, which callers need to find the infield.
 */
internal fun drawLapOval(canvas: Canvas, oval: RectF, model: OvalModel): Float {
    val band = oval.height() * 0.065f
    val hairline = (oval.height() / 220f).coerceAtLeast(1f)
    val lanes = model.lanes.size
    canvas.drawRoundRect(oval.inset(band * lanes), oval.height(), oval.height(), fill(TRACK_DEEP))
    val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairline
        color = LANE_LINE
    }
    for (i in 0..lanes) {
        val box = oval.inset(if (i == 0) hairline / 2f else band * i)
        canvas.drawRoundRect(box, box.height() / 2f, box.height() / 2f, line)
    }
    val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = band * 0.56f
        strokeCap = Paint.Cap.ROUND
    }
    val measure = PathMeasure()
    model.lanes.forEachIndexed { index, lane ->
        if (lane.fraction <= 0f) return@forEachIndexed
        measure.setPath(stadiumPath(oval.inset(band * (index + 0.5f))), false)
        val segment = Path()
        measure.getSegment(0f, measure.length * lane.fraction, segment, true)
        progress.color = laneColor(lane, model.state).toArgb()
        canvas.drawPath(segment, progress)
    }
    val finishX = oval.left + oval.height() / 2f
    canvas.drawLine(finishX, oval.top, finishX, oval.top + band * lanes, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHALK
        strokeWidth = hairline * 2f
    })
    return band
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
    canvas.drawColor(TRACK)

    context.getDrawable(R.drawable.trek_mark)?.apply {
        setBounds(64, 70, 164, 130)
        draw(canvas)
    }
    canvas.drawText("ThumbTrek", 188f, 117f, fonts.wide(46f, CHALK, weight = 800))
    canvas.drawText(
        periodLabel, CARD_WIDTH - MARGIN, 114f,
        fonts.text(32f, CHALK_DIM, weight = 500).apply { textAlign = Paint.Align.RIGHT },
    )

    // The lap oval. The share sheet has no limit to hand over, so a lap is the no-limit scale.
    val model = ovalModel(distanceMeters, perApp)
    val oval = RectF(MARGIN, 200f, CARD_WIDTH - MARGIN, 200f + content / 1.5f)
    val band = drawLapOval(canvas, oval, model)
    val lanes = model.lanes.size

    // The infield: the distance, in the wide cut, sized to fit between the lanes.
    val infield = oval.inset(band * lanes)
    val room = infield.width() - infield.height() * 0.5f
    val distance = formatDistance(distanceMeters)
    val figure = fonts.wide(150f, CHALK, weight = 800, spacing = -0.03f).apply { textAlign = Paint.Align.CENTER }
    while (figure.measureText(distance) > room && figure.textSize > 36f) figure.textSize -= 4f
    canvas.drawText(distance, oval.centerX(), oval.centerY() + figure.textSize * 0.22f, figure)
    canvas.drawText(
        lapsText(distanceMeters), oval.centerX(), oval.centerY() + figure.textSize * 0.22f + 58f,
        fonts.text(34f, CHALK_DIM, weight = 500).apply { textAlign = Paint.Align.CENTER },
    )

    // Lane rows for the apps: painted number, colour key, name, distance.
    var y = oval.bottom + 84f
    val rule = Paint().apply {
        color = LANE_LINE
        strokeWidth = 3f
    }
    model.lanes.drop(1).forEach { lane ->
        canvas.drawLine(MARGIN, y - 52f, CARD_WIDTH - MARGIN, y - 52f, rule)
        canvas.drawText(lane.lane.toString(), MARGIN, y, fonts.wide(40f, CHALK, weight = 800))
        canvas.drawRoundRect(RectF(MARGIN + 64f, y - 28f, MARGIN + 116f, y - 6f), 11f, 11f, fill(laneColor(lane, model.state).toArgb()))
        val namePaint = fonts.text(32f, CHALK, weight = 500)
        val valuePaint = fonts.wide(30f, CHALK, weight = 700).apply { textAlign = Paint.Align.RIGHT }
        val value = formatDistance(lane.meters)
        val clipped = TextUtils.ellipsize(lane.name, namePaint, content - 140f - valuePaint.measureText(value) - 36f, TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, MARGIN + 140f, y, namePaint)
        canvas.drawText(value, CARD_WIDTH - MARGIN, y, valuePaint)
        y += 78f
    }
    canvas.drawLine(MARGIN, y - 52f, CARD_WIDTH - MARGIN, y - 52f, rule)

    val comparisonPaint = fonts.text(34f, CHALK, weight = 500)
    val comparisonLayout = StaticLayout.Builder
        .obtain(comparisonLine, 0, comparisonLine.length, comparisonPaint, content.toInt())
        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(6f, 1f).build()
    canvas.withTranslation(MARGIN, y - 20f) { comparisonLayout.draw(this) }

    val footer = buildString {
        append("This week ${formatDistance(weekMeters)}")
        if (streak > 0) append(", $streak day streak")
    }
    canvas.drawText(footer, MARGIN, CARD_HEIGHT - 70f, fonts.text(30f, CHALK_DIM, weight = 500))
    return bitmap
}

private fun writeCard(context: Context, bitmap: Bitmap): Uri {
    val dir = File(context.cacheDir, CARD_DIR).apply { mkdirs() }
    val file = File(dir, CARD_FILE)
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/**
 * Archivo from the same variable file the app uses: the wide cut (width 125) for figures
 * and the brand, the normal cut for sentences. Weight and width are set on the paint, which
 * is how a variable font takes its axes below API 29.
 */
internal class CardFonts(context: Context) {
    private val archivo: Typeface = ResourcesCompat.getFont(context, R.font.archivo)
        ?: Typeface.SANS_SERIF

    private fun paint(size: Float, color: Int, weight: Int, width: Int, spacing: Float) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            letterSpacing = spacing
            typeface = archivo
            fontVariationSettings = "'wght' $weight, 'wdth' $width"
        }

    fun text(size: Float, color: Int, weight: Int = 400, spacing: Float = 0f): TextPaint =
        paint(size, color, weight, 100, spacing)

    fun wide(size: Float, color: Int, weight: Int = 700, spacing: Float = 0f): TextPaint =
        paint(size, color, weight, 125, spacing)
}

private fun fill(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
