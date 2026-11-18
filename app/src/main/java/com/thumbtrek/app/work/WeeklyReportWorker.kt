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
import com.thumbtrek.app.ThumbTrekApp
import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.ReportSpan
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.report
import com.thumbtrek.app.stats.reportSummary
import com.thumbtrek.app.stats.reportTitle
import com.thumbtrek.app.stats.weekKey
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Local history as the report functions read it: metres per app per day. */
fun reportDays(rows: List<DailyScroll>, densityDpi: Int): Map<LocalDate, Map<String, Double>> =
    rows.groupBy { it.date }.mapNotNull { (date, dayRows) ->
        val day = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
        day to dayRows.associate { it.packageName to pixelsToMeters(it.pixels, densityDpi) }
    }.toMap()

/**
 * One notification a week with last week's figures, sent the first time the job runs in a
 * new ISO week. Opt-in and off by default. The job itself runs daily because WorkManager
 * cannot be asked for "Monday morning"; [Prefs.reportedWeek] is what makes it weekly.
 */
class WeeklyReportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.weeklyReport.value) {
            cancel(applicationContext)
            return Result.success()
        }
        val today = LocalDate.now()
        val thisWeek = weekKey(today)
        if (prefs.reportedWeek == thisWeek) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                applicationContext, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.success()
        }

        val dpi = applicationContext.resources.displayMetrics.densityDpi
        val days = reportDays(ScrollDatabase.get(applicationContext).dao().allRows(), dpi)
        val last = report(
            span = ReportSpan.WEEK,
            days = days,
            today = today,
            offset = 1,
            limitMeters = prefs.dailyLimitM.value?.toDouble(),
            limitSince = prefs.limitSince.value,
        )
        // The week is marked done either way, so an empty week does not retry all week.
        prefs.reportedWeek = thisWeek
        if (last.totalMeters <= 0.0) return Result.success()

        val notification = NotificationCompat.Builder(applicationContext, ThumbTrekApp.DAILY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your week, ${reportTitle(last)}")
            .setContentText(reportSummary(last))
            .setStyle(NotificationCompat.BigTextStyle().bigText(reportSummary(last)))
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext, 0,
                    Intent(applicationContext, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "weekly_report"
        private const val NOTIFICATION_ID = 7

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeeklyReportWorker>(12, TimeUnit.HOURS).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
