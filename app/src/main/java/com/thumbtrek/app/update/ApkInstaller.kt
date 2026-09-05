package com.thumbtrek.app.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Hands a verified APK to the platform's [PackageInstaller].
 *
 * ### The honest limitation
 * A normal app cannot install packages silently. Two gates stand in the way:
 *
 * 1. **"Install unknown apps"** — since API 26 this is a per-app grant the user makes in
 *    system Settings. [canInstallUnknownApps] / [unknownAppSourcesIntent] handle it; it
 *    is asked for exactly once and then remembered by the system.
 * 2. **The install confirmation dialog** — committing a session normally comes back with
 *    `STATUS_PENDING_USER_ACTION`, and the app must launch a system-owned screen where
 *    the user presses Install. There is no way around this without device-owner or
 *    system/privileged privileges, which a sideloaded app does not have.
 *
 * On Android 12 (API 31) and up there is one narrow exception, and we take it: an app
 * holding `UPDATE_PACKAGES_WITHOUT_USER_ACTION` that is updating *itself* may ask for
 * `USER_ACTION_NOT_REQUIRED`. The system still refuses when it feels like it — most
 * relevantly when the current install was not performed by us, which is exactly the case
 * for a hand-sideloaded build. So: the first self-update after a manual install will
 * show the dialog; later ones may not. We ask, we never assume, and the notification
 * path works identically either way.
 */
object ApkInstaller {

    /** API 26+ per-app grant. Below that (not reachable here, minSdk is 26) it is implicit. */
    fun canInstallUnknownApps(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Deep-links straight to ThumbTrek's row in Settings → Install unknown apps. */
    fun unknownAppSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Streams [apk] into a new install session and commits it. Returns as soon as the
     * session is handed off — the outcome arrives asynchronously at
     * [InstallResultReceiver], which is where the confirmation dialog gets launched from.
     *
     * Must be called with the app in the foreground: if the system asks for confirmation,
     * something visible has to be around to start that screen (see
     * [UpdateInstallActivity], which exists purely so the notification tap has one).
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Best effort only — see the class doc. If the system disagrees we get
                // STATUS_PENDING_USER_ACTION back and show the dialog as normal.
                runCatching {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        }

        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(WRITE_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }

            val callback = Intent(context, InstallResultReceiver::class.java)
                .setAction(UpdateConfig.ACTION_INSTALL_RESULT)
            // MUTABLE because the system fills the result extras in for us. Required from
            // API 31; earlier releases default to mutable and reject the flag.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
            session.commit(pending.intentSender)
        }
    }

    private const val WRITE_NAME = "thumbtrek_update.apk"
}
