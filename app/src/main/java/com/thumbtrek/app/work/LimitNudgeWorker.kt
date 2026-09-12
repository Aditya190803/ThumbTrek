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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.thumbtrek.app.MainActivity
import com.thumbtrek.app.R
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.limitNudgeLevel
import com.thumbtrek.app.stats.pixelsToMeters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The limit's voice: at most two quiet notifications a day, and only the ones earned.
 *
 * A cap you only see when you open the app is decoration. This fires level 1 once, when
 * today reaches 80% of the limit, and level 2 once, on breach — then stays silent, because
 * a limit app that nags is the thing PRD §3 rules out. Opt-in and off by default, like
 * every other nag here.
 */
class LimitNudgeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.limitNudge.value) {
            cancel(applicationContext)
            return Result.success()
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val today = LocalDate.now()
        val px = ScrollDatabase.get(applicationContext).dao().allDays()
            .firstOrNull { it.date == today.toString() }?.pixels ?: 0L
        val dpi = applicationContext.resources.displayMetrics.densityDpi
        val todayM = pixelsToMeters(px, dpi)
        val limitM = prefs.dailyLimitM.value.toDouble()

        val level = limitNudgeLevel(todayM, limitM)
        if (level <= 0) return Result.success()
        // One notification per level per day: a warn already fired doesn't re-fire, and a
        // breach after a warn still gets its one line — escalating is not repeating.
        if (prefs.lastLimitNudgeDay == today.toString() && prefs.lastLimitNudgeLevel >= level) {
            return Result.success()
        }

        val (title, text) = if (level == 1) {
            "Approaching your limit" to
                "${formatDistance(limitM - todayM)} to go before your " +
                "${formatDistance(limitM)} limit."
        } else {
            "Limit passed" to
                "Over by ${formatDistance(todayM - limitM)} — tomorrow under " +
                "${formatDistance(limitM)} starts a new run."
        }

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, LIMIT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(LIMIT_NOTIFICATION_ID, notification)
        prefs.recordLimitNudge(today.toString(), level)
        return Result.success()
    }

    companion object {
        const val LIMIT_CHANNEL_ID = "limit_nudge"

        private const val UNIQUE_WORK = "limit_nudge"
        private const val LIMIT_NOTIFICATION_ID = 3

        /** Every 4 hours: prompt enough to catch a breach mid-day, quiet enough to forget. */
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LimitNudgeWorker>(4, TimeUnit.HOURS).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
