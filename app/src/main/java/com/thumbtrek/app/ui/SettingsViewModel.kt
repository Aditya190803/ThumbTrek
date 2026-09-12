package com.thumbtrek.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.freeLimitEditsLeft
import com.thumbtrek.app.stats.weekKey
import com.thumbtrek.app.track.ScrollTrackerService
import com.thumbtrek.app.work.LimitNudgeWorker
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
        val streakReminder: Boolean = false,
        val limitNudge: Boolean = false,
        val leaderboardOptIn: Boolean = false,
        val anonymous: Boolean = true,
        // --- daily limit ---
        val limitM: Float = 100f,
        val freeEditsLeft: Int = 1,
        val premium: Boolean = false,
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
        streakReminder = prefs.streakReminder.value,
        limitNudge = prefs.limitNudge.value,
        leaderboardOptIn = prefs.leaderboardOptIn.value,
        anonymous = prefs.anonymous.value,
        limitM = prefs.dailyLimitM.value,
        freeEditsLeft = freeLimitEditsLeft(prefs.limitEditWeek, prefs.limitEditCount),
        premium = prefs.premium.value,
    )

    /** Last limit-edit attempt: null until the user tries to save. */
    private val _limitEdit = MutableStateFlow<Prefs.LimitEditResult?>(null)
    val limitEdit = _limitEdit.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), null,
    )

    private val prefState = combine(
        prefs.trackedApps,
        prefs.customApps,
        prefs.streakReminder,
        prefs.limitNudge,
        prefs.leaderboardOptIn,
    ) { tracked, custom, reminder, nudge, leaderboard ->
        PrefSnapshot(tracked, custom, reminder, nudge, leaderboard)
    }

    /**
     * Limit half of the settings state. Folded into one flow because this coroutines
     * version has no 6-way combine overload; [_limitEdit] rides along so the free-edits
     * counter refreshes even when the saved value is unchanged (same-value writes don't
     * re-emit dailyLimitM).
     */
    private data class LimitSnapshot(
        val limitM: Float,
        val premium: Boolean,
        val edit: Prefs.LimitEditResult?,
    )

    private val limitState = combine(
        prefs.dailyLimitM,
        prefs.premium,
        _limitEdit,
    ) { limitM, premium, edit -> LimitSnapshot(limitM, premium, edit) }

    val state = combine(
        prefState,
        prefs.anonymous,
        trackingEnabled,
        limitState,
    ) { snap: PrefSnapshot, anon: Boolean, enabled: Boolean, limit: LimitSnapshot ->
        UiState(
            trackingEnabled = enabled,
            trackedApps = snap.tracked,
            customApps = snap.custom,
            streakReminder = snap.reminder,
            limitNudge = snap.nudge,
            leaderboardOptIn = snap.leaderboard,
            anonymous = anon,
            limitM = limit.limitM,
            freeEditsLeft = if (!BILLING_ENFORCED || limit.premium) Int.MAX_VALUE
                else freeLimitEditsLeft(prefs.limitEditWeek, prefs.limitEditCount),
            premium = limit.premium,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    private data class PrefSnapshot(
        val tracked: Set<String>,
        val custom: Map<String, String>,
        val reminder: Boolean,
        val nudge: Boolean,
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

    fun setStreakReminder(enabled: Boolean) {
        prefs.setStreakReminder(enabled)
        if (enabled) StreakReminderWorker.schedule(getApplication())
        else StreakReminderWorker.cancel(getApplication())
    }

    fun setLimitNudge(enabled: Boolean) {
        prefs.setLimitNudge(enabled)
        if (enabled) LimitNudgeWorker.schedule(getApplication())
        else LimitNudgeWorker.cancel(getApplication())
    }

    fun setLeaderboardOptIn(enabled: Boolean) = prefs.setLeaderboardOptIn(enabled)

    fun setAnonymous(enabled: Boolean) = prefs.setAnonymous(enabled)

    /**
     * Saves a new daily limit, enforcing the weekly free-edit quota. Returns what happened
     * so the screen can say "saved" or "premium needed"; [clearLimitEdit] resets it.
     */
    fun trySetLimit(meters: Float): Prefs.LimitEditResult {
        val result = prefs.trySetDailyLimit(meters, weekKey())
        _limitEdit.value = result
        return result
    }

    fun clearLimitEdit() {
        _limitEdit.value = null
    }
}
