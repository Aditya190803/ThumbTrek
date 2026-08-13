package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.shareTrekCard
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: DashboardViewModel) {
    LifecycleResumeEffect(Unit) {
        vm.refreshTrackingState()
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ThumbTrek") }) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Trek") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.DateRange, contentDescription = null) },
                    label = { Text("History") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Person, contentDescription = null) },
                    label = { Text("Social") },
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Tracking is off", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "ThumbTrek needs its Accessibility Service to count how far you scroll " +
                            "in Instagram, YouTube, X and Reddit. It never reads screen content.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) {
                        Text("Enable tracking")
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Today's trek",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    formatDistance(todayMeters),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    comparison(todayMeters),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.streak > 0) {
                    Text(
                        "🔥 ${state.streak}-day trek streak",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share today's trek")
                }
            }
        }

        if (state.perApp.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("By app", style = MaterialTheme.typography.titleMedium)
                    AppSplitDonut(
                        slices = state.perApp.map {
                            val meters = pixelsToMeters(it.pixels, dpi)
                            ChartSlice(it.packageName, appName(it.packageName), meters.toFloat(), formatDistance(meters))
                        },
                        centerLabel = "Today",
                        centerValue = formatDistance(todayMeters),
                    )
                }
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
private fun History(state: DashboardViewModel.UiState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Longest trek", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatDistance(pixelsToMeters(px, dpi))} on ${date.format(dateFormat)}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }

        if (state.dailyBuckets.any { it.pixels > 0 }) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
                        HistoryBarChart(state.dailyBuckets.map {
                            ChartBar(it.label, pixelsToMeters(it.pixels, dpi).toFloat())
                        })
                    }
                }
            }
        }

        if (state.appTrends.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("App trends", style = MaterialTheme.typography.titleMedium)
                        AppTrendChart(
                            series = state.appTrends.map { trend ->
                                ChartSeries(
                                    trend.packageName,
                                    appName(trend.packageName),
                                    trend.points.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                                )
                            },
                            labels = state.appTrends.first().points.map { it.label },
                        )
                    }
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
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}
