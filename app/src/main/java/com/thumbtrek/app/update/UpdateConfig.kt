package com.thumbtrek.app.update

/**
 * Where self-updates come from and how eagerly the app goes looking.
 *
 * ThumbTrek is sideloaded, not on the Play Store, so nothing updates it for us. The
 * release workflow (.github/workflows/release.yml) publishes a signed APK plus an
 * `update.json` describing it as GitHub Release assets; everything below points at that.
 *
 * Privacy: these are the only endpoints the updater ever touches. The check is an
 * anonymous GET — no account, no token, no device identifier, no scroll data. GitHub
 * sees an IP and a User-Agent, the same as opening the releases page in a browser.
 */
object UpdateConfig {

    /** `owner/repo` on github.com. Change this if the project ever moves. */
    const val REPO = "Aditya190803/ThumbTrek"

    /**
     * GitHub Releases API — the source of truth for "what is the newest release".
     * Unauthenticated calls are rate-limited to 60/hour per IP, which is many times more
     * headroom than a six-hourly check needs.
     */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"

    /** Release asset the workflow attaches next to the APK. */
    const val MANIFEST_ASSET_NAME = "update.json"

    /** Human-facing page, for the "view the release" escape hatch. */
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /** How often the background check runs. WorkManager will not honour anything under 15m. */
    const val CHECK_INTERVAL_HOURS = 6L

    const val UNIQUE_WORK_NAME = "update_check"
    const val ONE_SHOT_WORK_NAME = "update_check_now"

    /** Own channel so the user can silence update nags without losing streak reminders. */
    const val CHANNEL_ID = "updates"

    // 1..3 are already taken by the daily summary, streak reminder and social notifiers.
    const val NOTIFICATION_ID_READY = 5
    const val NOTIFICATION_ID_PROGRESS = 6

    /** Sent to [InstallResultReceiver] by PackageInstaller when a session finishes. */
    const val ACTION_INSTALL_RESULT = "com.thumbtrek.app.update.INSTALL_RESULT"

    /** Subdirectory of `filesDir` holding downloaded APKs. */
    const val DOWNLOAD_DIR = "updates"

    /** Refuse anything absurd — a ThumbTrek APK is single-digit megabytes. */
    const val MAX_APK_BYTES = 200L * 1024 * 1024

    const val CONNECT_TIMEOUT_MS = 15_000
    const val READ_TIMEOUT_MS = 30_000
}
