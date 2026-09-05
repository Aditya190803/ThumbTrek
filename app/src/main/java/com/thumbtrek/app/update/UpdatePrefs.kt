package com.thumbtrek.app.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Updater settings, in their own SharedPreferences file rather than in
 * [com.thumbtrek.app.data.Prefs] — the update package owns its own state so it can be
 * lifted out (or switched off) without touching the app's settings surface.
 */
class UpdatePrefs private constructor(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("thumbtrek_update", Context.MODE_PRIVATE)

    private val _autoCheck = MutableStateFlow(sp.getBoolean(KEY_AUTO_CHECK, true))
    /**
     * On by default. Unlike the social features there is nothing to opt into privacy-wise:
     * the check is an anonymous GET to GitHub, and an app that silently rots on an old
     * version is worse for the user than one that tells them a build exists.
     */
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    private val _lastCheckedAt = MutableStateFlow(sp.getLong(KEY_LAST_CHECKED, 0L))
    /** Epoch millis of the last completed check, successful or not. */
    val lastCheckedAt: StateFlow<Long> = _lastCheckedAt.asStateFlow()

    /**
     * Highest versionCode we have already put a notification up for, so a six-hourly
     * check does not re-nag about the same release four times a day.
     */
    var notifiedVersionCode: Long
        get() = sp.getLong(KEY_NOTIFIED_VERSION, 0L)
        set(value) = sp.edit().putLong(KEY_NOTIFIED_VERSION, value).apply()

    /**
     * The last manifest we successfully fetched, verbatim. Lets a cold start show
     * "update ready" without going back to the network — and lets the install path know
     * which SHA-256 the file on disk was checked against.
     */
    var cachedManifestJson: String?
        get() = sp.getString(KEY_CACHED_MANIFEST, null)
        set(value) = sp.edit().putString(KEY_CACHED_MANIFEST, value).apply()

    fun setAutoCheck(enabled: Boolean) {
        sp.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _autoCheck.value = enabled
    }

    fun markChecked(atMillis: Long = System.currentTimeMillis()) {
        sp.edit().putLong(KEY_LAST_CHECKED, atMillis).apply()
        _lastCheckedAt.value = atMillis
    }

    companion object {
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_LAST_CHECKED = "last_checked_at"
        private const val KEY_NOTIFIED_VERSION = "notified_version_code"
        private const val KEY_CACHED_MANIFEST = "cached_manifest_json"

        @Volatile
        private var instance: UpdatePrefs? = null

        fun get(context: Context): UpdatePrefs =
            instance ?: synchronized(this) {
                instance ?: UpdatePrefs(context).also { instance = it }
            }
    }
}
