package com.thumbtrek.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.thumbtrek.app.MainActivity
import com.thumbtrek.app.R
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.stats.formatDistance
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
            return WidgetData(
                todayLabel = formatDistance(pixelsToMeters(byDate[today] ?: 0L, dpi)),
                weekLabel = formatDistance(pixelsToMeters(totalThisWeek(byDate), dpi)),
                streak = trekStreak(byDate.keys),
            )
        }
    }
}

private data class WidgetData(val todayLabel: String, val weekLabel: String, val streak: Int)
