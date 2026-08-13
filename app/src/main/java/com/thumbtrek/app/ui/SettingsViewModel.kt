package com.thumbtrek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.track.ScrollTrackerService
import com.thumbtrek.app.work.StreakReminderWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val trackingEnabled: Boolean = false,
        val trackedApps: Set<String> = emptySet(),
        val streakReminder: Boolean = false,
        val leaderboardOptIn: Boolean = false,
        val anonymous: Boolean = false,
    )

    private val prefs = Prefs.get(app)
    private val trackingEnabled = MutableStateFlow(ScrollTrackerService.isEnabled(app))

    // Prefs are already in memory, so seed the initial value rather than letting the
    // screen render one frame of "everything off".
    private val initial = UiState(
        trackingEnabled = trackingEnabled.value,
        trackedApps = prefs.trackedApps.value,
        streakReminder = prefs.streakReminder.value,
        leaderboardOptIn = prefs.leaderboardOptIn.value,
        anonymous = prefs.anonymous.value,
    )

    val state = combine(
        prefs.trackedApps,
        prefs.streakReminder,
        prefs.leaderboardOptIn,
        prefs.anonymous,
        trackingEnabled,
    ) { tracked, reminder, leaderboard, anonymous, enabled ->
        UiState(
            trackingEnabled = enabled,
            trackedApps = tracked,
            streakReminder = reminder,
            leaderboardOptIn = leaderboard,
            anonymous = anonymous,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    fun refreshTrackingState() {
        trackingEnabled.value = ScrollTrackerService.isEnabled(getApplication())
    }

    fun setAppTracked(pkg: String, tracked: Boolean) = prefs.setAppTracked(pkg, tracked)

    fun setStreakReminder(enabled: Boolean) {
        prefs.setStreakReminder(enabled)
        if (enabled) StreakReminderWorker.schedule(getApplication())
        else StreakReminderWorker.cancel(getApplication())
    }

    fun setLeaderboardOptIn(enabled: Boolean) = prefs.setLeaderboardOptIn(enabled)

    fun setAnonymous(enabled: Boolean) = prefs.setAnonymous(enabled)
}
