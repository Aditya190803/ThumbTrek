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
import com.thumbtrek.app.stats.trekStreak
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * PRD §5.5: streak reminders, opt-in and off by default. It only fires when the nag is
 * actually earned — a live streak plus a day with nothing scrolled yet — so it never
 * becomes the digital-wellbeing lecture PRD §3 rules out.
 */
class StreakReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.get(applicationContext).streakReminder.value) {
            cancel(applicationContext)
            return Result.success()
        }

        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val active = ScrollDatabase.get(applicationContext).dao().allDays()
            .filter { it.pixels > 0 }
            .map { LocalDate.parse(it.date) }
            .toSet()

        val today = LocalDate.now()
        if (today in active) return Result.success()

        val streak = trekStreak(active, today)
        if (streak <= 0) return Result.success()

        val days = if (streak == 1) "1 day" else "$streak days"

        val openApp = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, STREAK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$days in, 0 m today")
            .setContentText("Not a lecture — just the one day your streak needs a thumb.")
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(STREAK_NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        const val STREAK_CHANNEL_ID = "streak_reminder"

        private const val UNIQUE_WORK = "streak_reminder"
        private const val STREAK_NOTIFICATION_ID = 2

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StreakReminderWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayUntilNextReminder())
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }

        /** Next 19:00 local — early evening, while there's still time to save the streak. */
        private fun delayUntilNextReminder(): Duration {
            val now = LocalDateTime.now()
            var next = now.withHour(19).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }
}
