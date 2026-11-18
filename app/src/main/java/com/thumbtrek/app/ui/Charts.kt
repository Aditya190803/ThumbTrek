package com.thumbtrek.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.TRACKED_APPS

/** One app in the daily summary. The package key keeps its color stable. */
data class ChartSlice(
    val key: String,
    val label: String,
    val value: Float,
)

/** One column of [HistoryBars]; [label] is the axis caption underneath it. */
data class ChartBar(val label: String, val value: Float)

/** One line of [TrendLines]. [key] should be the package name: it picks the colour. */
data class ChartSeries(val key: String, val label: String, val values: List<Float>)

/**
 * Deterministic series colour: the same [key] always maps to the same hue, so an app keeps
 * its colour across every chart, so the split list and the trend agree. The series come
 * from the palette (DESIGN.md); volt is reserved for the total and is never an app's colour.
 * Tracked apps take their slot in [TRACKED_APPS] order; anything else hashes in.
 */
@Composable
fun chartColor(key: String): Color = chartColorFor(key, Trek.isDark)

fun chartColorFor(key: String, dark: Boolean): Color {
    val palette = if (dark) TrackSeries else LightSeries
    val index = TRACKED_APPS.keys.indexOf(key).takeIf { it >= 0 }
        ?: (key.hashCode() and Int.MAX_VALUE)
    return palette[index % palette.size]
}

/**
 * PRD 5.3 history bars. One animation drives the whole set with a per-column offset, so a
 * long range still costs a single running animation instead of one per bar.
 *
 * @param bars ordered oldest to newest.
 * @param highlight index drawn in the accent colour, normally today. -1 for none.
 * @param selected index the user tapped, drawn ink-dark with the rest dimmed. -1 for none.
 * @param onSelect tap handler; null renders a static chart with no touch handling.
 * @param guide optional reference value (the daily limit) drawn as a dashed line. Skipped
 *   when it sits far above every bar, where it would only squash the data into a sliver.
 */
@Composable
fun HistoryBars(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    highlight: Int = -1,
    barColor: Color = Trek.info,
    accentColor: Color = Trek.accent,
    height: Dp = 132.dp,
    maxInlineLabels: Int = 8,
    selected: Int = -1,
    onSelect: ((Int) -> Unit)? = null,
    guide: Float? = null,
    guideColor: Color = Trek.danger,
) {
    if (bars.isEmpty()) return
    val tallest = bars.maxOf { it.value }
    val shownGuide = guide?.takeIf { it > 0f && tallest > 0f && it <= tallest * 2f }
    val max = maxOf(tallest, shownGuide ?: 0f).coerceAtLeast(1f)
    val track = Trek.sunken
    // Palette reads are @Composable, so resolve them before the draw scope below.
    val selectedColor = Trek.accent
    val motion = LocalTrekMotion.current
    val hasSelection = selected in bars.indices

    // Animate on data arrival, not on every tracking update.
    val hasData = bars.any { it.value > 0f }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(bars.size, hasData, motion) {
        grow.snapTo(0f)
        if (hasData && motion) {
            grow.animateTo(1f, tween(TrekDur.LARGE + bars.size * 18, easing = TrekEase))
        } else {
            grow.snapTo(1f)
        }
    }

    Column(
        modifier = modifier.semantics {
            if (onSelect != null) {
                contentDescription = "History chart. Select a period to see its distance."
                customActions = bars.mapIndexed { index, bar ->
                    CustomAccessibilityAction("${bar.label}: ${com.thumbtrek.app.stats.formatDistance(bar.value.toDouble())}") {
                        onSelect(index)
                        true
                    }
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { }
                .pointerInput(bars.size, onSelect) {
                    if (onSelect == null) return@pointerInput
                    detectTapGestures { offset ->
                        // Slots tile the width edge to edge, so x alone picks the column.
                        val slot = size.width / bars.size
                        onSelect((offset.x / slot).toInt().coerceIn(0, bars.size - 1))
                    }
                },
        ) {
            val slot = size.width / bars.size
            val barWidth = (slot * 0.66f).coerceAtMost(32.dp.toPx()).coerceAtLeast(2f)
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            // Columns start together and finish in order, which reads as one gesture
            // sweeping left to right rather than a queue of separate animations.
            val span = 0.45f
            val stagger = if (bars.size > 1) (1f - span) / (bars.size - 1) else 0f

            bars.forEachIndexed { index, bar ->
                val x = slot * index + (slot - barWidth) / 2f
                drawRoundRect(
                    color = track,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = corner,
                )
                if (bar.value <= 0f) return@forEachIndexed
                val local = ((grow.value - index * stagger) / span).coerceIn(0f, 1f)
                val target = (bar.value / max * size.height).coerceIn(barWidth, size.height)
                val barHeight = target * local
                if (barHeight <= 0f) return@forEachIndexed
                drawRoundRect(
                    color = when {
                        index == selected -> selectedColor
                        hasSelection -> barColor.copy(alpha = 0.4f)
                        index == highlight -> accentColor
                        else -> barColor
                    },
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner,
                )
            }
            if (shownGuide != null) {
                val y = size.height - shownGuide / max * size.height
                drawLine(
                    color = guideColor.copy(alpha = 0.7f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
            }
        }
        ChartAxisLabels(
            bars.map { it.label },
            maxInlineLabels,
            if (hasSelection) selected else highlight,
        )
    }
}

/**
 * PRD 5.3 per-app trends: one line per app over a shared x axis. The paths draw themselves
 * in with [PathMeasure], which is why a series appears as a stroke being laid down rather
 * than a shape fading up.
 */
@Composable
fun TrendLines(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 128.dp,
    maxInlineLabels: Int = 8,
) {
    val drawn = series.filter { it.values.isNotEmpty() }
    if (drawn.isEmpty()) return
    val points = drawn.maxOf { it.values.size }
    val max = drawn.maxOf { line -> line.values.maxOrNull() ?: 0f }.coerceAtLeast(1f)
    val colors = drawn.map { chartColor(it.key) }
    val baseline = Trek.line
    val motion = LocalTrekMotion.current

    val trace = remember { Animatable(0f) }
    LaunchedEffect(drawn.size, points, motion) {
        trace.snapTo(0f)
        if (motion) {
            trace.animateTo(1f, tween(TrekDur.LAP, easing = TrekEase))
        } else {
            trace.snapTo(1f)
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { },
        ) {
            val stroke = 2.dp.toPx()
            val inset = stroke * 2f
            val plotHeight = (size.height - inset * 2f).coerceAtLeast(1f)
            val plotWidth = (size.width - inset * 2f).coerceAtLeast(1f)
            val stepX = if (points > 1) plotWidth / (points - 1) else 0f

            drawLine(
                color = baseline,
                start = Offset(0f, size.height - inset),
                end = Offset(size.width, size.height - inset),
                strokeWidth = 1.dp.toPx(),
            )

            val measure = PathMeasure()
            drawn.forEachIndexed { lineIndex, line ->
                fun pointAt(index: Int): Offset {
                    val x = if (points > 1) inset + stepX * index else size.width / 2f
                    val value = line.values[index].coerceIn(0f, max)
                    return Offset(x, size.height - inset - value / max * plotHeight)
                }
                if (line.values.size == 1) {
                    drawCircle(
                        color = colors[lineIndex],
                        radius = stroke * 1.6f * trace.value,
                        center = pointAt(0),
                    )
                    return@forEachIndexed
                }

                val path = Path()
                line.values.indices.forEach { index ->
                    val point = pointAt(index)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                val visible = if (trace.value >= 1f) {
                    path
                } else {
                    measure.setPath(path, false)
                    Path().also { measure.getSegment(0f, measure.length * trace.value, it, true) }
                }
                drawPath(
                    path = visible,
                    color = colors[lineIndex],
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                if (trace.value >= 1f) {
                    drawCircle(
                        color = colors[lineIndex],
                        radius = stroke * 1.7f,
                        center = pointAt(line.values.lastIndex),
                    )
                }
            }
        }
        if (labels.isNotEmpty()) {
            ChartAxisLabels(labels, maxInlineLabels, highlight = labels.lastIndex)
        }
    }
}

/** Legend entry: colour key, series name, optional value. */
@Composable
fun ChartLegendRow(color: Color, label: String, value: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesDot(color)
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(value, style = TrekFigure, color = Trek.textDim, maxLines = 1)
        }
    }
}

@Composable
private fun ChartAxisLabels(labels: List<String>, maxInline: Int, highlight: Int) {
    if (labels.isEmpty()) return
    val muted = Trek.textFaint
    val strong = Trek.text
    if (labels.size <= maxInline) {
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = TrekCaption,
                    color = if (index == highlight) strong else muted,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(labels.first(), style = TrekCaption, color = muted, maxLines = 1)
            Text(labels.last(), style = TrekCaption, color = strong, maxLines = 1)
        }
    }
}

/** Ring of progress toward a locked achievement, drawn around its glyph. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = Trek.accent,
    track: Color = Trek.line,
    stroke: Dp = 2.5.dp,
    content: @Composable () -> Unit,
) {
    val motion = LocalTrekMotion.current
    val swept = remember { Animatable(0f) }
    LaunchedEffect(progress, motion) {
        if (motion) {
            swept.animateTo(progress.coerceIn(0f, 1f), tween(TrekDur.LARGE, easing = TrekEase))
        } else {
            swept.snapTo(progress.coerceIn(0f, 1f))
        }
    }
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val w = stroke.toPx()
            val inset = w / 2f
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = w),
            )
            if (swept.value > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * swept.value,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = w, cap = StrokeCap.Round),
                )
            }
        }
        content()
    }
}

