package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.shareTrekCard
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.LANDMARKS
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.dayOverDayDelta
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.monthlyBuckets
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.weekOverWeekDelta
import com.thumbtrek.app.stats.weeklyBuckets
import com.thumbtrek.app.widget.TrekWidgetProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val GUTTER = 20.dp

// ---------------------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------------------

/** Index of the Social panel in the nav bar, named so an invite can jump straight to it. */
private const val SOCIAL_TAB = 2

/**
 * [pendingInvite] is a friend code that arrived by invite link. It jumps straight to the
 * Social tab so the trek request is one tap from where the link left you, and is reported
 * spent through [onInviteConsumed] so a rotation doesn't drag you back there.
 */
@Composable
fun MainScreen(
    vm: DashboardViewModel,
    pendingInvite: String? = null,
    onInviteConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refreshTrackingState()
        TrekWidgetProvider.updateAll(context)
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val slideDuration = trekDuration(TrekDur.MEDIUM)

    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) tab = SOCIAL_TAB
    }

    Scaffold(
        containerColor = Trek.ground,
        contentColor = Trek.ink,
        topBar = { TrekTopRail(tab, state) },
        bottomBar = { TrekNavBar(tab) { tab = it } },
    ) { padding ->
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                // Panels slide in from the side you moved toward, so the four tabs feel
                // like one strip you are travelling along rather than four dead ends.
                val forward = targetState > initialState
                val enter = fadeIn(tween(slideDuration, easing = TrekEase)) +
                    slideInHorizontally(tween(slideDuration, easing = TrekEase)) {
                        if (forward) 48 else -48
                    }
                enter togetherWith fadeOut(tween(slideDuration / 2)) using
                    SizeTransform(clip = false)
            },
            label = "tab",
        ) { index ->
            when (index) {
                0 -> Dashboard(state)
                1 -> History(state)
                SOCIAL_TAB -> SocialScreen(
                    inviteCode = pendingInvite,
                    onInviteConsumed = onInviteConsumed,
                )
                else -> SettingsScreen()
            }
        }
    }
}

/**
 * Top rail. The wordmark stays put across tabs, the right-hand slot changes with context:
 * a share action on the dashboard, the date elsewhere. No Material TopAppBar, because its
 * 64dp of chrome and centered title bought nothing here.
 */
@Composable
private fun TrekTopRail(tab: Int, state: DashboardViewModel.UiState) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val today = remember { LocalDate.now() }
    val dateLabel = remember(today) {
        DateTimeFormatter.ofPattern("EEE d MMM").format(today).uppercase()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Trek.ground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = GUTTER, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "THUMBTREK",
                style = TrekOverline,
                color = Trek.moss,
            )
            Text(
                dateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Trek.inkFaint,
            )
        }
        if (tab == 0) {
            val todayMeters = pixelsToMeters(state.todayPx, dpi)
            IconAction(
                icon = Icons.Default.Share,
                label = "Share today's trek",
                enabled = state.todayPx > 0,
                onClick = {
                    shareTrekCard(
                        context = context,
                        distanceMeters = todayMeters,
                        comparisonLine = comparison(todayMeters),
                        streak = state.streak,
                        weekMeters = pixelsToMeters(state.weekPx, dpi),
                        perApp = state.perApp.map {
                            appName(it.packageName, state.customLabels) to
                                pixelsToMeters(it.pixels, dpi)
                        },
                    )
                },
            )
        }
    }
}

/** 48dp icon target with a real label for screen readers and a visible disabled state. */
@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Trek.ink else Trek.inkFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class TrekTab(val icon: ImageVector, val label: String)

private val TABS = listOf(
    TrekTab(Icons.Default.Home, "Trek"),
    TrekTab(Icons.Default.DateRange, "History"),
    TrekTab(Icons.Default.Person, "Social"),
    TrekTab(Icons.Default.Settings, "Settings"),
)

/**
 * Bottom navigation. A trail blaze slides along the top edge to mark where you are, which
 * is a marker on a route rather than Material's pill behind an icon: same information,
 * and it does not fight the data on the screen above it.
 */
@Composable
private fun TrekNavBar(selected: Int, onSelect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Trek.ground),
    ) {
        Hairline(color = Trek.hairline)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            val slot = maxWidth / TABS.size
            val blazeWidth = slot * 0.34f
            val offsetX by animateDpAsState(
                targetValue = slot * selected + (slot - blazeWidth) / 2,
                animationSpec = tween(trekDuration(TrekDur.MEDIUM), easing = TrekEase),
                label = "blaze",
            )
            Spacer(
                modifier = Modifier
                    .offset(x = offsetX)
                    .width(blazeWidth)
                    .height(2.dp)
                    .background(Trek.moss),
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                TABS.forEachIndexed { index, item ->
                    val active = index == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .selectable(
                                selected = active,
                                role = Role.Tab,
                                onClick = {
                                    if (!active) {
                                        haptics.performHapticFeedback(
                                            HapticFeedbackType.TextHandleMove,
                                        )
                                        onSelect(index)
                                    }
                                },
                            )
                            .padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = if (active) Trek.moss else Trek.inkFaint,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) Trek.ink else Trek.inkFaint,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------

@Composable
private fun Dashboard(state: DashboardViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val todayMeters = pixelsToMeters(state.todayPx, dpi)
    val byDate = remember(state.days) {
        state.days.associate { LocalDate.parse(it.date) to it.pixels }
    }
    val week = remember(byDate) { dailyBuckets(byDate, 7) }
    val hasEverTracked = state.days.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GUTTER)
            .padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (!state.trackingEnabled) {
            TrackingOffBanner(
                onEnable = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )
        }

        HeroDial(
            todayMeters = todayMeters,
            streak = state.streak,
            slices = state.perApp.map {
                val meters = pixelsToMeters(it.pixels, dpi)
                ChartSlice(
                    it.packageName,
                    appName(it.packageName, state.customLabels),
                    meters.toFloat(),
                    formatDistance(meters),
                )
            },
            delta = dayOverDayDelta(byDate),
        )

        LimitGlance(
            todayMeters = todayMeters,
            limitM = state.limitM,
            clean = state.todayClean,
            cleanStreak = state.cleanStreak,
        )

        if (todayMeters > 0.0) {
            LandmarkReading(todayMeters)
        }

        if (hasEverTracked) {
            WeekGlance(
                week = week.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                labels = week.map { it.label },
                weekMeters = pixelsToMeters(state.weekPx, dpi),
                delta = weekOverWeekDelta(byDate),
            )
        }

        if (state.perApp.isNotEmpty()) {
            AppSplit(state, dpi, todayMeters)
        }

        if (!hasEverTracked || !state.trackingEnabled || state.streak < 2) {
            FirstTrekChecklist(
                trackingOn = state.trackingEnabled,
                hasScrolled = state.todayPx > 0 || hasEverTracked,
                hasStreak = state.streak >= 2,
            )
        }

        Text(
            "Distance only. ThumbTrek never reads what is on your screen, and nothing " +
                "leaves this phone until you opt into a leaderboard.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkFaint,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * The screen people open daily. The distance sits inside the instrument that measured it,
 * with the streak in the break at the bottom of the dial, so one glance answers "how far",
 * "on what" and "am I still going".
 */
@Composable
private fun HeroDial(
    todayMeters: Double,
    streak: Int,
    slices: List<ChartSlice>,
    delta: Int?,
) {
    val readout = if (todayMeters > 0.0) {
        "Today's trek: ${formatDistance(todayMeters)}"
    } else {
        "Today's trek: nothing yet"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = readout },
        contentAlignment = Alignment.TopCenter,
    ) {
        TrekGauge(
            slices = slices,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            ringWidth = 15.dp,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(horizontal = 28.dp),
            ) {
                Text("TODAY'S TREK", style = TrekOverline, color = Trek.inkFaint)
                HeroDistance(todayMeters)
                if (delta != null && todayMeters > 0.0) {
                    DeltaMark(delta, "on yesterday")
                }
            }
        }
        // Sits in the break the dial leaves open at the bottom.
        StreakBadge(
            streak,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = 6.dp),
        )
    }
}

/**
 * The landmark comparison, given somewhere to go. The multiple is the delight; the rail
 * underneath turns it into a reason to look again tomorrow rather than a one-line joke.
 */
@Composable
private fun LandmarkReading(meters: Double) {
    val next = remember(meters) { LANDMARKS.firstOrNull { it.meters > meters } }
    val floor = remember(meters) {
        LANDMARKS.lastOrNull { meters >= it.meters }?.meters ?: 0.0
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            comparison(meters),
            style = MaterialTheme.typography.bodyLarge,
            color = Trek.ink,
        )
        if (next != null) {
            val span = (next.meters - floor).coerceAtLeast(0.0001)
            Rail(
                fraction = ((meters - floor) / span).toFloat(),
                color = Trek.moss,
                height = 4.dp,
            )
            Text(
                "${formatDistance(next.meters - meters)} to go before ${next.label}.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkMuted,
            )
        }
    }
}

/**
 * The limit theme, given the dashboard slot right under the hero dial. Today's trek against
 * the daily cap, with the clean-days counter beside it: the streak that rewards scrolling
 * less sits next to the trek streak in the dial, not instead of it.
 */
@Composable
private fun LimitGlance(
    todayMeters: Double,
    limitM: Float,
    clean: Boolean,
    cleanStreak: Int,
) {
    val limit = limitM.toDouble().coerceAtLeast(1.0)
    val caption = when {
        !clean -> "Over the limit today — tomorrow under ${formatDistance(limit)} starts a new run."
        cleanStreak > 1 -> "$cleanStreak clean days in a row — stay under ${formatDistance(limit)} to keep it."
        else -> "On track — finish today under ${formatDistance(limit)} for a clean day."
    }
    Column {
        SectionHead(
            "Daily limit",
            trailing = if (cleanStreak > 0) "$cleanStreak CLEAN" else null,
        )
        TrekPanel {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (clean) "On track" else "Over limit",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (clean) Trek.ink else Trek.danger,
                )
                Text(
                    "${formatDistance(todayMeters)} of ${formatDistance(limit)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.inkMuted,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Rail(
                fraction = (todayMeters / limit).toFloat(),
                color = if (clean) Trek.moss else Trek.danger,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkMuted,
            )
        }
    }
}

@Composable
private fun WeekGlance(
    week: List<Float>,
    labels: List<String>,
    weekMeters: Double,
    delta: Int?,
) {
    Column {
        SectionHead("This week", trailing = formatDistance(weekMeters))
        WeekPulse(values = week)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                labels.firstOrNull().orEmpty(),
                style = TrekOverline,
                color = Trek.inkFaint,
            )
            DeltaMark(delta, "on last week")
            Text("TODAY", style = TrekOverline, color = Trek.ink)
        }
    }
}

@Composable
private fun AppSplit(state: DashboardViewModel.UiState, dpi: Int, todayMeters: Double) {
    val busiest = state.perApp.firstOrNull()?.pixels ?: 0L
    Column {
        SectionHead("Where it went", trailing = "${state.perApp.size} APPS")
        state.perApp.forEach { app ->
            val meters = pixelsToMeters(app.pixels, dpi)
            val share = if (todayMeters > 0) (meters / todayMeters * 100).toInt() else 0
            MeasureRow(
                label = appName(app.packageName, state.customLabels),
                value = formatDistance(meters),
                fraction = if (busiest > 0) app.pixels.toFloat() / busiest else 0f,
                color = chartColor(app.packageName),
                leading = { SeriesDot(chartColor(app.packageName)) },
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
            ChecklistStep(1, "Turn on tracking", "One Accessibility toggle. Nothing else.", trackingOn)
            Hairline(modifier = Modifier.padding(horizontal = 14.dp))
            ChecklistStep(
                2,
                "Go scroll a feed",
                "Open Instagram, YouTube, X or Reddit and come back.",
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
                    if (done) Trek.moss else Color.Transparent,
                    CircleShape,
                )
                .border(1.dp, if (done) Trek.moss else Trek.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Trek.onMoss,
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Text(index.toString(), style = MaterialTheme.typography.labelSmall, color = Trek.inkFaint)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (done) Trek.inkMuted else Trek.ink,
            )
            Text(body, style = MaterialTheme.typography.bodySmall, color = Trek.inkFaint)
        }
    }
}

@Composable
private fun TrackingOffBanner(onEnable: () -> Unit) {
    TrekPanel(
        fill = Trek.amberWash,
        border = Trek.amber.copy(alpha = 0.35f),
    ) {
        Text("Tracking is paused", style = MaterialTheme.typography.titleLarge, color = Trek.amber)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "ThumbTrek counts scroll distance through its Accessibility Service. Nothing is " +
                "measured while that is off, and it never reads screen content.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.ink,
        )
        Spacer(modifier = Modifier.height(14.dp))
        TrekButton("Turn on tracking", onEnable)
    }
}

// ---------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------

private val RANGES = listOf("7 days", "8 weeks", "6 months")

@Composable
private fun History(state: DashboardViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    var range by rememberSaveable { mutableIntStateOf(0) }

    val byDate = remember(state.days) {
        state.days.associate { LocalDate.parse(it.date) to it.pixels }
    }
    val buckets = remember(byDate, range) {
        when (range) {
            0 -> dailyBuckets(byDate, 7)
            1 -> weeklyBuckets(byDate, 8)
            else -> monthlyBuckets(byDate, 6)
        }
    }
    val allTime = remember(byDate) { byDate.values.sum() }

    if (state.days.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = GUTTER),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            SectionHead("History")
            EmptyState(
                title = "Nothing logged yet",
                body = "Once tracking has run for a day, this page fills with your daily " +
                    "totals, per-app trends, your longest trek and the checkpoints you have " +
                    "passed. Give it one session.",
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        item("total") {
            Column {
                Text("ALL TIME", style = TrekOverline, color = Trek.inkFaint)
                Spacer(modifier = Modifier.height(4.dp))
                CountedDistance(
                    pixelsToMeters(allTime, dpi),
                    style = MaterialTheme.typography.displayLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "over ${state.days.size} tracked ${if (state.days.size == 1) "day" else "days"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.inkMuted,
                )
            }
        }

        item("range") {
            Column {
                TrekSegmented(RANGES, range, onSelect = { range = it })
                Spacer(modifier = Modifier.height(18.dp))
                HistoryBars(
                    bars = buckets.map {
                        ChartBar(it.label, pixelsToMeters(it.pixels, dpi).toFloat())
                    },
                    highlight = buckets.lastIndex,
                )
            }
        }

        item("records") {
            Column {
                SectionHead("Records")
                val record = state.record
                if (record != null) {
                    LedgerLine(
                        label = "Longest single day",
                        value = formatDistance(pixelsToMeters(record.second, dpi)),
                        caption = record.first.format(dateFormat),
                        accent = Trek.amber,
                    )
                }
                val bestWeek = remember(byDate) {
                    weeklyBuckets(byDate, 52).maxByOrNull { it.pixels }
                }
                if (bestWeek != null && bestWeek.pixels > 0) {
                    LedgerLine(
                        label = "Best week",
                        value = formatDistance(pixelsToMeters(bestWeek.pixels, dpi)),
                        caption = "week of ${bestWeek.label}",
                        accent = Trek.amber,
                    )
                }
                LedgerLine(
                    label = "Current streak",
                    value = if (state.streak > 0) "${state.streak} days" else "None",
                    caption = if (state.streak > 0) "keep it going" else "scroll today to start one",
                    accent = if (state.streak > 0) Trek.amber else Trek.inkFaint,
                )
            }
        }

        if (state.appTrends.isNotEmpty()) {
            item("trends") {
                Column {
                    SectionHead("App trends", trailing = "14 DAYS")
                    TrendLines(
                        series = state.appTrends.map { trend ->
                            ChartSeries(
                                trend.packageName,
                                appName(trend.packageName, state.customLabels),
                                trend.points.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                            )
                        },
                        labels = state.appTrends.first().points.map { it.label },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    state.appTrends.forEach { trend ->
                        ChartLegendRow(
                            color = chartColor(trend.packageName),
                            label = appName(trend.packageName, state.customLabels),
                            value = formatDistance(
                                pixelsToMeters(trend.points.sumOf { it.pixels }, dpi),
                            ),
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }
            }
        }

        if (state.badges.isNotEmpty()) {
            item("checkpoints") {
                CheckpointShelf(state.badges)
            }
        }

        item("logHead") {
            SectionHead("Day log", trailing = "${state.days.size} DAYS")
        }

        val busiestDay = state.days.maxOfOrNull { it.pixels } ?: 1L
        var lastMonth: String? = null
        state.days.forEach { day ->
            val date = LocalDate.parse(day.date)
            val month = monthFormat.format(date)
            if (month != lastMonth) {
                lastMonth = month
                item("m-$month") {
                    Text(
                        month.uppercase(),
                        style = TrekOverline,
                        color = Trek.inkFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(day.date) {
                val meters = pixelsToMeters(day.pixels, dpi)
                MeasureRow(
                    label = date.format(dateFormat),
                    value = formatDistance(meters),
                    fraction = if (busiestDay > 0) day.pixels.toFloat() / busiestDay else 0f,
                    color = if (date == LocalDate.now()) Trek.moss else Trek.slate,
                    labelColor = if (date == LocalDate.now()) Trek.ink else Trek.inkMuted,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${date.format(dateFormat)}: ${formatDistance(meters)}"
                    },
                )
            }
        }
    }
}

/** A record: label on the left, figure on the right, the "why" underneath in small type. */
@Composable
private fun LedgerLine(label: String, value: String, caption: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Trek.ink,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Trek.inkFaint)
    }
}

/**
 * Checkpoints as a shelf you walk along, not a wall of locks. Earned badges lead, then the
 * ones closest to falling, each showing how far in it actually is.
 */
@Composable
private fun CheckpointShelf(badges: List<Badge>) {
    val ordered = remember(badges) {
        badges.sortedWith(
            compareByDescending<Badge> { it.earned }.thenByDescending { it.progress },
        )
    }
    val earned = ordered.count { it.earned }
    val next = ordered.firstOrNull { !it.earned }

    Column {
        SectionHead("Checkpoints", trailing = "$earned / ${badges.size}")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(ordered, key = { it.id }) { badge ->
                CheckpointTile(badge)
            }
        }
        if (next != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Next up: ${next.detail.lowercase()} (${percentCaption(next.progress)}).",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkMuted,
            )
        }
    }
}

@Composable
private fun CheckpointTile(badge: Badge) {
    val tint = if (badge.earned) Trek.moss else Trek.inkFaint
    Column(
        modifier = Modifier
            .width(92.dp)
            .background(
                if (badge.earned) Trek.mossWash else Trek.groundRaised,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                if (badge.earned) Trek.moss.copy(alpha = 0.4f) else Trek.hairline,
                MaterialTheme.shapes.medium,
            )
            .padding(vertical = 14.dp, horizontal = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (badge.earned) {
                    "${badge.label}, earned"
                } else {
                    "${badge.label}, ${percentCaption(badge.progress)} complete. ${badge.detail}"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProgressRing(
            progress = if (badge.earned) 1f else badge.progress.toFloat(),
            size = 42.dp,
            color = if (badge.earned) Trek.moss else Trek.slate,
        ) {
            Text(badge.emoji, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
