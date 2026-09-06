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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.stats.formatDistance
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------
// Structure
// ---------------------------------------------------------------------------------------

/**
 * A bordered panel, used only where the content genuinely is a discrete object: a banner
 * you can act on, one person's row on a leaderboard, one achievement. Sections of a screen
 * are separated by [SectionHead] and whitespace instead. Boxing every group is what made
 * the previous pass read as a stock dashboard.
 */
@Composable
fun TrekPanel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    fill: Color = Trek.groundRaised,
    border: Color = Trek.hairline,
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

/**
 * Section marker: a wide-tracked overline, a hairline rule running to the edge of the
 * content column, and an optional trailing value. Survey-sheet furniture, not a card.
 */
@Composable
fun SectionHead(
    label: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    trailingColor: Color = Trek.inkMuted,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = TrekOverline, color = Trek.inkFaint)
        Spacer(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .height(1.dp)
                .background(Trek.hairline),
        )
        if (trailing != null) {
            Text(trailing, style = TrekOverline, color = trailingColor)
        }
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
    val large = figure.length <= 4

    Row(
        modifier = modifier.clearAndSetSemantics { },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            figure,
            style = if (large) TrekHero else TrekHeroTight,
            color = color,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            unit,
            style = TrekUnit,
            color = Trek.inkMuted,
            modifier = Modifier.padding(bottom = if (large) 14.dp else 10.dp),
        )
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
    color: Color = Trek.moss,
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

/**
 * Streak marker. Amber is reserved for this and for records, so it always means heat rather
 * than "look here". At zero it becomes a quiet invitation instead of vanishing: an element
 * that disappears teaches a new user nothing.
 */
@Composable
fun StreakBadge(streak: Int, modifier: Modifier = Modifier) {
    val live = streak > 0
    val amber = Trek.amber
    val idle = Trek.inkFaint
    val pulse = if (live && LocalTrekMotion.current) {
        rememberInfiniteTransition(label = "streak").animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "streakGlow",
        ).value
    } else {
        1f
    }

    Row(
        modifier = modifier
            .background(
                if (live) Trek.amberWash else Color.Transparent,
                MaterialTheme.shapes.extraLarge,
            )
            .border(
                1.dp,
                if (live) amber.copy(alpha = 0.35f) else Trek.hairline,
                MaterialTheme.shapes.extraLarge,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(7.dp)
                .clearAndSetSemantics { }
                .drawBehind {
                    drawCircle(if (live) amber.copy(alpha = pulse) else idle)
                },
        )
        Text(
            if (live) "$streak day streak" else "No streak yet",
            style = MaterialTheme.typography.labelMedium,
            color = if (live) amber else idle,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------

/**
 * Primary action. Flat, tight-cornered, tall enough to clear a 48dp target. Holds its own
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
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = Trek.moss,
            contentColor = Trek.onMoss,
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
                    color = Trek.onMoss,
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
        shape = MaterialTheme.shapes.small,
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

/**
 * Segmented control whose indicator slides between slots, so switching ranges reads as
 * moving one object rather than repainting two. Slot width comes from the parent's own
 * constraints, which keeps the indicator exact without a subcompose pass.
 */
@Composable
fun TrekSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shape = MaterialTheme.shapes.small
    val slotShape = RoundedCornerShape(8.dp)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Trek.groundSunken)
            .border(1.dp, Trek.hairlineSoft, shape)
            .padding(3.dp)
            .selectableGroup(),
    ) {
        val slot = maxWidth / options.size
        val offsetX by animateDpAsState(
            targetValue = slot * selected.coerceIn(0, options.lastIndex),
            animationSpec = tween(trekDuration(TrekDur.SMALL), easing = TrekEase),
            label = "segment",
        )
        Spacer(
            modifier = Modifier
                .offset(x = offsetX)
                .width(slot)
                .fillMaxHeight()
                .background(Trek.groundRaised, slotShape)
                .border(1.dp, Trek.hairline, slotShape),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val active = index == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(slotShape)
                        .selectable(
                            selected = active,
                            role = Role.Tab,
                            onClick = { onSelect(index) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (active) Trek.ink else Trek.inkFaint,
                        maxLines = 1,
                    )
                }
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
    color: Color = Trek.moss,
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
        highlight -> Trek.moss
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
