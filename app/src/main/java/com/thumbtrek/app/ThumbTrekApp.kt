package com.thumbtrek.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.thumbtrek.app.work.DailySummaryWorker
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.widget.TrekWidgetProvider
import com.thumbtrek.app.work.StreakReminderWorker
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class ThumbTrekApp : Application() {

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                DAILY_CHANNEL_ID,
                "Daily summary",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                StreakReminderWorker.STREAK_CHANNEL_ID,
                "Streak reminders",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                SOCIAL_CHANNEL_ID,
                "Social",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_summary",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayUntilNextSummary())
                .build(),
        )
        if (Prefs.get(this).streakReminder.value) StreakReminderWorker.schedule(this)
        TrekWidgetProvider.updateAll(this)
    }

    /** Next 21:00 local — daily summary per PRD §5.5. */
    private fun delayUntilNextSummary(): Duration {
        val now = LocalDateTime.now()
        var next = now.withHour(21).withMinute(0).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    companion object {
        const val DAILY_CHANNEL_ID = "daily_summary"
        const val SOCIAL_CHANNEL_ID = "social"
    }
}
