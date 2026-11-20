package com.thumbtrek.app.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.shareTrekCard
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.widget.TrekWidgetProvider
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

internal val GUTTER = 24.dp

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
        containerColor = Trek.bg,
        contentColor = Trek.text,
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
            .background(Trek.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = GUTTER, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(Modifier.size(40.dp, 24.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "ThumbTrek",
                style = MaterialTheme.typography.titleMedium,
                color = Trek.text,
            )
            Text(
                dateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Trek.textFaint,
            )
        }
        if (tab == 0) {
            val todayMeters = pixelsToMeters(state.todayPx, dpi)
            IconAction(
                icon = Icons.Default.Share,
                label = "Share today's distance",
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
internal fun IconAction(
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
            tint = if (enabled) Trek.text else Trek.textFaint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class TrekTab(val icon: ImageVector, val label: String)

private val TABS = listOf(
    TrekTab(Icons.Default.Home, "Today"),
    TrekTab(Icons.Default.DateRange, "History"),
    TrekTab(Icons.Default.Person, "Friends"),
    TrekTab(Icons.Default.Settings, "Settings"),
)

/**
 * Four destinations. The selected one sits in an accent stadium with its label; the others
 * are icons with the label read out. Tapping the tab you are already on scrolls it back to
 * the top, as on every other Android app.
 */
@Composable
private fun TrekNavBar(selected: Int, onSelect: (Int) -> Unit, onReselect: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Trek.bg)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEachIndexed { index, item ->
            val active = index == selected
            Row(
                modifier = Modifier
                    .weight(if (active) 1.7f else 1f)
                    .heightIn(min = 52.dp)
                    .clip(CircleShape)
                    .background(if (active) Trek.accent else Color.Transparent)
                    .selectable(
                        selected = active,
                        role = Role.Tab,
                        onClick = {
                            if (!active) {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            } else {
                                onReselect(index)
                            }
                        },
                    )
                    .semantics { contentDescription = item.label }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (active) Trek.onAccent else Trek.textDim,
                    modifier = Modifier.size(22.dp),
                )
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = Trek.onAccent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
