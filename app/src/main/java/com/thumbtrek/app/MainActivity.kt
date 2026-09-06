package com.thumbtrek.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thumbtrek.app.social.FRIEND_CODE_LENGTH
import com.thumbtrek.app.social.normalizeFriendCode
import com.thumbtrek.app.ui.DashboardViewModel
import com.thumbtrek.app.ui.MainScreen
import com.thumbtrek.app.ui.ThumbTrekTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /**
     * A friend code delivered by an invite link, waiting to be shown on the Social tab.
     * Held here rather than in a ViewModel because it belongs to the *intent*, not to the
     * user's data: once it has been handed to the screen it is spent, and it must not
     * survive a rotation to re-open the Social tab a second time.
     */
    private var pendingInvite by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askNotificationPermissionIfNeeded()
        pendingInvite = inviteCodeFrom(intent)
        setContent {
            ThumbTrekTheme {
                val vm: DashboardViewModel = viewModel()
                MainScreen(
                    vm = vm,
                    pendingInvite = pendingInvite,
                    onInviteConsumed = { pendingInvite = null },
                )
            }
        }
    }

    /** A second invite tapped while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        inviteCodeFrom(intent)?.let { pendingInvite = it }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Pulls a friend code out of `thumbtrek://i/<code>` or `https://thumbtrek.adityamer.dev/i/<code>`.
     *
     * Both shapes end in the code, so the last path segment is enough — except for the
     * custom scheme, where `thumbtrek://i/CODE` parses with host `i` and path `/CODE`, and
     * a bare `thumbtrek://i` has no path at all. [normalizeFriendCode] then does the work it
     * already does for pasted input: it strips the grouping dash, upper-cases, and maps the
     * letters Crockford base32 leaves out.
     *
     * Anything that does not normalise to a full-length code is dropped rather than
     * prefilled, so a truncated or mangled share link opens the app instead of putting a
     * code in the box that could never resolve.
     */
    private fun inviteCodeFrom(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val segment = intent.data?.lastPathSegment ?: return null
        return normalizeFriendCode(segment).takeIf { it.length == FRIEND_CODE_LENGTH }
    }
}
