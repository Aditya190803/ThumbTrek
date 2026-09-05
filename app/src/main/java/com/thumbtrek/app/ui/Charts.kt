package com.thumbtrek.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.TRACKED_APPS
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One arc of [TrekGauge]. [key] should be the package name: it picks the colour. */
data class ChartSlice(
    val key: String,
    val label: String,
    val value: Float,
    val valueLabel: String,
)

/** One column of [HistoryBars]; [label] is the axis caption underneath it. */
data class ChartBar(val label: String, val value: Float)

/** One line of [TrendLines]. [key] should be the package name: it picks the colour. */
data class ChartSeries(val key: String, val label: String, val values: List<Float>)

/**
 * Categorical ramp for per-app series. Deliberately separate from the semantic accents in
 * [TrekPalette]: a chart hue means "which app", never "good" or "hot". Hues are spaced
 * around the wheel and held at similar lightness so no single app looks louder than the
 * others, and the light set is darkened rather than merely desaturated so it survives on
 * bone paper.
 */
private val SERIES_DARK = listOf(
    Color(0xFF8BE6A0),
    Color(0xFF77C4E8),
    Color(0xFFE9C063),
    Color(0xFFE79BB4),
    Color(0xFFB6A2F0),
    Color(0xFF6FD3C4),
)

private val SERIES_LIGHT = listOf(
    Color(0xFF1E6B3E),
    Color(0xFF1A5F87),
    Color(0xFF8A5806),
    Color(0xFF9C2F55),
    Color(0xFF5A3FA8),
    Color(0xFF116B60),
)

/**
 * Deterministic series colour: the same [key] always maps to the same hue, so an app keeps
 * its colour across every chart and the gauge, the split list and the trend all agree.
 * Tracked apps take their slot in [TRACKED_APPS] order; anything else hashes in.
 */
@Composable
fun chartColor(key: String): Color {
    val palette = if (Trek.isDark) SERIES_DARK else SERIES_LIGHT
    val index = TRACKED_APPS.keys.indexOf(key).takeIf { it >= 0 }
        ?: (key.hashCode() and Int.MAX_VALUE)
    return palette[index % palette.size]
}

// The gauge is an open-bottom instrument dial, not a closed donut: the break at the bottom
// is where the streak sits, and an arc that does not close reads as a measurement in
// progress rather than a completed pie.
private const val GAUGE_START = 130f
private const val GAUGE_SWEEP = 280f
private const val GAUGE_TICKS = 12

/**
 * PRD 5.2 per-app ring, drawn as a segmented survey dial. Sweeps open once on entry, then
 * animates between values. [content] is laid out in the middle, which is where the hero
 * distance lives on the dashboard.
 *
 * With no data the dial draws as a dashed, unwalked trail rather than disappearing, so a
 * first-run screen still shows the shape of the thing it is about to fill.
 */
@Composable
fun TrekGauge(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    ringWidth: Dp = 14.dp,
    content: @Composable () -> Unit,
) {
    val drawn = slices.filter { it.value > 0f }
    val total = drawn.fold(0f) { acc, slice -> acc + slice.value }
    val colors = drawn.map { chartColor(it.key) }
    val track = Trek.groundSunken
    val tickColor = Trek.hairline
    val emptyColor = Trek.hairline
    val motion = LocalTrekMotion.current

    // Keyed on "is there anything to draw" rather than on the values themselves: the sweep
    // should play once, when the first real figures land, and never replay while today's
    // numbers tick up underneath it.
    val hasData = drawn.isNotEmpty() && total > 0f
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(hasData, motion) {
        when {
            !hasData -> reveal.snapTo(0f)
            motion -> reveal.animateTo(1f, tween(TrekDur.REVEAL, easing = TrekEase))
            else -> reveal.snapTo(1f)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
        ) {
            val stroke = ringWidth.toPx()
            val tickLength = stroke * 0.45f
            val tickGap = stroke * 0.75f
            val diameter = (size.minDimension - stroke - (tickLength + tickGap) * 2f)
                .coerceAtLeast(0f)
            val radius = diameter / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val topLeft = Offset(cx - radius, cy - radius)
            val arcSize = Size(diameter, diameter)

            // Survey ticks sit outside the ring, marking eighths of the dial.
            val tickRadius = radius + stroke / 2f + tickGap
            repeat(GAUGE_TICKS + 1) { i ->
                val angle = (GAUGE_START + GAUGE_SWEEP * i / GAUGE_TICKS) * PI.toFloat() / 180f
                val dx = cos(angle)
                val dy = sin(angle)
                val long = i % 3 == 0
                val len = if (long) tickLength else tickLength * 0.55f
                drawLine(
                    color = tickColor,
                    start = Offset(cx + dx * tickRadius, cy + dy * tickRadius),
                    end = Offset(cx + dx * (tickRadius + len), cy + dy * (tickRadius + len)),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            if (drawn.isEmpty() || total <= 0f) {
                drawArc(
                    color = emptyColor,
                    startAngle = GAUGE_START,
                    sweepAngle = GAUGE_SWEEP,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(
                        width = stroke * 0.5f,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(2.dp.toPx(), 7.dp.toPx()),
                        ),
                    ),
                )
                return@Canvas
            }

            drawArc(
                color = track,
                startAngle = GAUGE_START,
                sweepAngle = GAUGE_SWEEP,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            val gap = if (drawn.size > 1) 2.5f else 0f
            var start = GAUGE_START
            drawn.forEachIndexed { index, slice ->
                val full = slice.value / total * GAUGE_SWEEP
                val sweep = (full - gap).coerceAtLeast(0.8f) * reveal.value
                drawArc(
                    color = colors[index],
                    startAngle = start + gap / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(
                        width = stroke,
                        cap = if (drawn.size == 1) StrokeCap.Round else StrokeCap.Butt,
                    ),
                )
                start += full
            }
        }
        content()
    }
}

/**
 * PRD 5.3 history bars. One animation drives the whole set with a per-column offset, so a
 * long range still costs a single running animation instead of one per bar.
 *
 * @param bars ordered oldest to newest.
 * @param highlight index drawn in the accent colour, normally today. -1 for none.
 */
@Composable
fun HistoryBars(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    highlight: Int = -1,
    barColor: Color = Trek.slate,
    accentColor: Color = Trek.moss,
    height: Dp = 132.dp,
    maxInlineLabels: Int = 8,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.coerceAtLeast(1f)
    val track = Trek.groundSunken
    val motion = LocalTrekMotion.current

    // Same rule as the gauge: play once when data first exists, then hold. Keying on the
    // values would replay the whole sweep every time today's column ticks up.
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

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clearAndSetSemantics { },
        ) {
            val slot = size.width / bars.size
            val barWidth = (slot * 0.54f).coerceAtMost(22.dp.toPx()).coerceAtLeast(2f)
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
                    color = if (index == highlight) accentColor else barColor,
                    topLeft = Offset(x, size.height - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = corner,
                )
            }
        }
        ChartAxisLabels(bars.map { it.label }, maxInlineLabels, highlight)
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
    val baseline = Trek.hairline
    val motion = LocalTrekMotion.current

    val trace = remember { Animatable(0f) }
    LaunchedEffect(drawn.size, points, motion) {
        trace.snapTo(0f)
        if (motion) {
            trace.animateTo(1f, tween(TrekDur.REVEAL, easing = TrekEase))
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

/**
 * A seven-column glance strip: no axis, no frame, just the shape of the week with today
 * picked out. Small enough to sit under the hero without competing with it.
 */
@Composable
fun WeekPulse(
    values: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color = Trek.slate,
    accentColor: Color = Trek.moss,
    height: Dp = 34.dp,
) {
    if (values.isEmpty()) return
    val max = values.maxOrNull()?.coerceAtLeast(0.0001f) ?: return
    val track = Trek.groundSunken
    val motion = LocalTrekMotion.current
    val hasData = values.any { it > 0f }
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values.size, hasData, motion) {
        grow.snapTo(0f)
        if (hasData && motion) {
            grow.animateTo(1f, tween(TrekDur.LARGE, easing = TrekEase))
        } else {
            grow.snapTo(1f)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { },
    ) {
        val slot = size.width / values.size
        val barWidth = (slot * 0.46f).coerceAtMost(14.dp.toPx()).coerceAtLeast(2f)
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        values.forEachIndexed { index, value ->
            val x = slot * index + (slot - barWidth) / 2f
            drawRoundRect(
                color = track,
                topLeft = Offset(x, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = corner,
            )
            if (value <= 0f) return@forEachIndexed
            val h = (value / max * size.height).coerceIn(barWidth, size.height) * grow.value
            drawRoundRect(
                color = if (index == values.lastIndex) accentColor else barColor,
                topLeft = Offset(x, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = corner,
            )
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
            color = Trek.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(value, style = TrekFigure, color = Trek.inkMuted, maxLines = 1)
        }
    }
}

@Composable
private fun ChartAxisLabels(labels: List<String>, maxInline: Int, highlight: Int) {
    if (labels.isEmpty()) return
    val muted = Trek.inkFaint
    val strong = Trek.ink
    if (labels.size <= maxInline) {
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = TrekOverline,
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
            Text(labels.first(), style = TrekOverline, color = muted, maxLines = 1)
            Text(labels.last(), style = TrekOverline, color = strong, maxLines = 1)
        }
    }
}

/** Ring of progress toward a locked achievement, drawn around its glyph. */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = Trek.moss,
    track: Color = Trek.hairline,
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

/** Spacer that reserves a chart's height while its data is still loading. */
@Composable
fun ChartPlaceholder(height: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            listOf(0.4f, 0.7f, 0.3f, 0.9f, 0.55f, 0.75f, 0.45f).forEach { f ->
                Skeleton(
                    modifier = Modifier.weight(1f),
                    height = (height.value * f).dp,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
