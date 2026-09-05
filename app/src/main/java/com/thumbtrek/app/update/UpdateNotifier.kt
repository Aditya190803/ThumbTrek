package com.thumbtrek.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.thumbtrek.app.R

/**
 * The "your update is downloaded, tap to install" notification — the closest thing to
 * hands-off updating a sideloaded app can offer.
 *
 * The channel is created here rather than in `ThumbTrekApp` so the whole updater stays
 * self-contained; `createNotificationChannel` is idempotent, so calling it before each
 * post costs nothing.
 */
object UpdateNotifier {

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                UpdateConfig.CHANNEL_ID,
                "App updates",
                // LOW: a badge and a quiet entry in the shade. An update is not urgent
                // enough to buzz a phone at 3am.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Tells you when a new ThumbTrek build has been downloaded."
            },
        )
    }

    /**
     * Tapping this goes to [UpdateInstallActivity], which exists so the install has a
     * foreground activity to launch the system confirmation screen from.
     */
    fun notifyReady(context: Context, manifest: UpdateManifest) {
        if (!canPost(context)) return
        ensureChannel(context)

        val tap = PendingIntent.getActivity(
            context,
            manifest.versionCode.toInt(),
            Intent(context, UpdateInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val summary = "ThumbTrek ${manifest.versionName} is downloaded and verified. " +
            "Tap to install — Android will ask you to confirm."

        val notification = NotificationCompat.Builder(context, UpdateConfig.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Update ready: ${manifest.versionName}")
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (manifest.notes.isBlank()) summary else "$summary\n\n${manifest.notes}",
                ),
            )
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(UpdateConfig.NOTIFICATION_ID_READY, notification)
        }
    }

    fun cancelReady(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(UpdateConfig.NOTIFICATION_ID_READY)
        }
    }

    /** Android 13+ needs the runtime grant; without it we stay silent rather than crash. */
    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
}
