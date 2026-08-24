package com.thumbtrek.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.track.ScrollTrackerService
import com.thumbtrek.app.work.StreakReminderWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A launchable app the user could add to tracking beyond the built-in four. */
data class InstallableApp(val packageName: String, val label: String)

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val trackingEnabled: Boolean = false,
        val trackedApps: Set<String> = emptySet(),
        /** User-added packages: pkg -> label captured at add time. */
        val customApps: Map<String, String> = emptyMap(),
        /** Per-app scroll multipliers; absent means 1.0. */
        val calibration: Map<String, Float> = emptyMap(),
        val streakReminder: Boolean = false,
        val leaderboardOptIn: Boolean = false,
        val anonymous: Boolean = false,
    )

    private val prefs = Prefs.get(app)
    private val trackingEnabled = MutableStateFlow(ScrollTrackerService.isEnabled(app))
    private val installed = MutableStateFlow<List<InstallableApp>>(emptyList())

    val installedApps = installed.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
    )

    // Prefs are already in memory, so seed the initial value rather than letting the
    // screen render one frame of "everything off".
    private val initial = UiState(
        trackingEnabled = trackingEnabled.value,
        trackedApps = prefs.trackedApps.value,
        customApps = prefs.customApps.value,
        calibration = prefs.calibration.value,
        streakReminder = prefs.streakReminder.value,
        leaderboardOptIn = prefs.leaderboardOptIn.value,
        anonymous = prefs.anonymous.value,
    )

    private val prefState = combine(
        prefs.trackedApps,
        prefs.customApps,
        prefs.calibration,
        prefs.streakReminder,
        prefs.leaderboardOptIn,
    ) { tracked, custom, calibration, reminder, leaderboard ->
        PrefSnapshot(tracked, custom, calibration, reminder, leaderboard)
    }

    val state = combine(
        prefState,
        prefs.anonymous,
        trackingEnabled,
    ) { snap, anon, enabled ->
        UiState(
            trackingEnabled = enabled,
            trackedApps = snap.tracked,
            customApps = snap.custom,
            calibration = snap.calibration,
            streakReminder = snap.reminder,
            leaderboardOptIn = snap.leaderboard,
            anonymous = anon,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private data class PrefSnapshot(
        val tracked: Set<String>,
        val custom: Map<String, String>,
        val calibration: Map<String, Float>,
        val reminder: Boolean,
        val leaderboard: Boolean,
    )

    fun refreshTrackingState() {
        trackingEnabled.value = ScrollTrackerService.isEnabled(getApplication())
    }

    /** Launchable user-installed apps, for the "add another app" picker. */
    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = getApplication<Application>().packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val self = getApplication<Application>().packageName
            val resolveInfo = if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            installed.value = resolveInfo.mapNotNull { info ->
                val ai = info.activityInfo ?: return@mapNotNull null
                val pkg = ai.packageName
                // Skip ourselves and anything that ships with the ROM — nobody wants to
                // "track" their launcher.
                if (pkg == self || ai.applicationInfo.flags and
                    ApplicationInfo.FLAG_SYSTEM != 0
                ) {
                    return@mapNotNull null
                }
                InstallableApp(pkg, info.loadLabel(pm).toString())
            }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun addCustomApp(pkg: String, label: String) = prefs.addCustomApp(pkg, label)

    fun removeCustomApp(pkg: String) = prefs.removeCustomApp(pkg)

    fun setAppTracked(pkg: String, tracked: Boolean) = prefs.setAppTracked(pkg, tracked)

    fun setCalibration(pkg: String, factor: Float) = prefs.setCalibration(pkg, factor)

    fun setStreakReminder(enabled: Boolean) {
        prefs.setStreakReminder(enabled)
        if (enabled) StreakReminderWorker.schedule(getApplication())
        else StreakReminderWorker.cancel(getApplication())
    }

    fun setLeaderboardOptIn(enabled: Boolean) = prefs.setLeaderboardOptIn(enabled)

    fun setAnonymous(enabled: Boolean) = prefs.setAnonymous(enabled)
}
