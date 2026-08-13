package com.thumbtrek.app.stats

import com.thumbtrek.app.data.DailyScroll
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
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

/** One column/point of a history chart: [key] is stable and sortable, [label] goes on the axis. */
data class Bucket(val key: String, val label: String, val pixels: Long)

/** One app's line in a PRD §5.3 trend chart. Every series shares the same [points] keys and order. */
data class AppSeries(val packageName: String, val points: List<Bucket>)

private val DAY_LABEL = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MONTH_LABEL = DateTimeFormatter.ofPattern("MMM", Locale.US)

private fun dayWindow(dayCount: Int, today: LocalDate): List<LocalDate> =
    if (dayCount <= 0) emptyList() else (dayCount - 1 downTo 0).map { today.minusDays(it.toLong()) }

/** PRD §5.3: the last [dayCount] days, oldest first, zero-filled. */
fun dailyBuckets(
    days: Map<LocalDate, Long>,
    dayCount: Int = 7,
    today: LocalDate = LocalDate.now(),
): List<Bucket> = dayWindow(dayCount, today).map { date ->
    Bucket(date.toString(), DAY_LABEL.format(date), days[date] ?: 0L)
}

/** PRD §5.3: the last [weekCount] ISO weeks (Monday–Sunday), oldest first, zero-filled. */
fun weeklyBuckets(
    days: Map<LocalDate, Long>,
    weekCount: Int = 8,
    today: LocalDate = LocalDate.now(),
): List<Bucket> {
    if (weekCount <= 0) return emptyList()
    val thisMonday = today.with(DayOfWeek.MONDAY)
    return (weekCount - 1 downTo 0).map { back ->
        val monday = thisMonday.minusWeeks(back.toLong())
        val sunday = monday.plusDays(6)
        Bucket(
            weekKey(monday),
            DAY_LABEL.format(monday),
            days.filterKeys { it in monday..sunday }.values.sum(),
        )
    }
}

/** PRD §5.3: the last [monthCount] calendar months, oldest first, zero-filled. */
fun monthlyBuckets(
    days: Map<LocalDate, Long>,
    monthCount: Int = 6,
    today: LocalDate = LocalDate.now(),
): List<Bucket> {
    if (monthCount <= 0) return emptyList()
    val thisMonth = YearMonth.from(today)
    return (monthCount - 1 downTo 0).map { back ->
        val month = thisMonth.minusMonths(back.toLong())
        Bucket(
            month.toString(),
            month.format(MONTH_LABEL),
            days.filterKeys { YearMonth.from(it) == month }.values.sum(),
        )
    }
}

/**
 * PRD §5.3: one zero-filled daily series per app over the last [dayCount] days, busiest app first.
 * Apps with no rows inside the window are dropped; rows outside it are ignored.
 */
fun appTrends(
    rows: List<DailyScroll>,
    dayCount: Int = 14,
    today: LocalDate = LocalDate.now(),
): List<AppSeries> {
    val window = dayWindow(dayCount, today)
    if (window.isEmpty()) return emptyList()
    val slots = window.withIndex().associate { (index, date) -> date.toString() to index }
    val byApp = LinkedHashMap<String, LongArray>()
    rows.forEach { row ->
        val slot = slots[row.date] ?: return@forEach
        byApp.getOrPut(row.packageName) { LongArray(window.size) }[slot] += row.pixels
    }
    return byApp.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, LongArray>> { it.value.sum() }.thenBy { it.key },
        )
        .map { (pkg, pixels) ->
            AppSeries(
                pkg,
                window.mapIndexed { index, date ->
                    Bucket(date.toString(), DAY_LABEL.format(date), pixels[index])
                },
            )
        }
}

/** Percent change against [previous]; null when there's no baseline to compare against. */
fun percentDelta(current: Long, previous: Long): Int? =
    if (previous <= 0L) null else ((current - previous) * 100.0 / previous).roundToInt()

/** PRD §5.5: "12% more than yesterday". */
fun dayOverDayDelta(days: Map<LocalDate, Long>, today: LocalDate = LocalDate.now()): Int? =
    percentDelta(days[today] ?: 0L, days[today.minusDays(1)] ?: 0L)

/** PRD §5.3: this week so far against the same stretch of last week, so partial weeks are fair. */
fun weekOverWeekDelta(days: Map<LocalDate, Long>, today: LocalDate = LocalDate.now()): Int? {
    val monday = today.with(DayOfWeek.MONDAY)
    val current = days.filterKeys { it in monday..today }.values.sum()
    val previous = days.filterKeys { it in monday.minusWeeks(1)..today.minusWeeks(1) }.values.sum()
    return percentDelta(current, previous)
}

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
