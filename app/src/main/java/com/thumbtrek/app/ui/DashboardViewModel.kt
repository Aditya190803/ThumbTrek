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
import com.thumbtrek.app.stats.isCleanDay
import com.thumbtrek.app.stats.limitStreak
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisMonth
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import com.thumbtrek.app.track.ScrollTrackerService
import com.thumbtrek.app.data.BaselineLimit
import com.thumbtrek.app.stats.BASELINE_DAYS
import com.thumbtrek.app.stats.trackedDaysSince
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        /** False until Room's first emission, so existing users never see first-run copy. */
        val loaded: Boolean = false,
        /** The day every "today" figure below belongs to. Rolls over at midnight. */
        val today: LocalDate = LocalDate.now(),
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
        // --- daily limit (PRD §11: clean-days counter sits next to the trek streak) ---
        val limitM: Float? = null,
        val todayClean: Boolean = true,
        val cleanStreak: Int = 0,
        /** First day the limit judges; earlier days stay neutral. Null: all of history. */
        val limitSince: LocalDate? = null,
        // --- baseline week ---
        /** Non-null while a baseline is being learned. */
        val baselineSince: LocalDate? = null,
        /** Tracked, completed days collected toward the baseline so far. */
        val baselineDays: Int = 0,
        /** The typical day behind a just-applied automatic limit, until acknowledged. */
        val autoLimitNotice: Float? = null,
    )

    private val dao = ScrollDatabase.get(app).dao()
    private val trackingEnabled = MutableStateFlow(ScrollTrackerService.isEnabled(app))
    private val dpi = app.resources.displayMetrics.densityDpi

    private val prefs = Prefs.get(app)

    /**
     * The current date as a flow. The day-scoped queries used to bind LocalDate.now() once,
     * at construction, so an app left open overnight kept showing yesterday as "today"
     * until the process died. This ticks just after midnight, and [refreshTrackingState]
     * re-reads it on every resume in case the timer slept through Doze.
     */
    private val today = MutableStateFlow(LocalDate.now())

    init {
        viewModelScope.launch {
            while (true) {
                val now = LocalDateTime.now()
                val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
                delay(Duration.between(now, midnight).toMillis() + 1_000)
                today.value = LocalDate.now()
                applyBaselineIfReady()
            }
        }
        applyBaselineIfReady()
    }

    private data class PrefsUi(
        val customLabels: Map<String, String>,
        val limitM: Float?,
        val limitSince: LocalDate?,
        val baselineSince: LocalDate?,
        val autoLimitNotice: Float?,
    )

    /** Every preference the dashboard reads, folded into one flow for [state]. */
    private val prefsUi = combine(
        prefs.customApps,
        prefs.dailyLimitM,
        prefs.limitSince,
        prefs.baselineSince,
        prefs.autoLimitNotice,
    ) { custom, limit, limitSince, baselineSince, notice ->
        PrefsUi(custom, limit, limitSince, baselineSince, notice)
    }

    private data class DayScope(
        val date: LocalDate,
        val perApp: List<AppTotal>,
        val rows: List<com.thumbtrek.app.data.DailyScroll>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dayScoped = today.flatMapLatest { date ->
        combine(
            dao.observeDay(date.toString()),
            dao.observeRowsSince(date.minusDays(13).toString()),
        ) { perApp, rows -> DayScope(date, perApp, rows) }
    }

    val state = combine(
        dayScoped,
        dao.observeAllDays(),
        trackingEnabled,
        prefsUi,
    ) { scope, allDays, enabled, p ->
        val customLabels = p.customLabels
        val limitM = p.limitM
        val date = scope.date
        val byDate = allDays.associate { LocalDate.parse(it.date) to it.pixels }
        val todayPx = scope.perApp.sumOf { it.pixels }
        val todayMeters = pixelsToMeters(todayPx, dpi)
        val dayMeters = byDate.mapValues { pixelsToMeters(it.value, dpi) }
        // The run never reaches back past the day the limit began, so a baseline week
        // (or any stretch before a first limit) is never scored against it.
        val firstTracked = dayMeters.keys.minOrNull()
        val judgeFrom = listOfNotNull(firstTracked, p.limitSince).maxOrNull()
        val cleanRun = limitM?.let {
            limitStreak(dayMeters, it.toDouble(), today = date, firstDay = judgeFrom)
        } ?: 0
        val streak = trekStreak(byDate.keys, today = date)
        UiState(
            loaded = true,
            today = date,
            trackingEnabled = enabled,
            todayPx = todayPx,
            perApp = scope.perApp.sortedByDescending { it.pixels },
            streak = streak,
            weekPx = totalThisWeek(byDate, today = date),
            monthPx = totalThisMonth(byDate, today = date),
            record = personalRecord(byDate),
            days = allDays,
            dailyBuckets = dailyBuckets(byDate, today = date),
            appTrends = appTrends(scope.rows, today = date),
            customLabels = customLabels,
            badges = badges(
                totalMeters = pixelsToMeters(byDate.values.sum(), dpi),
                bestDayMeters = pixelsToMeters(byDate.values.maxOrNull() ?: 0L, dpi),
                streak = streak,
                cleanStreak = cleanRun,
            ).filter { limitM != null || !it.id.startsWith("clean_") },
            limitM = limitM,
            todayClean = limitM?.let { isCleanDay(todayMeters, it.toDouble()) } ?: false,
            cleanStreak = cleanRun,
            limitSince = p.limitSince,
            baselineSince = p.baselineSince,
            baselineDays = p.baselineSince?.let {
                trackedDaysSince(dayMeters, it, date).size.coerceAtMost(BASELINE_DAYS)
            } ?: 0,
            autoLimitNotice = p.autoLimitNotice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun refreshTrackingState() {
        trackingEnabled.value = ScrollTrackerService.isEnabled(getApplication())
        today.value = LocalDate.now()
        applyBaselineIfReady()
    }

    private fun applyBaselineIfReady() {
        viewModelScope.launch(Dispatchers.IO) { BaselineLimit.applyIfReady(getApplication()) }
    }

    fun dismissAutoLimitNotice() = prefs.dismissAutoLimitNotice()

    /** "No limit" from the announcement card: the user's call, and it sticks. */
    fun declineAutoLimit() = prefs.clearDailyLimit()
}
