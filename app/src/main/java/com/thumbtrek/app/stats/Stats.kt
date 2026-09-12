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

/** Default daily limit in metres: about one football pitch. Achievable but disciplined. */
const val DEFAULT_DAILY_LIMIT_M = 100.0

/** Hard bounds for the limit editor, so a typo can't create an unwinnable game. */
const val MIN_DAILY_LIMIT_M = 10.0
const val MAX_DAILY_LIMIT_M = 10_000.0

/** One free limit change per ISO week; further changes need premium (PRD §11). */
const val FREE_LIMIT_EDITS_PER_WEEK = 1

/**
 * Paywall master switch. Off during the 1-month free data window: limits and streaks stay
 * free while clean-streak retention is measured, and billing is decided on that data.
 * The weekly quota + Premium machinery underneath keeps recording edits, so flipping this
 * to true later enforces the paywall with history already in place — no migration.
 */
const val BILLING_ENFORCED = false

/** A day is clean when its trek stays at or under the limit. Zero counts: not scrolling
 * is the ultimate under-limit day. A non-positive limit is misconfiguration, never clean. */
fun isCleanDay(meters: Double, limitMeters: Double): Boolean =
    limitMeters > 0 && meters <= limitMeters

/**
 * Consecutive clean days ending today, for the limit theme (PRD §11): the streak that
 * rewards scrolling *less*, not more.
 *
 * [dayMeters] maps each tracked date to that day's metres; a date with no entry reads as
 * 0 m (no scrolling measured). Unlike [trekStreak] there is no yesterday fallback: a day
 * over the limit today means the streak is broken *today*, and showing yesterday's number
 * would pretend otherwise. The streak never extends before [firstDay] (the earliest
 * tracked date, or null when there is no history), so a fresh install doesn't inherit an
 * infinite pre-install abstinence streak.
 */
fun limitStreak(
    dayMeters: Map<LocalDate, Double>,
    limitMeters: Double,
    today: LocalDate = LocalDate.now(),
    firstDay: LocalDate? = dayMeters.keys.minOrNull(),
): Int {
    if (limitMeters <= 0) return 0
    var streak = 0
    var cursor = today
    while (true) {
        if (firstDay != null && cursor.isBefore(firstDay)) break
        // A date with no entry reads as 0 m (nothing measured); the gameable edge --
        // disabling tracking reads as abstinence -- is documented, not solved, in v1.
        if ((dayMeters[cursor] ?: 0.0) > limitMeters) break
        streak++
        if (firstDay == null) break // no history: today alone counts once
        cursor = cursor.minusDays(1)
    }
    return streak
}

/**
 * Free limit edits remaining this week, given the stored edit week/count. While the
 * paywall is off ([BILLING_ENFORCED] == false) this is unbounded — the quota still
 * *records* every edit, so the billing decision later has real edits-per-week data.
 */
fun freeLimitEditsLeft(
    editWeekKey: String?,
    editCount: Int,
    today: LocalDate = LocalDate.now(),
    billingEnforced: Boolean = BILLING_ENFORCED,
): Int {
    if (!billingEnforced) return Int.MAX_VALUE
    if (editWeekKey != weekKey(today)) return FREE_LIMIT_EDITS_PER_WEEK
    return (FREE_LIMIT_EDITS_PER_WEEK - editCount).coerceAtLeast(0)
}

/** Warn once today's trek reaches this fraction of the limit. Below it: silence. */
const val LIMIT_WARN_FRACTION = 0.8

/**
 * Which limit nudge (if any) today has earned: 0 none, 1 approaching (at 80% of the
 * limit), 2 breached (over it). Pure so the worker stays thin and this is unit-tested;
 * the worker adds the only stateful rule — one notification per level per day.
 */
fun limitNudgeLevel(todayMeters: Double, limitMeters: Double): Int {
    if (limitMeters <= 0 || todayMeters <= 0) return 0
    if (todayMeters > limitMeters) return 2
    if (todayMeters >= limitMeters * LIMIT_WARN_FRACTION) return 1
    return 0
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

/** Month id like "2026-08". Monthly board reset works the same way as [weekKey]. */
fun monthKey(date: LocalDate = LocalDate.now()): String = YearMonth.from(date).toString()

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

/**
 * Ascending. Fun comparisons per PRD §5.2. Kept dense at the low end on purpose — the
 * old list jumped straight from a banana (0.18 m) to a basketball court (28 m), a 155×
 * gap that left every short trek reading as some large multiple of banana instead of
 * moving on to a new object.
 */
val LANDMARKS = listOf(
    Landmark(0.18, "a banana"),
    Landmark(0.3, "a sneaker"),
    Landmark(1.0, "a doorway"),
    Landmark(1.8, "a queen-size bed"),
    Landmark(4.5, "a parked car"),
    Landmark(12.0, "a school bus"),
    Landmark(19.0, "a bowling lane"),
    Landmark(28.0, "a basketball court"),
    Landmark(93.0, "the Statue of Liberty"),
    Landmark(105.0, "a football pitch"),
    Landmark(269.0, "the Titanic"),
    Landmark(330.0, "the Eiffel Tower"),
    Landmark(830.0, "the Burj Khalifa"),
    Landmark(1_609.0, "a mile"),
    Landmark(2_737.0, "the Golden Gate Bridge"),
    Landmark(5_000.0, "a 5K run"),
    Landmark(8_849.0, "Mount Everest"),
    Landmark(21_097.0, "a half marathon"),
    Landmark(42_195.0, "a marathon"),
    Landmark(100_000.0, "a 100K ultramarathon"),
    Landmark(384_400_000.0, "the Moon"),
)

fun comparison(meters: Double): String {
    if (meters <= 0.0) return "No trek yet — go scroll something."
    val landmark = LANDMARKS.lastOrNull { meters >= it.meters } ?: LANDMARKS.first()
    val times = meters / landmark.meters
    return "That's ${String.format(Locale.US, "%.1f", times)}× ${landmark.label}."
}

/** One achievement: [progress] is clamped 0..1 so UI can show a fill or lock. */
data class Badge(
    val id: String,
    val emoji: String,
    val label: String,
    val detail: String,
    val earned: Boolean,
    val progress: Double,
)

private fun badge(
    id: String,
    emoji: String,
    label: String,
    value: Double,
    target: Double,
    detail: String,
): Badge {
    val progress = if (target <= 0.0) 1.0 else (value / target).coerceIn(0.0, 1.0)
    return Badge(id, emoji, label, detail, progress >= 1.0, progress)
}

/**
 * Local achievements computed from history alone — no server, no extra storage.
 * [totalMeters] is the all-time distance, [bestDayMeters] the longest single day,
 * [cleanStreak] the current under-limit run (PRD §11.7). The clean ladder rewards scrolling
 * less, on purpose: it is the counterweight to the distance ladder, not a duplicate.
 */
fun badges(
    totalMeters: Double,
    bestDayMeters: Double,
    streak: Int,
    cleanStreak: Int = 0,
): List<Badge> = listOf(
    badge("first_trek", "👣", "First steps", totalMeters, 1.0,
        "Complete your first trek"),
    badge("banana", "🍌", "Banana", totalMeters, 100.0,
        "Trek 100 m in total"),
    badge("kilometer", "🎯", "Kilometer club", totalMeters, 1_000.0,
        "Trek 1 km in total"),
    badge("bridge", "🌉", "Golden Gate", totalMeters, 2_737.0,
        "Trek 2.7 km in total"),
    badge("fivek", "🏅", "5K", totalMeters, 5_000.0,
        "Trek 5 km in total"),
    badge("burj_day", "🏙️", "Burj day", bestDayMeters, 830.0,
        "Trek 830 m — the Burj Khalifa — in a single day"),
    badge("tenk", "🥉", "10K", totalMeters, 10_000.0,
        "Trek 10 km in total"),
    badge("half_marathon", "🥈", "Half marathon", totalMeters, 21_097.0,
        "Trek 21.1 km in total"),
    badge("everest", "🏔️", "Everest", bestDayMeters, 8_849.0,
        "Trek 8.8 km in a single day"),
    badge("marathon", "🏃", "Marathon thumb", totalMeters, 42_195.0,
        "Trek 42.2 km in total"),
    badge("ultra", "🥇", "Ultra", totalMeters, 50_000.0,
        "Trek 50 km in total"),
    badge("century", "💯", "Century", totalMeters, 100_000.0,
        "Trek 100 km in total"),
    badge("quarter_million", "🚀", "Quarter-million", totalMeters, 250_000.0,
        "Trek 250 km in total"),
    badge("half_million", "⭐", "Half-million", totalMeters, 500_000.0,
        "Trek 500 km in total"),
    badge("million", "🌟", "Thousand-K club", totalMeters, 1_000_000.0,
        "Trek 1,000 km in total"),
    badge("streak_3", "🔥", "Warm-up", streak.toDouble(), 3.0,
        "Keep a 3-day streak"),
    badge("streak_7", "📅", "Week trekker", streak.toDouble(), 7.0,
        "Keep a 7-day streak"),
    badge("streak_14", "🌱", "Two-week habit", streak.toDouble(), 14.0,
        "Keep a 14-day streak"),
    badge("streak_30", "🗓️", "Monthly mover", streak.toDouble(), 30.0,
        "Keep a 30-day streak"),
    badge("streak_100", "💎", "Centurion streak", streak.toDouble(), 100.0,
        "Keep a 100-day streak"),
    badge("streak_365", "👑", "Year-round trekker", streak.toDouble(), 365.0,
        "Keep a 365-day streak"),
    badge("clean_3", "🧼", "Clean slate", cleanStreak.toDouble(), 3.0,
        "Stay under your limit 3 days in a row"),
    badge("clean_7", "🛡️", "Under control", cleanStreak.toDouble(), 7.0,
        "Stay under your limit 7 days in a row"),
    badge("clean_14", "🧘", "Steady mind", cleanStreak.toDouble(), 14.0,
        "Stay under your limit 14 days in a row"),
    badge("clean_30", "🏵️", "Month of restraint", cleanStreak.toDouble(), 30.0,
        "Stay under your limit 30 days in a row"),
)
