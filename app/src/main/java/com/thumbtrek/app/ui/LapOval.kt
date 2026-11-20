package com.thumbtrek.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thumbtrek.app.stats.OvalLane
import com.thumbtrek.app.stats.OvalModel
import com.thumbtrek.app.stats.OvalState
import com.thumbtrek.app.stats.ovalDescription
import com.thumbtrek.app.stats.appLimitCaption
import com.thumbtrek.app.stats.isCleanDay

/** Width over height on a phone (DESIGN.md). */
private const val OVAL_ASPECT = 1.35f

/** Each lane's band as a share of the oval's height. */
private const val LANE_BAND = 0.065f

/** The colour of a lane on the track: volt, warn or danger for the total, series for apps. */
fun laneColor(lane: OvalLane, state: OvalState): Color = when {
    lane.lane == 1 && state == OvalState.OVER -> TrackDanger
    lane.lane == 1 && state == OvalState.NEAR -> TrackWarn
    lane.lane == 1 -> Volt
    else -> TrackSeries[(lane.lane - 2) % TrackSeries.size]
}

/**
 * A stadium outline inset by [inset], starting at the left end of the top straight and
 * running clockwise. That start point is where the finish line is drawn, and it is the
 * same start a stroked SVG rounded rect uses on the web clients.
 */
private fun stadiumPath(size: Size, inset: Float): Path {
    val top = inset
    val bottom = size.height - inset
    val radius = (bottom - top) / 2f
    val left = inset + radius
    val right = size.width - inset - radius
    return Path().apply {
        moveTo(left, top)
        lineTo(right, top)
        arcTo(Rect(right - radius, top, right + radius, bottom), -90f, 180f, false)
        lineTo(left, bottom)
        arcTo(Rect(left - radius, top, left + radius, bottom), 90f, 180f, false)
    }
}

private fun DrawScope.stadium(color: Color, inset: Float, stroke: Stroke? = null) {
    val height = size.height - inset * 2f
    val radius = CornerRadius(height / 2f, height / 2f)
    val topLeft = Offset(inset, inset)
    val box = Size(size.width - inset * 2f, height)
    if (stroke == null) {
        drawRoundRect(color, topLeft, box, radius)
    } else {
        drawRoundRect(color, topLeft, box, radius, style = stroke)
    }
}

/**
 * The lap oval: concentric lanes on track blue, the total outermost. The lanes run in once
 * from the finish line when the first figures land, then follow the values.
 *
 * The drawing is hidden from screen readers and the whole oval is described in one
 * sentence from [model]; every lane also has a text row below it on the screen.
 */
@Composable
fun LapOval(
    model: OvalModel,
    meters: Double,
    modifier: Modifier = Modifier,
) {
    val motion = LocalTrekMotion.current
    val hasProgress = model.lanes.any { it.fraction > 0f }
    val run = remember { Animatable(0f) }
    LaunchedEffect(hasProgress, motion) {
        when {
            !hasProgress -> run.snapTo(0f)
            motion -> run.animateTo(1f, tween(TrekDur.LAP, easing = TrekEase))
            else -> run.snapTo(1f)
        }
    }
    val laneCount = model.lanes.size
    val colors = model.lanes.map { laneColor(it, model.state) }
    val description = ovalDescription(model)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(OVAL_ASPECT)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val bandDp = maxHeight * LANE_BAND
        Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val band = size.height * LANE_BAND
            val hair = 1.5.dp.toPx()

            stadium(TrackBlue, 0f)
            stadium(TrackDeep, band * laneCount)
            val lineStroke = Stroke(width = hair)
            val lineColor = Chalk.copy(alpha = 0.45f)
            for (i in 0..laneCount) {
                stadium(lineColor, if (i == 0) hair / 2f else band * i, lineStroke)
            }

            val progress = Stroke(width = band * 0.56f, cap = StrokeCap.Round)
            val measure = PathMeasure()
            model.lanes.forEachIndexed { index, lane ->
                val shown = lane.fraction * run.value
                if (shown <= 0f) return@forEachIndexed
                measure.setPath(stadiumPath(size, band * (index + 0.5f)), false)
                val segment = Path()
                measure.getSegment(0f, measure.length * shown.coerceAtMost(1f), segment, true)
                drawPath(segment, colors[index], style = progress)
            }

            val finishX = size.height / 2f
            drawLine(Chalk, Offset(finishX, 0f), Offset(finishX, band * laneCount), strokeWidth = 3.dp.toPx())
        }

        // The infield: today's distance and one caption line, kept clear of the lanes.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = bandDp * laneCount + maxHeight * 0.06f)
                .clearAndSetSemantics { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            HeroDistance(meters, color = Chalk, unitColor = Chalk, centered = true)
            Text(
                model.caption,
                style = TrekCaption,
                color = ChalkDim,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** A lane's colour key: a stadium ringed in track blue, so volt still reads on a light page. */
@Composable
fun LaneSwatch(color: Color, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .size(width = 26.dp, height = 12.dp)
            .background(color, CircleShape)
            .border(2.dp, TrackBlue, CircleShape)
            .clearAndSetSemantics { },
    )
}

/**
 * The text twin of a lane: painted lane number, colour key, name, distance. Also the row
 * for ranked lists, which are the only other thing that carries numbers.
 */
@Composable
fun LaneRow(
    number: Int,
    name: String,
    value: String,
    modifier: Modifier = Modifier,
    swatch: Color? = null,
    numberColor: Color = Trek.text,
    valueColor: Color = Trek.text,
    numberWidth: Dp = 30.dp,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            number.toString(),
            modifier = Modifier.widthIn(min = numberWidth),
            style = TrekLaneNumber,
            color = numberColor,
            maxLines = 1,
        )
        if (swatch != null) LaneSwatch(swatch)
        leading?.invoke()
        Text(
            name,
            modifier = Modifier.weight(1f),
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            color = Trek.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(value, style = TrekFigure, color = valueColor, maxLines = 1)
        trailing?.invoke()
    }
}

/**
 * The oval's lanes as rows, divided by lane lines. [limits] maps a lane's name to that
 * app's own limit in metres: such a row reads "640 m of 500 m", and says so in words and
 * in the danger colour once it is over.
 */
@Composable
fun LaneRows(
    model: OvalModel,
    modifier: Modifier = Modifier,
    limits: Map<String, Double> = emptyMap(),
) {
    Column(modifier) {
        model.lanes.forEach { lane ->
            val limit = limits[lane.name].takeIf { lane.lane > 1 && !lane.isOther }
            val over = limit != null && !isCleanDay(lane.meters, limit)
            val value = appLimitCaption(lane.meters, limit)
            LaneLine()
            LaneRow(
                number = lane.lane,
                name = lane.name,
                value = value,
                valueColor = if (over) Trek.danger else Trek.text,
                swatch = laneColor(lane, model.state),
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "Lane ${lane.lane}, ${lane.name}, $value" +
                        if (over) ", over its limit" else ""
                },
            )
        }
        LaneLine()
    }
}

/** A lane line: the 1.5dp divider between rows and sections. */
@Composable
fun LaneLine(modifier: Modifier = Modifier, color: Color = Trek.line) {
    Spacer(modifier.fillMaxWidth().heightIn(min = 1.5.dp, max = 1.5.dp).background(color))
}

/** A small "Premium" chip for a planned feature shown as its ordinary row (DESIGN.md). */
@Composable
fun PremiumChip(modifier: Modifier = Modifier) {
    Text(
        "Premium",
        modifier = modifier.background(Trek.accentWash, CircleShape).padding(horizontal = 12.dp, vertical = 5.dp),
        style = TrekCaption.copy(fontSize = 12.sp),
        color = Trek.text,
        maxLines = 1,
    )
}
