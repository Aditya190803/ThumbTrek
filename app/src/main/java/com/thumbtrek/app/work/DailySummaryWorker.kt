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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.thumbtrek.app.MainActivity
import com.thumbtrek.app.R
import com.thumbtrek.app.ThumbTrekApp
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import java.time.LocalDate
import kotlin.math.roundToInt

/** Posts "You trekked X yesterday — N% more/less than the day before" once a day. */
class DailySummaryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val days = ScrollDatabase.get(applicationContext).dao().allDays()
            .associate { LocalDate.parse(it.date) to it.pixels }

        val yesterday = LocalDate.now().minusDays(1)
        val px = days[yesterday] ?: return Result.success()

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val meters = pixelsToMeters(px, applicationContext.resources.displayMetrics.densityDpi)
        val dayBefore = days[yesterday.minusDays(1)]
        val trend = if (dayBefore != null && dayBefore > 0) {
            val pct = ((px - dayBefore).toDouble() / dayBefore * 100).roundToInt()
            if (pct >= 0) " — $pct% more than the day before" else " — ${-pct}% less than the day before"
        } else {
            ""
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val headline = "You trekked ${formatDistance(meters)} yesterday$trend."

        val notification = NotificationCompat.Builder(applicationContext, ThumbTrekApp.DAILY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ThumbTrek")
            .setContentText(headline)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$headline ${comparison(meters)}"))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(DAILY_NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        private const val DAILY_NOTIFICATION_ID = 1
    }
}
