package com.thumbtrek.app.update

/**
 * Everything the settings UI needs to render the updater, and nothing it doesn't.
 *
 * One linear state machine: Idle → Checking → (UpToDate | Available) → Downloading →
 * ReadyToInstall → Installing → (process dies and restarts on success) — with Failed
 * reachable from anywhere.
 */
sealed interface UpdateStatus {

    /** Nothing has happened yet this session. */
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    /** Checked, and this build is the newest release. */
    data object UpToDate : UpdateStatus

    /** A newer release exists but its APK is not on the device yet. */
    data class Available(val manifest: UpdateManifest) : UpdateStatus

    /** [percent] is 0..100, or -1 when the server did not send a Content-Length. */
    data class Downloading(val manifest: UpdateManifest, val percent: Int) : UpdateStatus

    /** Downloaded and SHA-256 verified. One tap from installing. */
    data class ReadyToInstall(val manifest: UpdateManifest) : UpdateStatus

    /** Session committed; the system takes it from here (usually via its own dialog). */
    data class Installing(val manifest: UpdateManifest) : UpdateStatus

    /** Network trouble, a hash mismatch, a refused install — all land here. */
    data class Failed(val message: String) : UpdateStatus
}

/** What [UpdateViewModel] hands the composable. */
data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.Idle,
    val installedVersionName: String = "",
    val installedVersionCode: Long = 0L,
    val autoCheckEnabled: Boolean = true,
    /** Epoch millis, 0 when never checked. */
    val lastCheckedAt: Long = 0L,
    /**
     * API 26+ makes "install unknown apps" a per-app user grant. Without it the install
     * step cannot even start, so the UI asks for it before offering the button.
     */
    val canInstallUnknownApps: Boolean = true,
)
