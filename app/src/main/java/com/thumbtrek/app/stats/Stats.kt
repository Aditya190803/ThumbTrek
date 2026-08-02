package com.thumbtrek.app.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.roundToInt

/** PRD §7: pixels / densityDpi * 0.0254 = meters */
fun pixelsToMeters(pixels: Long, densityDpi: Int): Double =
    pixels.toDouble() / densityDpi * 0.0254

fun formatDistance(meters: Double): String =
    if (meters < 1000) {
        "${meters.roundToInt()} m"
    } else {
        val km = String.format(Locale.US, "%.2f", meters / 1000).trimEnd('0').trimEnd('.')
        "$km km"
    }

/** Consecutive days with activity, ending today (or yesterday if today is still empty). */
fun trekStreak(activeDates: Collection<LocalDate>, today: LocalDate = LocalDate.now()): Int {
    val days = activeDates.toHashSet()
    var cursor = if (today in days) today else today.minusDays(1)
    var streak = 0
    while (cursor in days) {
        streak++
        cursor = cursor.minusDays(1)
    }
    return streak
}

fun totalThisWeek(days: Map<LocalDate, Long>, today: LocalDate = LocalDate.now()): Long {
    val monday = today.with(DayOfWeek.MONDAY)
    return days.filterKeys { it in monday..today }.values.sum()
}

fun totalThisMonth(days: Map<LocalDate, Long>, today: LocalDate = LocalDate.now()): Long {
    val month = YearMonth.from(today)
    return days.filterKeys { YearMonth.from(it) == month }.values.sum()
}

/** ISO week id like "2026-W31". Leaderboard "weekly reset" = this string changing. */
fun weekKey(date: LocalDate = LocalDate.now()): String {
    val week = date.get(WeekFields.ISO.weekOfWeekBasedYear())
    val year = date.get(WeekFields.ISO.weekBasedYear())
    return String.format(Locale.US, "%d-W%02d", year, week)
}

/** Longest single-day trek, or null when there's no data. */
fun personalRecord(days: Map<LocalDate, Long>): Pair<LocalDate, Long>? =
    days.maxByOrNull { it.value }?.toPair()

data class Landmark(val meters: Double, val label: String)

/** Ascending. Fun comparisons per PRD §5.2. */
val LANDMARKS = listOf(
    Landmark(0.18, "a banana"),
    Landmark(28.0, "a basketball court"),
    Landmark(93.0, "the Statue of Liberty"),
    Landmark(105.0, "a football pitch"),
    Landmark(330.0, "the Eiffel Tower"),
    Landmark(830.0, "the Burj Khalifa"),
    Landmark(2_737.0, "the Golden Gate Bridge"),
    Landmark(8_849.0, "Mount Everest"),
    Landmark(42_195.0, "a marathon"),
)

fun comparison(meters: Double): String {
    if (meters <= 0.0) return "No trek yet — go scroll something."
    val landmark = LANDMARKS.lastOrNull { meters >= it.meters } ?: LANDMARKS.first()
    val times = meters / landmark.meters
    return "That's ${String.format(Locale.US, "%.1f", times)}× ${landmark.label}."
}
