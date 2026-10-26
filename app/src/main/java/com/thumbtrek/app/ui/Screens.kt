package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.shareTrekCard
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.AppSeries
import com.thumbtrek.app.stats.Bucket
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.dayOverDayDelta
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.BASELINE_DAYS
import com.thumbtrek.app.stats.limitNudgeLevel
import com.thumbtrek.app.stats.monthlyBuckets
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.weekOverWeekDelta
import com.thumbtrek.app.stats.weeklyBuckets
import com.thumbtrek.app.widget.TrekWidgetProvider
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val GUTTER = 24.dp

// ---------------------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------------------

/** Index of the Social panel in the nav bar, named so an invite can jump straight to it. */
private const val SOCIAL_TAB = 2
private const val SETTINGS_TAB = 3

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

    // Hoisted out of the tabs so each keeps its place when you switch away and back, and
    // so tapping the tab you are already on can return it to the top.
    val dashboardScroll = rememberScrollState()
    val historyList = rememberLazyListState()
    val socialList = rememberLazyListState()
    val settingsScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingInvite) {
        if (pendingInvite != null) tab = SOCIAL_TAB
    }

    Scaffold(
        containerColor = Trek.ground,
        contentColor = Trek.ink,
        topBar = { TrekTopRail(tab, state) },
        bottomBar = {
            TrekNavBar(
                selected = tab,
                onSelect = { tab = it },
                onReselect = { index ->
                    scope.launch {
                        when (index) {
                            0 -> dashboardScroll.animateScrollTo(0)
                            1 -> historyList.animateScrollToItem(0)
                            SOCIAL_TAB -> socialList.animateScrollToItem(0)
                            else -> settingsScroll.animateScrollTo(0)
                        }
                    }
                },
            )
        },
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
                0 -> Dashboard(
                    state = state,
                    scrollState = dashboardScroll,
                    onKeepAutoLimit = vm::dismissAutoLimitNotice,
                    onDeclineAutoLimit = vm::declineAutoLimit,
                    onAdjustLimit = {
                        vm.dismissAutoLimitNotice()
                        tab = SETTINGS_TAB
                    },
                )
                1 -> History(state, historyList)
                SOCIAL_TAB -> SocialScreen(
                    inviteCode = pendingInvite,
                    onInviteConsumed = onInviteConsumed,
                    listState = socialList,
                )
                else -> SettingsScreen(scrollState = settingsScroll)
            }
        }
    }
}

@Composable
private fun TrekTopRail(tab: Int, state: DashboardViewModel.UiState) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateLabel = remember(state.today) {
        DateTimeFormatter.ofPattern("EEEE, d MMM").format(state.today)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Trek.ground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = GUTTER, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.size(30.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ThumbTrek",
                style = MaterialTheme.typography.titleMedium,
                color = Trek.ink,
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
    TrekTab(Icons.Default.Home, "Today"),
    TrekTab(Icons.Default.DateRange, "History"),
    TrekTab(Icons.Default.Person, "Social"),
    TrekTab(Icons.Default.Settings, "Settings"),
)

/**
 * Familiar tabs with a soft, selected pill and a full touch target. Tapping the tab you
 * are already on scrolls it back to the top, as on every other Android app.
 */
@Composable
private fun TrekNavBar(selected: Int, onSelect: (Int) -> Unit, onReselect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    NavigationBar(containerColor = Trek.ground, tonalElevation = 0.dp) {
        TABS.forEachIndexed { index, item ->
            NavigationBarItem(
                selected = index == selected,
                onClick = {
                    if (index != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(index)
                    } else {
                        onReselect(index)
                    }
                },
                icon = { Icon(item.icon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Trek.accent,
                    selectedTextColor = Trek.accent,
                    indicatorColor = Trek.accentWash,
                    unselectedIconColor = Trek.inkMuted,
                    unselectedTextColor = Trek.inkMuted,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Dashboard
// ---------------------------------------------------------------------------------------

@Composable
private fun Dashboard(
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
                ChartSlice(it.packageName, appName(it.packageName, state.customLabels), meters.toFloat(), formatDistance(meters))
            },
            delta = dayOverDayDelta(byDate, state.today),
            yesterdayMeters = pixelsToMeters(byDate[state.today.minusDays(1)] ?: 0L, dpi),
            limitM = state.limitM,
            cleanStreak = state.cleanStreak,
            baselineDays = state.baselineDays.takeIf { state.baselineSince != null },
        )

        if (state.perApp.isNotEmpty()) {
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
            color = Trek.inkFaint,
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** The app-colored dial answers how far and where; the separate rail shows the limit. */
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
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            TrekGauge(
                slices = slices,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Today: ${formatDistance(todayMeters)} scrolled. " +
                            slices.joinToString { "${it.label}: ${it.valueLabel}" }
                    },
                ringWidth = 15.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(0.64f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Today's trek", style = TrekOverline, color = Trek.inkMuted)
                    HeroDistance(todayMeters, centered = true)
                }
            }
            StreakBadge(streak, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            if (todayMeters > 0.0) comparison(todayMeters) else "Your first scroll starts the trail.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.inkMuted,
            textAlign = TextAlign.Center,
        )
        // Today is still running, so a percentage against all of yesterday reads as a big
        // drop every morning. Show the percentage only once today has caught up (it can
        // only grow from there); before that, yesterday's total is the honest reference.
        if (delta != null && delta >= 0 && todayMeters > 0.0) {
            DeltaMark(delta, "vs yesterday")
        } else if (yesterdayMeters > 0.0) {
            Text(
                "Yesterday: ${formatDistance(yesterdayMeters)}",
                style = MaterialTheme.typography.labelMedium,
                color = Trek.inkMuted,
            )
        }
        TrekPanel {
            when {
                limitM != null -> LimitGlance(todayMeters, limitM, cleanStreak)
                baselineDays != null -> BaselineGlance(baselineDays)
                else -> {
                    Text("No daily limit", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                    Spacer(Modifier.height(6.dp))
                    Text("You’re tracking without a cap. Set a limit or learn your baseline anytime in Settings.", style = MaterialTheme.typography.bodyMedium, color = Trek.inkMuted)
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
            Text("Learning your baseline", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Trek.ink)
            Text("Day $days of $BASELINE_DAYS", style = TrekFigure, color = Trek.inkMuted)
        }
        Spacer(Modifier.height(12.dp))
        Rail(days / BASELINE_DAYS.toFloat(), color = Trek.slate, track = Trek.slate.copy(alpha = 0.12f), height = 6.dp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (days == 0) "Scroll as you normally would. Nothing is judged this week."
            else "After $BASELINE_DAYS tracked days, your limit is set about 10% under your usual day. Nothing is judged until then.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.inkMuted,
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
    TrekPanel(fill = Trek.accentWash, border = Trek.accent.copy(alpha = 0.35f)) {
        Text("Your daily limit is set", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your usual day is about ${formatDistance(baselineM)}, so your limit is now " +
                "${formatDistance(limitM)}. Clean days count from today.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.inkMuted,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrekButton("Sounds good", onKeep, Modifier.weight(1f))
            TrekGhostButton("Adjust", onAdjust, Modifier.weight(1f))
        }
        TextButton(onClick = onDecline) { Text("I’d rather not have a limit", color = Trek.inkMuted) }
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
        1 -> Trek.amber
        else -> Trek.success
    }
    val remaining = formatDistance((limit - todayMeters).coerceAtLeast(0.0))
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Daily limit", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Trek.ink)
            Text(
                "${formatDistance(todayMeters)} of ${formatDistance(limit)}",
                style = TrekFigure,
                color = Trek.inkMuted,
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
            Text("$cleanStreak ${if (cleanStreak == 1) "day" else "days"} within your limit", style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
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
            barColor = Trek.accent.copy(alpha = 0.4f),
            guide = limitM,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { DeltaMark(delta, "vs last week") }
            if (limitM != null && week.any { it > 0f }) {
                LimitKey()
            }
        }
    }
}

/** Legend for the dashed limit line drawn over the bar charts. */
@Composable
private fun LimitKey() {
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
        Text("Daily limit", style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
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
                .border(1.dp, if (done) Trek.accent else Trek.hairline, CircleShape),
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
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Trek.amberWash)
            .clickable(role = Role.Button, onClick = onEnable)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Info, contentDescription = null, tint = Trek.amber, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text("Tracking is paused", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
            Text("Tap to enable in Accessibility settings", style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
        }
    }
}

// ---------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------

private val RANGES = listOf("7 days", "8 weeks", "6 months")

@Composable
private fun History(
    state: DashboardViewModel.UiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    var range by rememberSaveable { mutableIntStateOf(0) }
    val today = state.today

    val byDate = remember(state.days) {
        state.days.associate { LocalDate.parse(it.date) to it.pixels }
    }
    val buckets = remember(byDate, range, today) {
        when (range) {
            0 -> dailyBuckets(byDate, 7, today)
            1 -> weeklyBuckets(byDate, 8, today)
            else -> monthlyBuckets(byDate, 6, today)
        }
    }
    val allTime = remember(byDate) { byDate.values.sum() }

    if (!state.loaded) return

    if (state.days.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GUTTER, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PageHeading("History", "See how your habits are changing.")
            TrekPanel {
                HistoryBars(List(7) { ChartBar(listOf("M", "T", "W", "T", "F", "S", "S")[it], 0f) })
            }
            EmptyState(
                title = "Your story starts today",
                body = "Your daily totals, app trends and achievements will appear here after your first scroll.",
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        item("heading") { PageHeading("History", "See how your habits are changing.") }
        item("total") {
            Column {
                Text("Total distance", style = TrekOverline, color = Trek.inkMuted)
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
            // Latest period selected up front, so the detail below answers "how far was
            // that bar" before the first tap. Reset per range; tapping the active bar
            // clears back to an unselected chart.
            var selected by rememberSaveable(range) { mutableIntStateOf(buckets.lastIndex) }
            val haptics = LocalHapticFeedback.current
            TrekPanel {
                TrekSegmented(RANGES, range, onSelect = { range = it })
                Spacer(modifier = Modifier.height(18.dp))
                HistoryBars(
                    bars = buckets.map {
                        ChartBar(it.label, pixelsToMeters(it.pixels, dpi).toFloat())
                    },
                    highlight = buckets.lastIndex,
                    selected = selected,
                    onSelect = { tapped ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selected = if (tapped == selected) -1 else tapped
                    },
                    // The limit is per day, so it only means something on the day range.
                    guide = state.limitM.takeIf { range == 0 },
                )
                if (range == 0 && state.limitM != null && buckets.any { it.pixels > 0 }) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LimitKey()
                }
                Spacer(modifier = Modifier.height(14.dp))
                buckets.getOrNull(selected)?.let { bucket ->
                    PeriodDetail(
                        bucket = bucket,
                        range = range,
                        dpi = dpi,
                        trends = state.appTrends,
                        customLabels = state.customLabels,
                        dateFormat = dateFormat,
                        monthFormat = monthFormat,
                    )
                }
            }
        }

        if (state.limitM != null) item("cleanMonth") {
            var calDay by rememberSaveable { mutableStateOf<String?>(null) }
            // Months back from the current one. Bounded by the first tracked month, so
            // paging back never lands on a calendar that can only ever be empty.
            var monthsBack by rememberSaveable { mutableIntStateOf(0) }
            val thisMonth = YearMonth.from(today)
            val firstMonth = remember(byDate) {
                byDate.keys.minOrNull()?.let { YearMonth.from(it) } ?: thisMonth
            }
            val maxBack = (thisMonth.year - firstMonth.year) * 12 +
                (thisMonth.monthValue - firstMonth.monthValue)
            val shownMonth = thisMonth.minusMonths(monthsBack.coerceIn(0, maxBack).toLong())
            val (cleanDays, trackedDays) = remember(byDate, shownMonth, state.limitM, today) {
                val days = (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }
                    .filter { !it.isAfter(today) && byDate[it] != null }
                    .filter { date -> state.limitSince?.let { !date.isBefore(it) } ?: true }
                days.count { pixelsToMeters(byDate.getValue(it), dpi) <= state.limitM } to days.size
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Days in balance", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                        Text(
                            if (trackedDays == 0) monthFormat.format(shownMonth)
                            else "${monthFormat.format(shownMonth)} · $cleanDays of $trackedDays within limit",
                            style = MaterialTheme.typography.bodySmall,
                            color = Trek.inkMuted,
                        )
                    }
                    IconAction(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "Previous month",
                        enabled = monthsBack < maxBack,
                        onClick = { monthsBack++; calDay = null },
                    )
                    IconAction(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "Next month",
                        enabled = monthsBack > 0,
                        onClick = { monthsBack--; calDay = null },
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                CleanMonthGrid(
                    month = shownMonth,
                    today = today,
                    judgeFrom = state.limitSince,
                    byDate = byDate,
                    dpi = dpi,
                    limitM = state.limitM,
                    selectedDay = calDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    onSelect = { date ->
                        calDay = date?.toString()
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarKey(Trek.success, "Clean")
                    CalendarKey(Trek.danger, "Over")
                    CalendarKey(Trek.hairline, "No data")
                    if (state.limitSince != null && byDate.keys.any { it.isBefore(state.limitSince) }) {
                        CalendarKey(Trek.slate, "Before limit")
                    }
                }
                calDay?.let { key ->
                    val px = byDate[LocalDate.parse(key)] ?: 0L
                    Spacer(modifier = Modifier.height(14.dp))
                    PeriodDetail(
                        bucket = Bucket(
                            key,
                            LocalDate.parse(key).format(dateFormat),
                            px,
                        ),
                        range = 0,
                        dpi = dpi,
                        trends = state.appTrends,
                        customLabels = state.customLabels,
                        dateFormat = dateFormat,
                        monthFormat = monthFormat,
                    )
                }
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
                val bestWeek = remember(byDate, today) {
                    weeklyBuckets(byDate, 52, today).maxByOrNull { it.pixels }
                }
                if (bestWeek != null && bestWeek.pixels > 0) {
                    LedgerLine(
                        label = "Best week",
                        value = formatDistance(pixelsToMeters(bestWeek.pixels, dpi)),
                        caption = "week of ${bestWeek.label}",
                        accent = Trek.amber,
                    )
                }
                // Average over the days since tracking began, capped at a week, so a
                // two-day-old install isn't divided by seven empty days.
                val average = remember(byDate, today) {
                    val first = byDate.keys.minOrNull() ?: today
                    val span = (java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1)
                        .coerceIn(1, 7).toInt()
                    dailyBuckets(byDate, span, today).sumOf { it.pixels } / span to span
                }
                if (average.first > 0) {
                    LedgerLine(
                        label = "Daily average",
                        value = formatDistance(pixelsToMeters(average.first, dpi)),
                        caption = if (average.second == 7) "over the last 7 days"
                            else "over your first ${average.second} ${if (average.second == 1) "day" else "days"}",
                        accent = Trek.ink,
                    )
                }
                LedgerLine(
                    label = "Current streak",
                    value = if (state.streak > 0) "${state.streak} ${if (state.streak == 1) "day" else "days"}" else "None",
                    caption = if (state.streak > 0) "days in a row with tracked scrolling" else "scroll today to start one",
                    accent = if (state.streak > 0) Trek.amber else Trek.inkFaint,
                )
                if (state.limitM != null) {
                    LedgerLine(
                        label = "Within your limit",
                        value = "${state.cleanStreak} ${if (state.cleanStreak == 1) "day" else "days"}",
                        caption = if (state.cleanStreak > 0) "in a row, including today"
                            else "stay under ${formatDistance(state.limitM.toDouble())} today to start a run",
                        accent = if (state.cleanStreak > 0) Trek.success else Trek.inkFaint,
                    )
                }
            }
        }

        if (state.appTrends.isNotEmpty()) {
            item("trends") {
                Column {
                    SectionHead("App trends", trailing = "14 days")
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
            SectionHead("Day log", trailing = "${state.days.size} days")
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
                        month,
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
                    color = if (date == today) Trek.accent else Trek.slate,
                    labelColor = if (date == today) Trek.ink else Trek.inkMuted,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${date.format(dateFormat)}: ${formatDistance(meters)}"
                    },
                )
            }
        }
    }
}

/**
 * The month as discipline at a glance: each past day is clean (mint wash), over
 * (danger wash), or untracked (hollow). Tapping a day selects it; the caller reads it back
 * through PeriodDetail. Monday-start, matching the app's week convention.
 */
@Composable
private fun CleanMonthGrid(
    month: YearMonth,
    today: LocalDate,
    judgeFrom: LocalDate?,
    byDate: Map<LocalDate, Long>,
    dpi: Int,
    limitM: Float,
    selectedDay: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val limit = limitM.toDouble()
    val cells = remember(month) {
        val first = month.atDay(1)
        val blanks = List(first.dayOfWeek.value - 1) { null as LocalDate? }
        (blanks + (1..month.lengthOfMonth()).map { month.atDay(it) }).chunked(7)
    }
    val shape = RoundedCornerShape(10.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { head ->
                Text(
                    head,
                    modifier = Modifier.weight(1f),
                    style = TrekOverline,
                    color = Trek.inkFaint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        cells.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val px = byDate[date]
                        val meters = pixelsToMeters(px ?: 0L, dpi)
                        val future = date.isAfter(today)
                        // Days before the limit existed are shown, never scored.
                        val unjudged = judgeFrom != null && date.isBefore(judgeFrom)
                        val tracked = !future && px != null
                        val over = tracked && !unjudged && meters > limit
                        val fill = when {
                            future || !tracked -> Color.Transparent
                            unjudged -> Trek.slateWash
                            over -> Trek.dangerWash
                            else -> Trek.successWash
                        }
                        val ink = when {
                            future -> Trek.inkFaint
                            over -> Trek.danger
                            tracked -> Trek.ink
                            else -> Trek.inkMuted
                        }
                        val selected = date == selectedDay
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(shape)
                                .background(fill, shape)
                                .border(
                                    1.dp,
                                    when {
                                        selected -> Trek.ink
                                        date == today -> Trek.success
                                        tracked || over -> Color.Transparent
                                        else -> Trek.hairlineSoft
                                    },
                                    shape,
                                )
                                .clickable(enabled = !future, role = Role.Button) {
                                    onSelect(if (selected) null else date)
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${date.format(
                                        DateTimeFormatter.ofPattern("EEE d MMM"),
                                    )}: " + when {
                                        future -> "upcoming"
                                        !tracked -> "no data"
                                        unjudged -> "${formatDistance(meters)}, before your limit"
                                        over -> "${formatDistance(meters)}, over the limit"
                                        else -> "${formatDistance(meters)}, clean"
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ink,
                            )
                        }
                    }
                }
                // Pad short final weeks so every row spans the full width.
                repeat(7 - week.size) {
                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

/** Legend swatch for the calendar: a dot and a word. */
@Composable
private fun CalendarKey(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesDot(color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
    }
}

/**
 * The tapped bar, read back: full period label, total, and — for single days inside the
 * trends window — the per-app split. Weeks, months, and older days read as a total plus
 * the landmark line.
 */
@Composable
private fun PeriodDetail(
    bucket: Bucket,
    range: Int,
    dpi: Int,
    trends: List<AppSeries>,
    customLabels: Map<String, String>,
    dateFormat: DateTimeFormatter,
    monthFormat: DateTimeFormatter,
) {
    val meters = pixelsToMeters(bucket.pixels, dpi)
    val title = when (range) {
        0 -> LocalDate.parse(bucket.key).format(dateFormat)
        1 -> "Week of ${bucket.label}"
        else -> YearMonth.parse(bucket.key).format(monthFormat)
    }
    val noun = when (range) {
        0 -> "day"
        1 -> "week"
        else -> "month"
    }
    val split = remember(bucket.key, trends) {
        if (range != 0) return@remember emptyList()
        // The trends window only reaches back 14 days; older days read as a total so a
        // missing split never prints "nothing measured" over a day that has metres.
        if (trends.none { trend -> trend.points.any { it.key == bucket.key } }) {
            return@remember null
        }
        trends.mapNotNull { trend ->
            trend.points.find { it.key == bucket.key }?.let { trend.packageName to it.pixels }
        }.sortedByDescending { it.second }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Trek.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDistance(meters),
                style = MaterialTheme.typography.headlineSmall,
                color = if (meters > 0.0) Trek.accent else Trek.inkFaint,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (meters > 0.0) comparison(meters) else "No trek logged that $noun.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkMuted,
        )
        if (range == 0 && meters > 0.0 && split != null) {
            Spacer(modifier = Modifier.height(10.dp))
            if (split.isEmpty()) {
                Text(
                    "Nothing measured that day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.inkFaint,
                )
            } else {
                split.forEach { (pkg, px) ->
                    ChartLegendRow(
                        color = chartColor(pkg),
                        label = appName(pkg, customLabels),
                        value = formatDistance(pixelsToMeters(px, dpi)),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
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
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = ordered.firstOrNull { it.id == picked }

    Column {
        SectionHead("Achievements", trailing = "$earned of ${badges.size} earned")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(ordered, key = { it.id }) { badge ->
                CheckpointTile(
                    badge = badge,
                    selected = badge.id == picked,
                    onClick = { picked = if (picked == badge.id) null else badge.id },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // A tapped tile explains itself; otherwise the shelf points at the nearest goal.
        Text(
            when {
                focus?.earned == true -> "${focus.label}: earned. ${focus.detail}."
                focus != null -> "${focus.label}: ${focus.detail.lowercase()} (${percentCaption(focus.progress)} there)."
                next != null -> "Next up: ${next.detail.lowercase()} (${percentCaption(next.progress)})."
                else -> "Every achievement earned. Impressive thumb."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkMuted,
        )
    }
}

@Composable
private fun CheckpointTile(badge: Badge, selected: Boolean, onClick: () -> Unit) {
    val tint = if (badge.earned) Trek.accent else Trek.inkFaint
    Column(
        modifier = Modifier
            .width(116.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (badge.earned) Trek.accentWash else Trek.groundRaised,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                if (selected) Trek.ink else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
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
            size = 52.dp,
            color = if (badge.earned) Trek.accent else Trek.slate,
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
