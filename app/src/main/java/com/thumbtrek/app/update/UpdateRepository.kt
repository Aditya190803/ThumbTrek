package com.thumbtrek.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The whole updater in one place: check GitHub, download, verify, install.
 *
 * A process-wide singleton because two very different callers drive the same state — the
 * background [UpdateWorker] and the settings card — and they must not each keep their own
 * idea of what is downloaded. A [Mutex] serialises check/download so a manual "check now"
 * tapped while the worker is mid-download does not start a second transfer.
 *
 * Privacy: the only network traffic is two anonymous GETs to github.com plus the APK
 * download. No identifiers, no analytics, nothing about scrolling ever leaves the device
 * through this class.
 */
class UpdateRepository private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = UpdatePrefs.get(app)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /**
     * The system's install-confirmation screen, once PackageInstaller asks for it.
     * A replaying SharedFlow rather than state: whoever is on screen picks it up even if
     * it arrives a beat before they start collecting, and [consumeConfirmIntent] clears it
     * so a later recomposition does not re-launch the dialog.
     */
    private val _confirmIntents = MutableSharedFlow<Intent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val confirmIntents: SharedFlow<Intent> = _confirmIntents.asSharedFlow()

    val installedVersionCode: Long
    val installedVersionName: String

    /** GitHub rejects API requests with no User-Agent, and it keeps our traffic identifiable. */
    private val userAgent: String

    init {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        installedVersionCode = PackageInfoCompat.getLongVersionCode(info)
        installedVersionName = info.versionName ?: "?"
        userAgent = "ThumbTrek/" + installedVersionName +
            " (+https://github.com/" + UpdateConfig.REPO + ")"
        restoreCachedState()
    }

    // --- checking / downloading ---------------------------------------------------

    /**
     * Asks GitHub what the newest release is.
     *
     * [autoDownload] is true for the background pass: it downloads straight away, but
     * only over an unmetered connection — nobody wants a surprise APK on mobile data.
     * A user-initiated check downloads regardless, because they asked for it.
     */
    suspend fun check(autoDownload: Boolean) = mutex.withLock {
        _status.value = UpdateStatus.Checking

        val manifest = try {
            UpdateSource.fetchLatestManifest(userAgent)
        } catch (e: Exception) {
            prefs.markChecked()
            _status.value = UpdateStatus.Failed(
                "Couldn't reach GitHub: " + (e.message ?: e.javaClass.simpleName),
            )
            return@withLock
        }
        prefs.markChecked()

        if (manifest == null || manifest.versionCode <= installedVersionCode) {
            // Newest release is this one (or older, if a build was pulled). Bin any stale
            // download so the app is not sitting on a dead APK forever.
            prefs.cachedManifestJson = null
            pruneDownloads(keep = null)
            UpdateNotifier.cancelReady(app)
            _status.value = UpdateStatus.UpToDate
            return@withLock
        }

        prefs.cachedManifestJson = manifest.toJson()
        pruneDownloads(keep = apkFile(manifest))

        if (apkFile(manifest).isFile) {
            // Already downloaded and verified on an earlier pass.
            markReady(manifest, notify = autoDownload)
            return@withLock
        }

        _status.value = UpdateStatus.Available(manifest)
        if (!autoDownload || isUnmetered()) downloadLocked(manifest, notify = autoDownload)
    }

    /** Manual download, for when the background pass held off because of metered data. */
    suspend fun download(manifest: UpdateManifest) = mutex.withLock {
        downloadLocked(manifest, notify = false)
    }

    private suspend fun downloadLocked(manifest: UpdateManifest, notify: Boolean) {
        _status.value = UpdateStatus.Downloading(manifest, 0)
        try {
            UpdateSource.downloadVerified(
                manifest = manifest,
                destination = apkFile(manifest),
                userAgent = userAgent,
            ) { percent ->
                _status.value = UpdateStatus.Downloading(manifest, percent)
            }
            markReady(manifest, notify = notify)
        } catch (e: Exception) {
            _status.value = UpdateStatus.Failed(
                "Download failed: " + (e.message ?: e.javaClass.simpleName),
            )
        }
    }

    private fun markReady(manifest: UpdateManifest, notify: Boolean) {
        _status.value = UpdateStatus.ReadyToInstall(manifest)
        // One notification per version, however many times the six-hourly check runs.
        if (notify && prefs.notifiedVersionCode < manifest.versionCode) {
            prefs.notifiedVersionCode = manifest.versionCode
            UpdateNotifier.notifyReady(app, manifest)
        }
    }

    // --- installing ----------------------------------------------------------------

    /**
     * Commits the downloaded APK to PackageInstaller.
     *
     * Call this only from a foreground activity — if the system wants confirmation, the
     * dialog has to be launched from something visible. [UpdateInstallActivity] is that
     * something for the notification path.
     */
    fun installReadyUpdate() {
        val manifest = (_status.value as? UpdateStatus.ReadyToInstall)?.manifest ?: return
        val apk = apkFile(manifest)
        if (!apk.isFile) {
            // File vanished (storage cleaner, "clear data"). Fall back to offering it again.
            _status.value = UpdateStatus.Available(manifest)
            return
        }
        if (!ApkInstaller.canInstallUnknownApps(app)) {
            _status.value = UpdateStatus.Failed(
                "Android needs permission to install apps from ThumbTrek. Grant \"Install " +
                    "unknown apps\" and try again.",
            )
            return
        }

        _status.value = UpdateStatus.Installing(manifest)
        runCatching { ApkInstaller.install(app, apk) }.onFailure {
            _status.value = UpdateStatus.Failed(
                "Couldn't start the install: " + (it.message ?: it.javaClass.simpleName),
            )
        }
    }

    /** Called by [InstallResultReceiver] when the system wants the user to press Install. */
    fun onConfirmationRequired(intent: Intent) {
        _confirmIntents.tryEmit(intent)
    }

    /** Clears the replayed intent once a UI has launched it. */
    fun consumeConfirmIntent() {
        _confirmIntents.resetReplayCache()
    }

    fun onInstallSucceeded() {
        // Usually never reached — a successful self-update tears the process down first.
        prefs.cachedManifestJson = null
        pruneDownloads(keep = null)
        UpdateNotifier.cancelReady(app)
        _status.value = UpdateStatus.UpToDate
    }

    fun onInstallFailed(message: String) {
        _status.value = UpdateStatus.Failed(message)
    }

    /** Lets the UI drop a Failed/UpToDate banner without kicking off another check. */
    fun clearTransientStatus() {
        val current = _status.value
        if (current is UpdateStatus.Failed || current is UpdateStatus.UpToDate) {
            restoreCachedState()
        }
    }

    // --- plumbing -------------------------------------------------------------------

    /**
     * Rebuilds state from disk on process start, so reopening the app after the worker
     * has done its job shows "ready to install" instead of "idle".
     */
    private fun restoreCachedState() {
        val manifest = prefs.cachedManifestJson?.let { UpdateManifest.parse(it) }
        _status.value = when {
            manifest == null -> UpdateStatus.Idle
            manifest.versionCode <= installedVersionCode -> UpdateStatus.Idle
            apkFile(manifest).isFile -> UpdateStatus.ReadyToInstall(manifest)
            else -> UpdateStatus.Available(manifest)
        }
    }

    /**
     * App-private storage (`filesDir/updates`), not the shared Downloads folder: nothing
     * else on the device can swap the bytes out between verification and install, and it
     * needs no storage permission. PackageInstaller reads the bytes through our own
     * stream, so no FileProvider URI is involved either.
     */
    private fun apkFile(manifest: UpdateManifest): File =
        File(
            File(app.filesDir, UpdateConfig.DOWNLOAD_DIR),
            "thumbtrek-" + manifest.versionCode + ".apk",
        )

    /** Superseded downloads are dead weight — an APK is megabytes. */
    private fun pruneDownloads(keep: File?) {
        File(app.filesDir, UpdateConfig.DOWNLOAD_DIR).listFiles()?.forEach { file ->
            if (file != keep) file.delete()
        }
    }

    private fun isUnmetered(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        @Volatile
        private var instance: UpdateRepository? = null

        fun get(context: Context): UpdateRepository =
            instance ?: synchronized(this) {
                instance ?: UpdateRepository(context).also { instance = it }
            }
    }
}
