package com.thumbtrek.app.data

import android.content.Context
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.suggestedLimit
import com.thumbtrek.app.stats.typicalDayMeters
import java.time.LocalDate

/**
 * Turns a finished baseline week into a daily limit. Called from the daily summary worker,
 * so it happens even if the app isn't opened, and from the app on resume, so whoever opens
 * it on day eight sees the result straight away. Both paths are idempotent:
 * [Prefs.applyAutoLimit] does nothing once the baseline has been consumed.
 */
object BaselineLimit {

    /** Applies the limit if the week is complete; returns the baseline it came from. */
    suspend fun applyIfReady(context: Context, today: LocalDate = LocalDate.now()): Double? {
        val prefs = Prefs.get(context)
        val since = prefs.baselineSince.value ?: return null
        val dpi = context.resources.displayMetrics.densityDpi
        val dayMeters = ScrollDatabase.get(context).dao().allDays()
            .associate { LocalDate.parse(it.date) to pixelsToMeters(it.pixels, dpi) }
        val typical = typicalDayMeters(dayMeters, since, today) ?: return null
        prefs.applyAutoLimit(suggestedLimit(typical), typical, today)
        return typical
    }
}
