package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.shareTrekCard
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.widget.TrekWidgetProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: DashboardViewModel) {
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.refreshTrackingState()
        TrekWidgetProvider.updateAll(context)
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "ThumbTrek",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                TrekTab(tab == 0, { tab = 0 }, Icons.Default.Home, "Trek")
                TrekTab(tab == 1, { tab = 1 }, Icons.Default.DateRange, "History")
                TrekTab(tab == 2, { tab = 2 }, Icons.Default.Person, "Social")
                TrekTab(tab == 3, { tab = 3 }, Icons.Default.Settings, "Settings")
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (tab) {
            0 -> Dashboard(state, modifier)
            1 -> History(state, modifier)
            2 -> SocialScreen(modifier)
            else -> SettingsScreen(modifier)
        }
    }
}

@Composable
private fun RowScope.TrekTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun Dashboard(state: DashboardViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val todayMeters = pixelsToMeters(state.todayPx, dpi)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.trackingEnabled) {
            TrackingOffBanner(onEnable = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
        }

        TrekCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "TODAY'S TREK",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        formatDistance(todayMeters),
                        style = MaterialTheme.typography.displayLarge,
                    )
                    Text(
                        comparison(todayMeters),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.streak > 0) {
                        Spacer(modifier = Modifier.height(2.dp))
                        StreakPill(state.streak)
                    }
                }
                IconButton(
                    onClick = {
                        shareTrekCard(
                            context = context,
                            distanceMeters = todayMeters,
                            comparisonLine = comparison(todayMeters),
                            streak = state.streak,
                            perApp = state.perApp.map {
                                it.packageName to pixelsToMeters(it.pixels, dpi)
                            },
                        )
                    },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share today's trek")
                }
            }
        }

        if (state.perApp.isNotEmpty()) {
            TrekCard {
                Text("By app", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                AppSplitDonut(
                    slices = state.perApp.map {
                        val meters = pixelsToMeters(it.pixels, dpi)
                        ChartSlice(
                            it.packageName,
                            appName(it.packageName, state.customLabels),
                            meters.toFloat(),
                            formatDistance(meters),
                        )
                    },
                    centerLabel = "Today",
                    centerValue = formatDistance(todayMeters),
                )
            }
        }

        Text(
            "Everything stays on your device. ThumbTrek only measures distance — never content.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackingOffBanner(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TrekAmber.copy(alpha = 0.12f), MaterialTheme.shapes.large)
            .border(1.dp, TrekAmber.copy(alpha = 0.4f), MaterialTheme.shapes.large)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Tracking is off", style = MaterialTheme.typography.titleMedium, color = TrekAmber)
        Text(
            "ThumbTrek needs its Accessibility Service to count how far you scroll " +
                "in Instagram, YouTube, X and Reddit. It never reads screen content.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onEnable) {
            Text("Enable tracking")
        }
    }
}

@Composable
private fun StreakPill(streak: Int) {
    Row(
        modifier = Modifier
            .background(TrekAmber.copy(alpha = 0.15f), MaterialTheme.shapes.extraLarge)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("🔥", style = MaterialTheme.typography.bodyMedium)
        Text(
            "$streak-day streak",
            style = MaterialTheme.typography.labelLarge,
            color = TrekAmber,
        )
    }
}

@Composable
private fun History(state: DashboardViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.badges.any { it.earned }) {
            item { BadgesCard(state.badges) }
        }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard("This week", formatDistance(pixelsToMeters(state.weekPx, dpi)), Modifier.weight(1f))
                StatCard("This month", formatDistance(pixelsToMeters(state.monthPx, dpi)), Modifier.weight(1f))
            }
        }

        state.record?.let { (date, px) ->
            item {
                TrekCard {
                    Text("Longest trek", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${formatDistance(pixelsToMeters(px, dpi))} on ${date.format(dateFormat)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }

        if (state.dailyBuckets.any { it.pixels > 0 }) {
            item {
                TrekCard {
                    Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    HistoryBarChart(state.dailyBuckets.map {
                        ChartBar(it.label, pixelsToMeters(it.pixels, dpi).toFloat())
                    })
                }
            }
        }

        if (state.appTrends.isNotEmpty()) {
            item {
                TrekCard {
                    Text("App trends", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTrendChart(
                        series = state.appTrends.map { trend ->
                            ChartSeries(
                                trend.packageName,
                                appName(trend.packageName, state.customLabels),
                                trend.points.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                            )
                        },
                        labels = state.appTrends.first().points.map { it.label },
                    )
                }
            }
        }

        if (state.days.isEmpty()) {
            item {
                Text(
                    "No treks yet. Enable tracking and go scroll.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.days) { day ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(LocalDate.parse(day.date).format(dateFormat))
                    Text(
                        formatDistance(pixelsToMeters(day.pixels, dpi)),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    TrekCard(modifier = modifier, padding = PaddingValues(16.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.headlineMedium)
    }
}

/** Earned badges first, then the closest locked ones — a shelf to fill, not a wall of locks. */
@Composable
private fun BadgesCard(allBadges: List<Badge>) {
    TrekCard {
        Text("Checkpoints", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        val ordered = allBadges.sortedWith(
            compareByDescending<Badge> { it.earned }.thenByDescending { it.progress },
        ).take(6)
        ordered.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { badge ->
                    BadgeCell(badge, Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        allBadges.firstOrNull { !it.earned }?.let { next ->
            Text(
                "Next: ${next.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BadgeCell(badge: Badge, modifier: Modifier = Modifier) {
    val tint = if (badge.earned) MaterialTheme.colorScheme.primary else TrekBadgeLocked
    Column(
        modifier = modifier
            .background(
                if (badge.earned) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else TrekBadgeLockedBg,
                MaterialTheme.shapes.medium,
            )
            .then(
                if (badge.earned) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(badge.emoji, style = MaterialTheme.typography.headlineSmall)
        Text(
            badge.label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            color = tint,
        )
    }
}

private val TrekBadgeLocked = Color(0xFF6E7A62)
private val TrekBadgeLockedBg = Color(0xFF1B1F16)
