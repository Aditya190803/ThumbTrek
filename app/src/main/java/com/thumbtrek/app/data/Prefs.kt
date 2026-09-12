package com.thumbtrek.app.data

import android.content.Context
import android.content.SharedPreferences
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.DEFAULT_DAILY_LIMIT_M
import com.thumbtrek.app.stats.FREE_LIMIT_EDITS_PER_WEEK
import com.thumbtrek.app.stats.MAX_DAILY_LIMIT_M
import com.thumbtrek.app.stats.MIN_DAILY_LIMIT_M
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

    private val _anonymous = MutableStateFlow(sp.getBoolean(KEY_ANONYMOUS, true))
    /**
     * Anonymous by default: boards show a stable pseudonym and no photo until the user
     * opts into their Google name. Flipping the default is a clean migration — anyone
     * who explicitly chose keeps their stored value; only the never-chosen inherit true.
     */
    val anonymous: StateFlow<Boolean> = _anonymous.asStateFlow()

    // --- daily limit (PRD §11: the streak that rewards scrolling less) ---

    private val _dailyLimitM = MutableStateFlow(sp.getFloat(KEY_DAILY_LIMIT_M, DEFAULT_DAILY_LIMIT_M.toFloat()))
    /** Single daily cap in metres. Stay at or under it and the day is clean. */
    val dailyLimitM: StateFlow<Float> = _dailyLimitM.asStateFlow()

    private val _premium = MutableStateFlow(sp.getBoolean(KEY_PREMIUM, false))
    /**
     * Play Billing stub. Always false until billing lands; the weekly-gate logic reads it
     * so the paywall is a flag flip, not a rewrite, when it does.
     */
    val premium: StateFlow<Boolean> = _premium.asStateFlow()

    /** ISO week of the last limit edit, or null when the limit never changed. */
    var limitEditWeek: String?
        get() = sp.getString(KEY_LIMIT_EDIT_WEEK, null)
        private set(value) = sp.edit().putString(KEY_LIMIT_EDIT_WEEK, value).apply()

    /** Limit edits already spent inside [limitEditWeek]. */
    var limitEditCount: Int
        get() = sp.getInt(KEY_LIMIT_EDIT_COUNT, 0)
        private set(value) = sp.edit().putInt(KEY_LIMIT_EDIT_COUNT, value).apply()

    /**
     * True when this edit costs nothing: the paywall is in its free data window, or
     * premium, or the week's free change is unspent. The week is derived from [weekKey]
     * so the quota resets itself with no job.
     */
    fun canEditLimitFree(todayWeekKey: String): Boolean {
        if (!BILLING_ENFORCED || _premium.value) return true
        if (limitEditWeek != todayWeekKey) return true
        return limitEditCount < FREE_LIMIT_EDITS_PER_WEEK
    }

    /** Result of [trySetDailyLimit]: applied, or blocked until premium. */
    sealed interface LimitEditResult {
        /** New limit saved; [freeLeft] is this week's remaining free changes. */
        data class Applied(val freeLeft: Int) : LimitEditResult
        /** Week's free change is spent and premium is off. Nothing was written. */
        data object NeedsPremium : LimitEditResult
    }

    /**
     * Sets the daily limit in metres, enforcing the weekly free-edit quota. Values are
     * clamped to [MIN_DAILY_LIMIT_M]..[MAX_DAILY_LIMIT_M] so a typo can't create an
     * unwinnable game. Premium bypasses the quota but still records the edit.
     */
    fun trySetDailyLimit(meters: Float, todayWeekKey: String): LimitEditResult {
        if (!canEditLimitFree(todayWeekKey)) return LimitEditResult.NeedsPremium
        val clamped = meters.coerceIn(MIN_DAILY_LIMIT_M.toFloat(), MAX_DAILY_LIMIT_M.toFloat())
        sp.edit().putFloat(KEY_DAILY_LIMIT_M, clamped).apply()
        _dailyLimitM.value = clamped
        if (limitEditWeek != todayWeekKey) {
            limitEditWeek = todayWeekKey
            limitEditCount = 1
        } else {
            limitEditCount += 1
        }
        val freeLeft = if (!BILLING_ENFORCED || _premium.value) Int.MAX_VALUE
            else (FREE_LIMIT_EDITS_PER_WEEK - limitEditCount).coerceAtLeast(0)
        return LimitEditResult.Applied(freeLeft)
    }

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

    // --- day-ledger sync bookkeeping ---

    /**
     * What the server was last told each day's total was, as `yyyy-MM-dd=<µm>` lines.
     * Diffing Room against this is what makes a routine sync push the two days that moved
     * instead of re-uploading the user's whole history (see `social/SyncModel.dirtyDays`).
     *
     * SharedPreferences rather than a Room table, deliberately: this is disposable mirror
     * state *about a remote*, not user data. Room stays the source of truth for every
     * measurement, losing this costs one re-upload and never a metre, and a new Room table
     * would mean a schema migration for something with nothing worth migrating. It also
     * sits with the rest of the "what has the network already been told" bookkeeping above.
     * A two-year history encodes to roughly 12 KB, which a single preference carries fine.
     */
    val syncedDayUm: Map<String, Long>
        get() = decodeDayUm(sp.getString(KEY_SYNCED_DAY_UM, null))

    /** Merges a just-committed batch in, so a failure part-way does not lose the rest. */
    fun markDaysSynced(days: Map<String, Long>) {
        if (days.isEmpty()) return
        sp.edit().putString(KEY_SYNCED_DAY_UM, encodeDayUm(syncedDayUm + days)).apply()
    }

    /** Epoch millis of the last successful push, or 0 when this install never has. */
    var lastSyncedAt: Long
        get() = sp.getLong(KEY_LAST_SYNCED_AT, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNCED_AT, value).apply()

    /**
     * Opting out takes the day ledger off the server, so the record of having pushed it
     * has to go too — otherwise opting back in would backfill nothing and the history
     * would stay missing until every day happened to change again.
     */
    fun clearSyncState() {
        sp.edit()
            .remove(KEY_SYNCED_DAY_UM)
            .remove(KEY_LAST_SYNCED_AT)
            .apply()
    }

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
        private const val KEY_SYNCED_DAY_UM = "synced_day_um"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_DAILY_LIMIT_M = "daily_limit_m"
        private const val KEY_LIMIT_EDIT_WEEK = "limit_edit_week"
        private const val KEY_LIMIT_EDIT_COUNT = "limit_edit_count"
        private const val KEY_PREMIUM = "is_premium"

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

        /** `date=um` lines. Dates are fixed-width and µm are integers, so nothing escapes. */
        fun encodeDayUm(days: Map<String, Long>): String =
            days.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }

        fun decodeDayUm(raw: String?): Map<String, Long> =
            raw.orEmpty().lineSequence()
                .mapNotNull { line ->
                    val sep = line.indexOf('=')
                    if (sep <= 0) return@mapNotNull null
                    val um = line.drop(sep + 1).toLongOrNull() ?: return@mapNotNull null
                    line.take(sep) to um
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
