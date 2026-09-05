package com.thumbtrek.app.update

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An invisible one-shot activity whose entire job is to exist in the foreground while an
 * install is committed.
 *
 * Why it has to exist: the "update ready" notification needs to lead to an install, and
 * `PackageInstaller` answers a commit with `STATUS_PENDING_USER_ACTION` — an Intent that
 * has to be *started as an activity*. Modern Android blocks background activity starts, so
 * a broadcast receiver cannot launch it. Tapping a notification, on the other hand, is
 * allowed to bring an activity up, and once we are that activity we can start the system's
 * confirmation screen without argument.
 *
 * It draws nothing (translucent theme, see AndroidManifest) and finishes as soon as the
 * install screen is up, so the user perceives: tap notification → Android asks "Install
 * this update?" → done.
 */
class UpdateInstallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repository = UpdateRepository.get(this)

        lifecycleScope.launch {
            // Any stale intent from a previous attempt would fire immediately otherwise.
            repository.consumeConfirmIntent()
            repository.installReadyUpdate()

            // On Android 12+ a self-update may be allowed through with no confirmation at
            // all, in which case nothing ever arrives here — hence the timeout rather than
            // an open-ended collect that would pin an invisible activity forever.
            val confirm = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
                repository.confirmIntents.first()
            }
            if (confirm != null) {
                repository.consumeConfirmIntent()
                runCatching { startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            finish()
        }
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 20_000L
    }
}
