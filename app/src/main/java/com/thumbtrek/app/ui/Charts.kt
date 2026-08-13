package com.thumbtrek.app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.TRACKED_APPS

/** One arc of [AppSplitDonut]. [key] should be the package name — it picks the colour. */
data class ChartSlice(
    val key: String,
    val label: String,
    val value: Float,
    val valueLabel: String,
)

/** One column of [HistoryBarChart]; [label] is the axis caption underneath it. */
data class ChartBar(val label: String, val value: Float)

/** One line of [AppTrendChart]. [key] should be the package name — it picks the colour. */
data class ChartSeries(val key: String, val label: String, val values: List<Float>)

private val PALETTE_DARK = listOf(
    Color(0xFF7BD88A),
    Color(0xFF6EC1E4),
    Color(0xFFF2B441),
    Color(0xFFE8798C),
    Color(0xFFB58BF0),
    Color(0xFF9FC3A9),
)

private val PALETTE_LIGHT = listOf(
    Color(0xFF2F7D4A),
    Color(0xFF1D6C91),
    Color(0xFF9A6A00),
    Color(0xFFB03A55),
    Color(0xFF6941B0),
    Color(0xFF4E7A5C),
)

/**
 * Deterministic chart colour: the same [key] always maps to the same hue, so an app keeps its
 * colour across every chart. Tracked apps get their slot in [TRACKED_APPS] order, anything else
 * hashes into the palette. The palette follows the theme's surface brightness.
 */
@Composable
fun chartColor(key: String): Color {
    val palette =
        if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) PALETTE_DARK else PALETTE_LIGHT
    val index = TRACKED_APPS.keys.indexOf(key).takeIf { it >= 0 }
        ?: (key.hashCode() and Int.MAX_VALUE)
    return palette[index % palette.size]
}

/**
 * PRD §5.2 per-app ring chart for one day, with a legend of app names and distances.
 *
 * @param slices one entry per app, biggest first. Slices with a non-positive value are skipped,
 *   and nothing at all is drawn when the list is empty or everything is zero.
 * @param modifier applied to the chart row.
 * @param centerLabel small caption inside the ring, e.g. "Today's trek".
 * @param centerValue headline inside the ring, e.g. the formatted total distance.
 * @param ringSize outer diameter of the ring.
 * @param ringWidth thickness of the ring stroke.
 */
@Composable
fun AppSplitDonut(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    centerLabel: String? = null,
    centerValue: String? = null,
    ringSize: Dp = 132.dp,
    ringWidth: Dp = 18.dp,
) {
    val drawn = slices.filter { it.value > 0f }
    val total = drawn.sumOf { it.value.toDouble() }.toFloat()
    if (drawn.isEmpty() || total <= 0f) return

    val colors = drawn.map { chartColor(it.key) }
    val track = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(ringSize),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = ringWidth.toPx()
                val diameter = (size.minDimension - stroke).coerceAtLeast(0f)
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                val arcSize = Size(diameter, diameter)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                val gap = if (drawn.size > 1) 3f else 0f
                var start = -90f
                drawn.forEachIndexed { index, slice ->
                    val sweep = slice.value / total * 360f
                    drawArc(
                        color = colors[index],
                        startAngle = start + gap / 2f,
                        sweepAngle = (sweep - gap).coerceAtLeast(1f),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                    )
                    start += sweep
                }
            }
            if (centerLabel != null || centerValue != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    centerLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    centerValue?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            drawn.forEachIndexed { index, slice ->
                ChartLegendRow(colors[index], slice.label, slice.valueLabel)
            }
        }
    }
}

/**
 * PRD §5.3 history bars — daily, weekly or monthly, whichever buckets the caller passes in.
 *
 * @param bars ordered oldest → newest. Nothing is drawn when the list is empty.
 * @param modifier applied to the chart column.
 * @param barColor fill colour of the bars.
 * @param height height of the plot area, excluding the labels underneath.
 * @param maxInlineLabels above this many bars only the first and last labels are shown.
 */
@Composable
fun HistoryBarChart(
    bars: List<ChartBar>,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 140.dp,
    maxInlineLabels: Int = 8,
) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.coerceAtLeast(1f)
    val track = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
        ) {
            val slot = size.width / bars.size
            val barWidth = (slot * 0.6f).coerceAtMost(28.dp.toPx()).coerceAtLeast(1f)
            val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
            bars.forEachIndexed { index, bar ->
                val x = slot * index + (slot - barWidth) / 2f
                drawRoundRect(
                    color = track,
                    topLeft = Offset(x, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = corner,
                )
                if (bar.value > 0f) {
                    val barHeight = (bar.value / max * size.height)
                        .coerceIn(barWidth, size.height)
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = corner,
                    )
                }
            }
        }
        ChartAxisLabels(bars.map { it.label }, maxInlineLabels)
    }
}

/**
 * PRD §5.3 per-app trends: one line per app over a shared x axis, with a legend of app names.
 *
 * @param series one entry per app; every [ChartSeries.values] list should have one entry per
 *   [labels] entry, oldest → newest. Nothing is drawn when there is no data.
 * @param labels x-axis captions, oldest → newest.
 * @param modifier applied to the chart column.
 * @param height height of the plot area, excluding the labels and legend.
 * @param maxInlineLabels above this many points only the first and last labels are shown.
 */
@Composable
fun AppTrendChart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    maxInlineLabels: Int = 8,
) {
    val drawn = series.filter { it.values.isNotEmpty() }
    if (drawn.isEmpty()) return
    val points = drawn.maxOf { it.values.size }
    val max = drawn.maxOf { line -> line.values.maxOrNull() ?: 0f }.coerceAtLeast(1f)
    val colors = drawn.map { chartColor(it.key) }
    val baseline = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
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
            drawn.forEachIndexed { lineIndex, line ->
                fun pointAt(index: Int): Offset {
                    val x = if (points > 1) inset + stepX * index else size.width / 2f
                    val value = line.values[index].coerceIn(0f, max)
                    return Offset(x, size.height - inset - value / max * plotHeight)
                }
                if (line.values.size == 1) {
                    drawCircle(
                        color = colors[lineIndex],
                        radius = stroke * 1.6f,
                        center = pointAt(0),
                    )
                    return@forEachIndexed
                }
                val path = Path()
                line.values.indices.forEach { index ->
                    val point = pointAt(index)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                drawPath(
                    path = path,
                    color = colors[lineIndex],
                    style = Stroke(
                        width = stroke,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
                drawCircle(
                    color = colors[lineIndex],
                    radius = stroke * 1.6f,
                    center = pointAt(line.values.lastIndex),
                )
            }
        }
        if (labels.isNotEmpty()) {
            ChartAxisLabels(labels, maxInlineLabels)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            drawn.forEachIndexed { index, line ->
                ChartLegendRow(colors[index], line.label, null)
            }
        }
    }
}

@Composable
private fun ChartLegendRow(color: Color, label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(10.dp)) { drawCircle(color = color) }
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChartAxisLabels(labels: List<String>, maxInline: Int) {
    if (labels.isEmpty()) return
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    if (labels.size <= maxInline) {
        Row(modifier = Modifier.fillMaxWidth()) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = style,
                    color = color,
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
            Text(labels.first(), style = style, color = color, maxLines = 1)
            Text(labels.last(), style = style, color = color, maxLines = 1)
        }
    }
}
