package com.thumbtrek.app.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State holder for the updater's settings surface.
 *
 * Owns nothing itself — [UpdateRepository] is the single source of truth so the background
 * worker and the screen never disagree. This just flattens the repository plus
 * [UpdatePrefs] into one [UpdateUiState] and forwards the four things the user can do.
 *
 * The periodic check is normally armed by [UpdateStartupInitializer] on process start;
 * re-scheduling here is belt-and-braces for the case where the user switches automatic
 * checks back on mid-session. [UpdateWorker.schedule] is idempotent, so it costs nothing.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = UpdateRepository.get(app)
    private val prefs = UpdatePrefs.get(app)

    /**
     * Re-read on resume rather than observed — Android gives no callback when the user
     * flips "install unknown apps" in system Settings, so the screen asks again each time
     * it comes back to the foreground.
     */
    private val canInstall = MutableStateFlow(ApkInstaller.canInstallUnknownApps(app))

    /** Fires when the system asks the user to confirm an install; the UI launches it. */
    val confirmIntents = repository.confirmIntents

    private val initial = UpdateUiState(
        status = repository.status.value,
        installedVersionName = repository.installedVersionName,
        installedVersionCode = repository.installedVersionCode,
        autoCheckEnabled = prefs.autoCheck.value,
        lastCheckedAt = prefs.lastCheckedAt.value,
        canInstallUnknownApps = canInstall.value,
    )

    val state = combine(
        repository.status,
        prefs.autoCheck,
        prefs.lastCheckedAt,
        canInstall,
    ) { status, autoCheck, lastChecked, installable ->
        UpdateUiState(
            status = status,
            installedVersionName = repository.installedVersionName,
            installedVersionCode = repository.installedVersionCode,
            autoCheckEnabled = autoCheck,
            lastCheckedAt = lastChecked,
            canInstallUnknownApps = installable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    init {
        if (prefs.autoCheck.value) UpdateWorker.schedule(app)
        UpdateNotifier.ensureChannel(app)
    }

    /** Manual "check for updates". Downloads immediately, metered or not — they asked. */
    fun checkNow() {
        viewModelScope.launch { repository.check(autoDownload = false) }
    }

    /** Only reachable when the background pass deferred the download to save mobile data. */
    fun download(manifest: UpdateManifest) {
        viewModelScope.launch { repository.download(manifest) }
    }

    /**
     * Commits the verified APK. Safe to call from the screen because the screen *is* the
     * foreground activity the confirmation dialog needs.
     */
    fun install() = repository.installReadyUpdate()

    fun consumeConfirmIntent() = repository.consumeConfirmIntent()

    fun setAutoCheck(enabled: Boolean) {
        prefs.setAutoCheck(enabled)
        if (enabled) UpdateWorker.schedule(getApplication()) else UpdateWorker.cancel(getApplication())
    }

    /** Call from a resume effect — see [canInstall]. */
    fun refreshInstallPermission() {
        canInstall.value = ApkInstaller.canInstallUnknownApps(getApplication())
    }

    /** Dismisses a "failed" / "up to date" banner. */
    fun dismissStatus() = repository.clearTransientStatus()
}
