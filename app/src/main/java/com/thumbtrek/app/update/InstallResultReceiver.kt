package com.thumbtrek.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

/**
 * Where [PackageInstaller] reports back after a session is committed.
 *
 * The interesting branch is `STATUS_PENDING_USER_ACTION`: the system is telling us it
 * wants the user to confirm, and hands over an Intent pointing at its own install screen.
 * We cannot start an activity from here reliably (background activity starts are blocked
 * on modern Android), so the intent is pushed into [UpdateRepository] and whichever
 * foreground surface committed the session — the settings card, or
 * [UpdateInstallActivity] launched from the notification — launches it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateConfig.ACTION_INSTALL_RESULT) return

        val repository = UpdateRepository.get(context)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java,
                )
                if (confirm != null) {
                    repository.onConfirmationRequired(confirm)
                } else {
                    repository.onInstallFailed("The system asked for confirmation but sent no screen to show.")
                }
            }

            // Rarely observed in practice: a successful self-update kills our process on
            // the spot, often before this ever runs. Handled anyway for the case where it
            // does land (and so the notification gets cleared).
            PackageInstaller.STATUS_SUCCESS -> repository.onInstallSucceeded()

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                repository.onInstallFailed("Install cancelled.")

            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                repository.onInstallFailed(
                    "Install blocked by a signature conflict — the installed copy was signed " +
                        "with a different key. Uninstall ThumbTrek first (export your data " +
                        "from Settings before you do; uninstalling wipes local history).",
                )

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                repository.onInstallFailed(message ?: "Install failed (status $status).")
            }
        }
    }
}
