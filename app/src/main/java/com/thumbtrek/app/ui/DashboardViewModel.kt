package com.thumbtrek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.AppTotal
import com.thumbtrek.app.data.DayTotal
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.badges
import com.thumbtrek.app.stats.personalRecord
import com.thumbtrek.app.stats.AppSeries
import com.thumbtrek.app.stats.Bucket
import com.thumbtrek.app.stats.appTrends
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisMonth
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import com.thumbtrek.app.track.ScrollTrackerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val trackingEnabled: Boolean = false,
        val todayPx: Long = 0,
        val perApp: List<AppTotal> = emptyList(),
        val streak: Int = 0,
        val weekPx: Long = 0,
        val monthPx: Long = 0,
        val record: Pair<LocalDate, Long>? = null,
        val days: List<DayTotal> = emptyList(),
        val dailyBuckets: List<Bucket> = emptyList(),
        val appTrends: List<AppSeries> = emptyList(),
        /** Labels for user-added apps, so display names survive without PackageManager. */
        val customLabels: Map<String, String> = emptyMap(),
        val badges: List<Badge> = emptyList(),
    )

    private val dao = ScrollDatabase.get(app).dao()
    private val trackingEnabled = MutableStateFlow(ScrollTrackerService.isEnabled(app))
    private val dpi = app.resources.displayMetrics.densityDpi

    val state = combine(
        dao.observeDay(LocalDate.now().toString()),
        dao.observeAllDays(),
        dao.observeRowsSince(LocalDate.now().minusDays(13).toString()),
        trackingEnabled,
        Prefs.get(app).customApps,
    ) { perApp, allDays, rows, enabled, customLabels ->
        val byDate = allDays.associate { LocalDate.parse(it.date) to it.pixels }
        UiState(
            trackingEnabled = enabled,
            todayPx = perApp.sumOf { it.pixels },
            perApp = perApp.sortedByDescending { it.pixels },
            streak = trekStreak(byDate.keys),
            weekPx = totalThisWeek(byDate),
            monthPx = totalThisMonth(byDate),
            record = personalRecord(byDate),
            days = allDays,
            dailyBuckets = dailyBuckets(byDate),
            appTrends = appTrends(rows),
            customLabels = customLabels,
            // Calibration is baked into stored pixels, so these milestones are apples-to-apples.
            badges = badges(
                totalMeters = pixelsToMeters(byDate.values.sum(), dpi),
                bestDayMeters = pixelsToMeters(byDate.values.maxOrNull() ?: 0L, dpi),
                streak = trekStreak(byDate.keys),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun refreshTrackingState() {
        trackingEnabled.value = ScrollTrackerService.isEnabled(getApplication())
    }
}
