package com.thumbtrek.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.CardFonts
import com.thumbtrek.app.share.drawLapOval
import com.thumbtrek.app.share.inset
import com.thumbtrek.app.stats.OvalModel
import com.thumbtrek.app.stats.ovalDescription
import com.thumbtrek.app.stats.ovalModel
import kotlinx.coroutines.flow.first
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
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Glanceable today widget, on the track: today in the wide cut and a stadium lane for the limit. Plain RemoteViews — no Glance dependency. Reads Room
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
                setTextViewText(
                    R.id.widget_streak,
                    if (data.streak > 0) "${data.streak} day streak" else "",
                )
                setImageViewBitmap(R.id.widget_oval, ovalBitmap(appContext, data))
                // The picture's whole content, for a screen reader: the same sentence the
                // in-app oval carries.
                setContentDescription(R.id.widget_oval, data.description)
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

        /**
         * The lap oval as a bitmap, with today's distance in the infield. Drawn at a fixed
         * size and scaled by the ImageView, so it costs the same on every launcher grid.
         */
        private fun ovalBitmap(context: Context, data: WidgetData): Bitmap {
            val bitmap = Bitmap.createBitmap(OVAL_WIDTH, OVAL_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val oval = RectF(0f, 0f, OVAL_WIDTH.toFloat(), OVAL_HEIGHT.toFloat())
            val band = drawLapOval(canvas, oval, data.model)
            val infield = oval.inset(band * data.model.lanes.size)
            val room = infield.width() - infield.height() * 0.5f
            val figure = CardFonts(context).wide(OVAL_HEIGHT * 0.3f, Color.WHITE, weight = 800, spacing = -0.03f)
                .apply { textAlign = Paint.Align.CENTER }
            while (figure.measureText(data.todayLabel) > room && figure.textSize > 20f) figure.textSize -= 2f
            canvas.drawText(data.todayLabel, oval.centerX(), oval.centerY() + figure.textSize * 0.36f, figure)
            return bitmap
        }

        private suspend fun read(context: Context): WidgetData? {
            val dao = ScrollDatabase.get(context).dao()
            val prefs = Prefs.get(context)
            val byDate = dao.allDays().associate { LocalDate.parse(it.date) to it.pixels }
            val dpi = context.resources.displayMetrics.densityDpi
            val today = LocalDate.now()
            val labels = prefs.customApps.value
            val apps = dao.observeDay(today.toString()).first()
                .map { appName(it.packageName, labels) to pixelsToMeters(it.pixels, dpi) }
                .sortedByDescending { it.second }
            val todayM = pixelsToMeters(byDate[today] ?: 0L, dpi)
            val limitM = prefs.dailyLimitM.value?.toDouble()
            val model = ovalModel(todayM, apps, limitM)
            val week = formatDistance(pixelsToMeters(totalThisWeek(byDate), dpi))
            return WidgetData(
                todayLabel = formatDistance(todayM),
                streak = trekStreak(byDate.keys),
                model = model,
                description = ovalDescription(model),
                // The oval already shows how full the lap is; this line says it in words,
                // and falls back to the week when there is no limit to speak of.
                limitLabel = if (limitM == null) "This week: $week" else model.caption,
            )
        }

        // 2.2:1, close to the widget's own shape at its default 3x2 cells.
        private const val OVAL_WIDTH = 660
        private const val OVAL_HEIGHT = 300
    }
}

private data class WidgetData(
    val todayLabel: String,
    val streak: Int,
    val model: OvalModel,
    val description: String,
    val limitLabel: String,
)
