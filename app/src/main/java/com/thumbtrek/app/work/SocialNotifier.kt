package com.thumbtrek.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.thumbtrek.app.MainActivity
import com.thumbtrek.app.R
import com.thumbtrek.app.ThumbTrekApp

/**
 * Local social nudges — no push infrastructure, just observations made when the Social
 * tab refreshes: someone sent you a trek request, or you slipped down the global board.
 * Every call is safe to make without notification permission; it just does nothing.
 */
object SocialNotifier {

    private const val REQUEST_NOTIFICATION_ID = 2
    private const val RANK_SLIP_NOTIFICATION_ID = 3

    fun notifyRequest(context: Context, fromName: String) =
        post(context, REQUEST_NOTIFICATION_ID) {
            NotificationCompat.Builder(context, ThumbTrekApp.SOCIAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("New trek request")
                .setContentText("$fromName wants to trek with you — accept on the Social tab.")
                .setAutoCancel(true)
        }

    fun notifyRankSlip(context: Context, newRank: Int) =
        post(context, RANK_SLIP_NOTIFICATION_ID) {
            NotificationCompat.Builder(context, ThumbTrekApp.SOCIAL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("You slipped to #$newRank")
                .setContentText("Others are trekking harder this week. The Social tab has the board.")
                .setAutoCancel(true)
        }

    private inline fun post(
        context: Context,
        id: Int,
        build: () -> NotificationCompat.Builder,
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notification = build()
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }
}
