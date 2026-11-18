package com.thumbtrek.app.work

import android.Manifest
import android.annotation.SuppressLint
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
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.social.micrometresToMeters
import com.thumbtrek.app.stats.appLimitStatuses
import com.thumbtrek.app.stats.appNudgesDue
import com.thumbtrek.app.stats.formatDistance
import kotlinx.coroutines.flow.first
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
        val appLimits = prefs.appLimits.value
        if (!prefs.limitNudge.value || (prefs.dailyLimitM.value == null && appLimits.isEmpty())) {
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
        val dao = ScrollDatabase.get(applicationContext).dao()
        val dpi = applicationContext.resources.displayMetrics.densityDpi
        val perApp = dao.observeDay(today.toString()).first()
            .associate { it.packageName to pixelsToMeters(it.pixels, dpi) }
        notifyAppLimits(prefs, today.toString(), perApp, appLimits)

        // With a shared limit the browser's distance counts too, as of its last sync.
        val other = prefs.otherSourcesToday.value
            ?.takeIf { prefs.sharedLimit.value && it.date == today.toString() }
            ?.let { micrometresToMeters(it.um) } ?: 0.0
        val todayM = perApp.values.sum() + other
        val limitM = prefs.dailyLimitM.value?.toDouble() ?: return Result.success()

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
                "Over by ${formatDistance(todayM - limitM)}. Tomorrow under " +
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

    /** One line per app per level per day, the same economy as the daily limit's nudge. */
    // doWork has already returned if the notification permission is missing.
    @SuppressLint("MissingPermission")
    private fun notifyAppLimits(
        prefs: Prefs,
        day: String,
        perApp: Map<String, Double>,
        limits: Map<String, Float>,
    ) {
        if (limits.isEmpty()) return
        val due = appNudgesDue(appLimitStatuses(perApp, limits), prefs.appNudgesFired(day))
        val labels = prefs.customApps.value
        due.forEach { status ->
            val name = appName(status.key, labels)
            val limit = formatDistance(status.limitMeters)
            val (title, text) = if (status.level == 1) {
                "$name is close to its limit" to
                    "${formatDistance(status.limitMeters - status.meters)} to go before its $limit limit."
            } else {
                "$name passed its limit" to
                    "Over by ${formatDistance(status.meters - status.limitMeters)} on a $limit limit."
            }
            val notification = NotificationCompat.Builder(applicationContext, LIMIT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
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
            // The tag keeps one notification per app instead of each replacing the last.
            runCatching {
                NotificationManagerCompat.from(applicationContext)
                    .notify(status.key, APP_LIMIT_NOTIFICATION_ID, notification)
            }
            prefs.recordAppNudge(day, status.key, status.level)
        }
    }

    companion object {
        const val LIMIT_CHANNEL_ID = "limit_nudge"
        private const val APP_LIMIT_NOTIFICATION_ID = 8

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
