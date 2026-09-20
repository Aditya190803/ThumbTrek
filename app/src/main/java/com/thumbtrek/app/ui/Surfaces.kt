package com.thumbtrek.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import com.thumbtrek.app.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.thumbtrek.app.stats.formatDistance
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------------

/** A softly raised surface for a related group of controls or data. */
@Composable
fun TrekPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    fill: Color = Trek.groundRaised,
    border: Color = Trek.hairlineSoft,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(fill, shape)
            .border(1.dp, border, shape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHead(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color = Trek.inkMuted,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = Trek.ink)
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = trailingColor)
        }
    }
}

@Composable
fun PageHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Trek.ink)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Trek.inkMuted)
    }
}

@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.trek_mark), contentDescription = null, modifier = modifier)
}

/** Recognizable app initials give chart colors a second, textual key. */
@Composable
fun AppToken(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(38.dp).background(color.copy(alpha = 0.12f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(1), style = MaterialTheme.typography.titleMedium, color = color)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Trek.hairlineSoft) {
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

// ---------------------------------------------------------------------------------------
// Numbers
// ---------------------------------------------------------------------------------------

/** Splits "1.24 km" into figure and unit so they can be set at different sizes. */
fun distanceParts(meters: Double): Pair<String, String> {
    val text = formatDistance(meters)
    val cut = text.lastIndexOf(' ')
    return if (cut < 0) text to "" else text.substring(0, cut) to text.substring(cut + 1)
}

/**
 * The dashboard readout. Counts from where it was to [meters] on an exponential ease-out,
 * so opening the app feels like an odometer settling rather than a value appearing. The
 * unit is set as a separate, quieter mark.
 *
 * The figure steps down a size once it gets long, so a five-figure kilometre count still
 * fits on one line at large font scales.
 */
@Composable
fun HeroDistance(
    meters: Double,
    modifier: Modifier = Modifier,
    color: Color = Trek.ink,
    centered: Boolean = false,
) {
    val target = meters.toFloat()
    val motion = LocalTrekMotion.current
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target, motion) {
        if (motion) {
            animated.animateTo(target, tween(TrekDur.REVEAL, easing = TrekEase))
        } else {
            animated.snapTo(target)
        }
    }

    val (figure, unit) = distanceParts(animated.value.toDouble())
    BoxWithConstraints(modifier = modifier.fillMaxWidth().clearAndSetSemantics { }) {
        val fontScale = LocalDensity.current.fontScale
        val preferred = if (figure.length <= 4) TrekHero else TrekHeroTight
        val available = (maxWidth.value - 58f * fontScale).coerceAtLeast(40f)
        val figureSize = minOf(preferred.fontSize.value, available / (figure.length * 0.68f * fontScale)).sp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(figure, style = preferred.copy(fontSize = figureSize, lineHeight = figureSize * 1.12f), color = color, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            Text(unit, style = TrekUnit, color = Trek.inkMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

/** Smaller sibling of [HeroDistance], for figures that should tick rather than pop. */
@Composable
fun CountedDistance(
    meters: Double,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = Trek.ink,
) {
    val target = meters.toFloat()
    val motion = LocalTrekMotion.current
    val animated = remember { Animatable(0f) }
    LaunchedEffect(target, motion) {
        if (motion) {
            animated.animateTo(target, tween(TrekDur.LARGE, easing = TrekEase))
        } else {
            animated.snapTo(target)
        }
    }
    Text(
        formatDistance(animated.value.toDouble()),
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
    )
}

// ---------------------------------------------------------------------------------------
// Rails, pills, chips
// ---------------------------------------------------------------------------------------

/**
 * The one bar primitive in the app: a sunken track with a filled portion. App splits, day
 * history, badge progress and leaderboard rows all use it, so proportion looks the same
 * wherever it shows up.
 */
@Composable
fun Rail(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Trek.accent,
    track: Color = Trek.groundSunken,
    height: Dp = 6.dp,
) {
    val shown by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(trekDuration(TrekDur.LARGE), easing = TrekEase),
        label = "rail",
    )
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { }
            .drawBehind {
                val radius = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(color = track, cornerRadius = radius)
                if (shown > 0f) {
                    // Never narrower than the cap radius: a tiny value would otherwise
                    // render as a sliver that reads like a rendering bug.
                    val filled = (size.width * shown).coerceAtLeast(size.height)
                    drawRoundRect(
                        color = color,
                        size = Size(filled, size.height),
                        cornerRadius = radius,
                    )
                }
            },
    )
}

@Composable
fun TrekChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Trek.inkMuted,
    fill: Color = Color.Transparent,
    border: Color = Trek.hairline,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .background(fill, MaterialTheme.shapes.extraLarge)
            .border(1.dp, border, MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leading?.invoke()
        Text(text, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
fun StreakBadge(streak: Int, modifier: Modifier = Modifier) {
    TrekChip(
        text = if (streak > 0) "$streak day streak" else "A fresh start",
        modifier = modifier,
        color = if (streak > 0) Trek.amber else Trek.inkMuted,
        fill = if (streak > 0) Trek.amberWash else Trek.groundSunken,
        border = Color.Transparent,
    )
}

// ---------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------

/**
 * Primary action, tall enough to clear a 48dp target. Holds its own
 * width while [loading] so the label never jumps when async work starts.
 */
@Composable
fun TrekButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled && !loading,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Trek.accent,
            contentColor = Trek.onAccent,
            disabledContainerColor = Trek.groundSunken,
            disabledContentColor = Trek.inkFaint,
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp, 0.dp, 0.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // The label stays in the layout at zero alpha, so the button keeps its size.
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                color = if (loading) Color.Transparent else LocalContentColor.current,
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Trek.onAccent,
                )
            }
        }
    }
}

/** Secondary action: outline only, same target height. */
@Composable
fun TrekGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Trek.ink,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (enabled) Trek.hairline else Trek.hairlineSoft),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = Trek.inkFaint,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** Equal-width tabs with an explicit selected state, including in scrolling layouts. */
@Composable
fun TrekSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(Trek.groundSunken).padding(4.dp).selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (active) Trek.groundRaised else Color.Transparent)
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(horizontal = 2.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium,
                    color = if (active) Trek.accent else Trek.inkMuted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// States
// ---------------------------------------------------------------------------------------

/**
 * Zero state. Always names what will appear here and what the single next move is: a blank
 * screen with a shrug is how a new user decides the app is broken.
 */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Trek.ink)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Trek.inkMuted)
        if (action != null) {
            Spacer(modifier = Modifier.height(4.dp))
            action()
        }
    }
}

/** Shimmering placeholder that holds the exact height of the row it stands in. */
@Composable
fun Skeleton(
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
    shape: Shape = RoundedCornerShape(4.dp),
) {
    val phase = if (LocalTrekMotion.current) {
        rememberInfiniteTransition(label = "skeleton").animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "shimmer",
        ).value
    } else {
        0.5f
    }
    val base = Trek.groundSunken
    val highlight = Trek.hairline
    Spacer(
        modifier = modifier
            .height(height)
            .clip(shape)
            .clearAndSetSemantics { }
            .drawBehind {
                drawRect(base)
                val band = size.width * 0.6f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        startX = phase * size.width - band,
                        endX = phase * size.width + band,
                    ),
                )
            },
    )
}

// ---------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------

/**
 * A labelled figure over a proportional rail. This is the workhorse row of the app: per-app
 * splits, day history and leaderboard scores all use it, so relative size is always encoded
 * the same way and a reader learns it once.
 */
@Composable
fun MeasureRow(
    label: String,
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = Trek.accent,
    labelColor: Color = Trek.ink,
    valueColor: Color = Trek.inkMuted,
    leading: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(value, style = TrekFigure, color = valueColor, maxLines = 1)
        }
        Rail(fraction, color = color, height = 5.dp)
    }
}

/** Small round colour key beside a series name. */
@Composable
fun SeriesDot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Spacer(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics { }
            .drawBehind { drawCircle(color) },
    )
}

/**
 * Rank marker. The top three take a medal tint, everyone else a plain tabular figure, which
 * keeps the eye on the podium without turning the whole list into confetti.
 */
@Composable
fun RankMark(rank: Int, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val medals = Trek.medals
    val color = when {
        rank in 1..3 -> medals[rank - 1]
        highlight -> Trek.accent
        else -> Trek.inkFaint
    }
    Box(
        modifier = modifier.sizeIn(minWidth = 28.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(rank.toString(), style = TrekFigure, color = color, maxLines = 1)
    }
}

/** A percentage delta, coloured only by direction. Never by judgement about the number. */
@Composable
fun DeltaMark(percent: Int?, suffix: String, modifier: Modifier = Modifier) {
    if (percent == null) return
    val up = percent >= 0
    Text(
        "${if (up) "+" else "-"}${abs(percent)}% $suffix",
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = if (up) Trek.amber else Trek.slate,
        maxLines = 1,
    )
}

/** Progress toward a target, phrased the way a person would say it. */
fun percentCaption(progress: Double): String = "${(progress * 100).roundToInt()}%"
