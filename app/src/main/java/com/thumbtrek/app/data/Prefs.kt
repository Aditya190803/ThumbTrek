package com.thumbtrek.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings, backed by SharedPreferences.
 *
 * Everything social is opt-in and off by default (PRD §11 Q2); tracking is opt-out
 * per app and on by default for all four (PRD §11 Q1), since a tracker that tracks
 * nothing on first run is just a broken app.
 */
class Prefs private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("thumbtrek_prefs", Context.MODE_PRIVATE)

    private val _trackedApps = MutableStateFlow(readTrackedApps())
    /** Packages the accessibility service should count. Defaults to all of [TRACKED_APPS]. */
    val trackedApps: StateFlow<Set<String>> = _trackedApps.asStateFlow()

    private val _streakReminder = MutableStateFlow(sp.getBoolean(KEY_STREAK_REMINDER, false))
    /** PRD §5.5: off by default, "to avoid becoming another nagging app". */
    val streakReminder: StateFlow<Boolean> = _streakReminder.asStateFlow()

    private val _leaderboardOptIn = MutableStateFlow(sp.getBoolean(KEY_LEADERBOARD_OPT_IN, false))
    /** No score leaves the device until this is on. */
    val leaderboardOptIn: StateFlow<Boolean> = _leaderboardOptIn.asStateFlow()

    private val _anonymous = MutableStateFlow(sp.getBoolean(KEY_ANONYMOUS, false))
    /** Publish as an anonymous handle instead of the Google display name (PRD §5.4). */
    val anonymous: StateFlow<Boolean> = _anonymous.asStateFlow()

    private fun readTrackedApps(): Set<String> =
        sp.getStringSet(KEY_TRACKED_APPS, null) ?: TRACKED_APPS.keys

    fun setAppTracked(pkg: String, tracked: Boolean) {
        val next = _trackedApps.value.toMutableSet().apply {
            if (tracked) add(pkg) else remove(pkg)
        }
        sp.edit().putStringSet(KEY_TRACKED_APPS, next).apply()
        _trackedApps.value = next
    }

    fun setStreakReminder(enabled: Boolean) {
        sp.edit().putBoolean(KEY_STREAK_REMINDER, enabled).apply()
        _streakReminder.value = enabled
    }

    fun setLeaderboardOptIn(enabled: Boolean) {
        sp.edit().putBoolean(KEY_LEADERBOARD_OPT_IN, enabled).apply()
        _leaderboardOptIn.value = enabled
    }

    fun setAnonymous(enabled: Boolean) {
        sp.edit().putBoolean(KEY_ANONYMOUS, enabled).apply()
        _anonymous.value = enabled
    }

    /**
     * Read outside a coroutine by the accessibility service on the event hot path,
     * so it stays a plain synchronous read of the in-memory StateFlow.
     */
    fun isTracked(pkg: String): Boolean = pkg in _trackedApps.value

    companion object {
        private const val KEY_TRACKED_APPS = "tracked_apps"
        private const val KEY_STREAK_REMINDER = "streak_reminder"
        private const val KEY_LEADERBOARD_OPT_IN = "leaderboard_opt_in"
        private const val KEY_ANONYMOUS = "anonymous"

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
