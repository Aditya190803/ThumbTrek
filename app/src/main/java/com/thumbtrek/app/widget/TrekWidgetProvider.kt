package com.thumbtrek.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.thumbtrek.app.MainActivity
import com.thumbtrek.app.R
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.isCleanDay
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Glanceable today-trek widget. Plain RemoteViews — no Glance dependency. Reads Room
 * directly (a couple of indexed rows, done under [runBlocking] like the tracker's
 * shutdown flush) and is refreshed from app start, the daily summary worker, and the
 * system's own 30-minute tick.
 */
class TrekWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    companion object {
        fun updateAll(context: Context) {
            val appContext = context.applicationContext
            val data = runBlocking(Dispatchers.IO) { read(appContext) } ?: return
            val views = RemoteViews(appContext.packageName, R.layout.widget_trek).apply {
                setTextViewText(R.id.widget_today, data.todayLabel)
                setTextViewText(
                    R.id.widget_streak,
                    if (data.streak > 0) "🔥${data.streak}" else "",
                )
                setTextViewText(R.id.widget_week, "This week: ${data.weekLabel}")
                setTextViewText(R.id.widget_limit, data.limitLabel)
                setOnClickPendingIntent(
                    R.id.widget_root,
                    android.app.PendingIntent.getActivity(
                        appContext,
                        0,
                        android.content.Intent(appContext, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            AppWidgetManager.getInstance(appContext).updateAppWidget(
                ComponentName(appContext, TrekWidgetProvider::class.java),
                views,
            )
        }

        private suspend fun read(context: Context): WidgetData? {
            val days = ScrollDatabase.get(context).dao().allDays()
            if (days.isEmpty()) return null
            val byDate = days.associate { LocalDate.parse(it.date) to it.pixels }
            val dpi = context.resources.displayMetrics.densityDpi
            val today = LocalDate.now()
            val todayM = pixelsToMeters(byDate[today] ?: 0L, dpi)
            val limitM = Prefs.get(context).dailyLimitM.value.toDouble()
            return WidgetData(
                todayLabel = formatDistance(todayM),
                weekLabel = formatDistance(pixelsToMeters(totalThisWeek(byDate), dpi)),
                streak = trekStreak(byDate.keys),
                // Ambient limit: "62 m of 100 m" on track, "112 m — over 100 m" past it.
                limitLabel = if (isCleanDay(todayM, limitM)) {
                    "${formatDistance(todayM)} of ${formatDistance(limitM)} limit"
                } else {
                    "${formatDistance(todayM)} — over ${formatDistance(limitM)}"
                },
            )
        }
    }
}

private data class WidgetData(
    val todayLabel: String,
    val weekLabel: String,
    val streak: Int,
    val limitLabel: String,
)
