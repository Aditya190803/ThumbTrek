package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.dayOverDayDelta
import com.thumbtrek.app.stats.MAX_APP_LANES
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.ovalModel
import com.thumbtrek.app.stats.BASELINE_DAYS
import com.thumbtrek.app.stats.limitNudgeLevel
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.weekOverWeekDelta
import java.time.LocalDate

// ---------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------

@Composable
internal fun Dashboard(
    state: DashboardViewModel.UiState,
    scrollState: ScrollState,
    onKeepAutoLimit: () -> Unit,
    onDeclineAutoLimit: () -> Unit,
    onAdjustLimit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val todayMeters = pixelsToMeters(state.todayPx, dpi)
    val byDate = remember(state.days) {
        state.days.associate { LocalDate.parse(it.date) to it.pixels }
    }
    val week = remember(byDate, state.today) { dailyBuckets(byDate, 7, state.today) }
    val hasEverTracked = state.days.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = GUTTER)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        if (!state.trackingEnabled) {
            TrackingOffBanner(
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        }

        if (state.autoLimitNotice != null && state.limitM != null) {
            AutoLimitCard(
                baselineM = state.autoLimitNotice.toDouble(),
                limitM = state.limitM.toDouble(),
                onKeep = onKeepAutoLimit,
                onAdjust = onAdjustLimit,
                onDecline = onDeclineAutoLimit,
            )
        }

        DailySummary(
            todayMeters = todayMeters,
            streak = state.streak,
            slices = state.perApp.map {
                val meters = pixelsToMeters(it.pixels, dpi)
                ChartSlice(it.packageName, appName(it.packageName, state.customLabels), meters.toFloat())
            },
            delta = dayOverDayDelta(byDate, state.today),
            yesterdayMeters = pixelsToMeters(byDate[state.today.minusDays(1)] ?: 0L, dpi),
            limitM = state.limitM,
            cleanStreak = state.cleanStreak,
            baselineDays = state.baselineDays.takeIf { state.baselineSince != null },
            otherMeters = state.otherSourcesM,
            appLimits = state.appLimits,
        )

        // The lane rows already name up to four apps. The full split only adds something
        // once some of them have been folded into "Other".
        if (state.perApp.size > MAX_APP_LANES) {
            AppSplit(state, dpi, todayMeters)
        }

        if (hasEverTracked) {
            WeekGlance(
                week = week.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                labels = week.map { it.label },
                weekMeters = pixelsToMeters(state.weekPx, dpi),
                delta = weekOverWeekDelta(byDate, state.today),
                limitM = state.limitM,
            )
        }

        // Held back until Room has answered, so a returning user never sees a flash of
        // the first-run checklist while their history is still loading.
        if (state.loaded && (!hasEverTracked || !state.trackingEnabled || state.streak < 2)) {
            FirstTrekChecklist(
                trackingOn = state.trackingEnabled,
                hasScrolled = state.todayPx > 0 || hasEverTracked,
                hasStreak = state.streak >= 2,
            )
        }

        Text(
            "Private by default. Only scroll distance is measured.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.textFaint,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * The lap oval answers how far, where, and how close to the limit. Every lane it draws has
 * a text row under it, and the limit is said again in words below that.
 */
@Composable
private fun DailySummary(
    todayMeters: Double,
    streak: Int,
    slices: List<ChartSlice>,
    delta: Int?,
    yesterdayMeters: Double,
    limitM: Float?,
    cleanStreak: Int,
    baselineDays: Int?,
    otherMeters: Double = 0.0,
    appLimits: Map<String, Float> = emptyMap(),
) {
    // With a shared limit the browser is one more lane, and its distance is part of the
    // total the limit judges.
    val totalMeters = todayMeters + otherMeters
    val model = remember(totalMeters, slices, limitM, otherMeters) {
        val lanes = slices.map { it.label to it.value.toDouble() } +
            listOfNotNull(("Browser" to otherMeters).takeIf { otherMeters > 0.0 })
        ovalModel(totalMeters, lanes.sortedByDescending { it.second }, limitM?.toDouble())
    }
    // Lane rows are named, not keyed, so the limits are looked up by the same label.
    val limitByLabel = remember(slices, appLimits) {
        slices.mapNotNull { slice -> appLimits[slice.key]?.let { slice.label to it.toDouble() } }.toMap()
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LapOval(model, totalMeters)
        LaneRows(model, limits = limitByLabel)
        Text(
            if (todayMeters > 0.0) comparison(todayMeters) else "Your first scroll starts the first lap.",
            style = MaterialTheme.typography.titleMedium,
            color = Trek.text,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StreakBadge(streak)
            // Today is still running, so a percentage against all of yesterday reads as a
            // big drop every morning. Show it only once today has caught up (it can only
            // grow from there); before that, yesterday's total is the honest reference.
            if (delta != null && delta >= 0 && todayMeters > 0.0) {
                DeltaMark(delta, "than yesterday")
            } else if (yesterdayMeters > 0.0) {
                Text(
                    "Yesterday: ${formatDistance(yesterdayMeters)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Trek.textDim,
                )
            }
        }
        TrekPanel {
            when {
                limitM != null -> LimitGlance(totalMeters, limitM, cleanStreak)
                baselineDays != null -> BaselineGlance(baselineDays)
                else -> {
                    Text("No daily limit", style = MaterialTheme.typography.titleMedium, color = Trek.text)
                    Spacer(Modifier.height(6.dp))
                    Text("You’re tracking without a cap. Set a limit or learn your baseline anytime in Settings.", style = MaterialTheme.typography.bodyMedium, color = Trek.textDim)
                }
            }
        }
    }
}

/**
 * The baseline week in progress. Counts tracked days rather than calendar days, and says
 * so, because a day with tracking off doesn't move it and that should never look stuck.
 */
@Composable
private fun BaselineGlance(days: Int) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Learning your baseline", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Trek.text)
            Text("Day $days of $BASELINE_DAYS", style = TrekFigure, color = Trek.textDim)
        }
        Spacer(Modifier.height(12.dp))
        Rail(days / BASELINE_DAYS.toFloat(), color = Trek.info, track = Trek.info.copy(alpha = 0.12f), height = 6.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (days == 0) "Scroll as you normally would. Nothing is judged this week."
            else "After $BASELINE_DAYS tracked days, your limit is set about 10% under your usual day. Nothing is judged until then.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.textDim,
        )
    }
}

/**
 * Day eight: the baseline has become a limit. Announced rather than slipped in, with the
 * number it came from and every way out one tap away.
 */
@Composable
private fun AutoLimitCard(
    baselineM: Double,
    limitM: Double,
    onKeep: () -> Unit,
    onAdjust: () -> Unit,
    onDecline: () -> Unit,
) {
    TrekPanel(fill = Trek.accentWash, border = Color.Transparent) {
        Text("Your daily limit is set", style = MaterialTheme.typography.titleMedium, color = Trek.text)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your usual day is about ${formatDistance(baselineM)}, so your limit is now " +
                "${formatDistance(limitM)}. Days within the limit count from today.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.textDim,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrekButton("Keep this limit", onKeep, Modifier.weight(1f))
            TrekGhostButton("Change it", onAdjust, Modifier.weight(1f))
        }
        TextButton(onClick = onDecline) { Text("Track without a limit", color = Trek.textDim) }
    }
}

/**
 * Three readings of the same rail: comfortably inside, close (from the same 80% mark the
 * limit nudge uses, so the screen and the notification agree), and over. Each is said in
 * words as well as colour.
 */
@Composable
private fun LimitGlance(
    todayMeters: Double,
    limitM: Float,
    cleanStreak: Int,
) {
    val limit = limitM.toDouble().coerceAtLeast(1.0)
    val level = limitNudgeLevel(todayMeters, limit)
    val tint = when (level) {
        2 -> Trek.danger
        1 -> Trek.warn
        else -> Trek.good
    }
    val remaining = formatDistance((limit - todayMeters).coerceAtLeast(0.0))
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Daily limit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Trek.text)
            Text(
                "${formatDistance(todayMeters)} of ${formatDistance(limit)}",
                style = TrekFigure,
                color = Trek.textDim,
            )
        }
        Spacer(Modifier.height(12.dp))
        Rail((todayMeters / limit).toFloat(), color = tint, track = tint.copy(alpha = 0.12f), height = 6.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            when (level) {
                2 -> "${formatDistance(todayMeters - limit)} over. A good moment to pause."
                1 -> "$remaining left. You’re getting close."
                else -> "$remaining remaining today"
            },
            style = MaterialTheme.typography.bodyMedium, color = tint,
        )
        if (cleanStreak > 0) {
            Text("$cleanStreak ${if (cleanStreak == 1) "day" else "days"} within your limit", style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
        }
    }
}

@Composable
private fun WeekGlance(
    week: List<Float>,
    labels: List<String>,
    weekMeters: Double,
    delta: Int?,
    limitM: Float?,
) {
    Column {
        SectionHead("Last 7 days", trailing = formatDistance(weekMeters) + " this week")
        HistoryBars(
            week.mapIndexed { i, value -> ChartBar(labels.getOrElse(i) { "" }, value) },
            highlight = week.lastIndex,
            height = 84.dp,
            barColor = Trek.info,
            guide = limitM,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DeltaMark(delta, "than last week") }
            if (limitM != null && week.any { it > 0f }) {
                LimitKey()
            }
        }
    }
}

/** Legend for the dashed limit line drawn over the bar charts. */
@Composable
internal fun LimitKey() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(
            modifier = Modifier
                .width(14.dp)
                .height(2.dp)
                .background(Trek.danger.copy(alpha = 0.7f)),
        )
        Text("Daily limit", style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
    }
}

@Composable
private fun AppSplit(state: DashboardViewModel.UiState, dpi: Int, todayMeters: Double) {
    val busiest = state.perApp.firstOrNull()?.pixels ?: 0L
    Column {
        SectionHead("Where it went", trailing = "${state.perApp.size} ${if (state.perApp.size == 1) "app" else "apps"}")
        state.perApp.forEach { app ->
            val meters = pixelsToMeters(app.pixels, dpi)
            val share = if (todayMeters > 0) (meters / todayMeters * 100).toInt() else 0
            MeasureRow(
                label = appName(app.packageName, state.customLabels),
                value = formatDistance(meters),
                fraction = if (busiest > 0) app.pixels.toFloat() / busiest else 0f,
                color = chartColor(app.packageName),
                leading = { AppToken(appName(app.packageName, state.customLabels), chartColor(app.packageName)) },
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${appName(app.packageName, state.customLabels)}: " +
                        "${formatDistance(meters)}, $share percent of today"
                },
            )
        }
    }
}

/**
 * First-run activation. Three steps, each one a thing the user can actually do right now,
 * and the list retires itself once the habit exists. Replaces the blank screen that a
 * brand new install used to show.
 */
@Composable
private fun FirstTrekChecklist(
    trackingOn: Boolean,
    hasScrolled: Boolean,
    hasStreak: Boolean,
) {
    Column {
        SectionHead("Getting started")
        TrekPanel(padding = PaddingValues(4.dp)) {
            ChecklistStep(1, "Turn on tracking", "Enable ThumbTrek in Accessibility settings.", trackingOn)
            Hairline(modifier = Modifier.padding(horizontal = 14.dp))
            ChecklistStep(
                2,
                "Use your apps as usual",
                "We’ll measure the distance in the background.",
                hasScrolled,
            )
            Hairline(modifier = Modifier.padding(horizontal = 14.dp))
            ChecklistStep(
                3,
                "Come back tomorrow",
                "Two days in a row starts a streak.",
                hasStreak,
            )
        }
    }
}

@Composable
private fun ChecklistStep(index: Int, title: String, body: String, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (done) Trek.accent else Color.Transparent,
                    CircleShape,
                )
                .border(1.dp, if (done) Trek.accent else Trek.line, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Trek.onAccent,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(index.toString(), style = MaterialTheme.typography.labelSmall, color = Trek.textFaint)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done) Trek.textDim else Trek.text,
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = Trek.textFaint)
        }
    }
}

@Composable
private fun TrackingOffBanner(onEnable: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Trek.warnWash)
            .clickable(role = Role.Button, onClick = onEnable)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = Trek.warn, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("Tracking is paused", style = MaterialTheme.typography.titleMedium, color = Trek.text)
            Text("Tap to enable in Accessibility settings", style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
        }
    }
}
