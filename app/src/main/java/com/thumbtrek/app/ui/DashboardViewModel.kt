package com.thumbtrek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.AppTotal
import com.thumbtrek.app.data.DayTotal
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.personalRecord
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
    )

    private val dao = ScrollDatabase.get(app).dao()
    private val trackingEnabled = MutableStateFlow(ScrollTrackerService.isEnabled(app))

    val state = combine(
        dao.observeDay(LocalDate.now().toString()),
        dao.observeAllDays(),
        trackingEnabled,
    ) { perApp, allDays, enabled ->
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
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun refreshTrackingState() {
        trackingEnabled.value = ScrollTrackerService.isEnabled(getApplication())
    }
}
