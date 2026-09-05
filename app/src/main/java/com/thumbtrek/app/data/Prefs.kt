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

    /**
     * Packages the user added beyond [TRACKED_APPS], as pkg -> display label. Separate
     * from [trackedApps] so unchecking one doesn't forget it exists.
     */
    private val _customApps = MutableStateFlow(readCustomApps())
    val customApps: StateFlow<Map<String, String>> = _customApps.asStateFlow()

    private val _streakReminder = MutableStateFlow(sp.getBoolean(KEY_STREAK_REMINDER, false))
    /** PRD §5.5: off by default, "to avoid becoming another nagging app". */
    val streakReminder: StateFlow<Boolean> = _streakReminder.asStateFlow()

    private val _leaderboardOptIn = MutableStateFlow(sp.getBoolean(KEY_LEADERBOARD_OPT_IN, false))
    /** No score leaves the device until this is on. */
    val leaderboardOptIn: StateFlow<Boolean> = _leaderboardOptIn.asStateFlow()

    private val _anonymous = MutableStateFlow(sp.getBoolean(KEY_ANONYMOUS, false))
    /** Publish as an anonymous handle instead of the Google display name (PRD §5.4). */
    val anonymous: StateFlow<Boolean> = _anonymous.asStateFlow()

    // --- social notification bookkeeping (so each event nags exactly once) ---

    var knownRequestUids: Set<String>
        get() = sp.getStringSet(KEY_KNOWN_REQUESTS, emptySet()) ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_KNOWN_REQUESTS, value).apply()

    var notifiedRankWeek: String?
        get() = sp.getString(KEY_NOTIFIED_RANK_WEEK, null)
        set(value) = sp.edit().putString(KEY_NOTIFIED_RANK_WEEK, value).apply()

    var notifiedRank: Int
        get() = sp.getInt(KEY_NOTIFIED_RANK, Int.MAX_VALUE)
        set(value) = sp.edit().putInt(KEY_NOTIFIED_RANK, value).apply()

    private fun readTrackedApps(): Set<String> =
        sp.getStringSet(KEY_TRACKED_APPS, null) ?: TRACKED_APPS.keys

    fun setAppTracked(pkg: String, tracked: Boolean) {
        val next = _trackedApps.value.toMutableSet().apply {
            if (tracked) add(pkg) else remove(pkg)
        }
        sp.edit().putStringSet(KEY_TRACKED_APPS, next).apply()
        _trackedApps.value = next
    }

    fun addCustomApp(pkg: String, label: String) {
        val next = _customApps.value + (pkg to label)
        sp.edit().putString(KEY_CUSTOM_APPS, encodeCustomApps(next)).apply()
        _customApps.value = next
        setAppTracked(pkg, true)
    }

    fun removeCustomApp(pkg: String) {
        val next = _customApps.value - pkg
        sp.edit().putString(KEY_CUSTOM_APPS, encodeCustomApps(next)).apply()
        _customApps.value = next
        setAppTracked(pkg, false)
    }

    private fun readCustomApps(): Map<String, String> =
        decodeCustomApps(sp.getString(KEY_CUSTOM_APPS, null))

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
        private const val KEY_CUSTOM_APPS = "custom_apps"
        private const val KEY_STREAK_REMINDER = "streak_reminder"
        private const val KEY_LEADERBOARD_OPT_IN = "leaderboard_opt_in"
        private const val KEY_ANONYMOUS = "anonymous"
        private const val KEY_KNOWN_REQUESTS = "known_request_uids"
        private const val KEY_NOTIFIED_RANK_WEEK = "notified_rank_week"
        private const val KEY_NOTIFIED_RANK = "notified_rank"

        /** `pkg|label` lines; labels never contain newlines and `|` is stripped on save. */
        fun encodeCustomApps(apps: Map<String, String>): String =
            apps.entries.joinToString("\n") { "${it.key}|${it.value.replace('|', ' ')}" }

        fun decodeCustomApps(raw: String?): Map<String, String> =
            raw.orEmpty().lineSequence()
                .mapNotNull { line ->
                    val sep = line.indexOf('|')
                    if (sep <= 0 || sep == line.length - 1) return@mapNotNull null
                    line.take(sep) to line.drop(sep + 1)
                }
                .toMap()

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context).also { instance = it }
            }
    }
}
