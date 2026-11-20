package com.thumbtrek.app.stats

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Weekly and monthly reports. One pure function over the local history, ported line for
// line to web/app/report.js and extension/lib/report.js: the three must print the same
// figures for the same days.

enum class ReportSpan { WEEK, MONTH }

/** One app's line in a report. [previousMeters] is the same app in the period before. */
data class ReportApp(val key: String, val meters: Double, val previousMeters: Double)

data class Report(
    val span: ReportSpan,
    val from: LocalDate,
    /** Last day of the period, or today when the period is still running. */
    val to: LocalDate,
    /** True while the period has days left in it. */
    val running: Boolean,
    val totalMeters: Double,
    val previousMeters: Double,
    /** Percent change against the previous period, or null when that one was empty. */
    val deltaPercent: Int?,
    /** Mean over the days that have passed, not the days with data. */
    val dailyAverageMeters: Double,
    val activeDays: Int,
    val elapsedDays: Int,
    /** Days at or under the limit, among [judgedDays]. Null with no limit. */
    val cleanDays: Int?,
    val judgedDays: Int,
    val highest: Pair<LocalDate, Double>?,
    val lowest: Pair<LocalDate, Double>?,
    /** Largest first, at most [REPORT_APPS]. */
    val apps: List<ReportApp>,
)

const val REPORT_APPS = 6

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

/** The first day of the period [offset] periods back from the one containing [today]. */
fun reportStart(span: ReportSpan, today: LocalDate, offset: Int = 0): LocalDate = when (span) {
    ReportSpan.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .minusWeeks(offset.toLong())
    ReportSpan.MONTH -> YearMonth.from(today).minusMonths(offset.toLong()).atDay(1)
}

private fun reportEnd(span: ReportSpan, start: LocalDate): LocalDate = when (span) {
    ReportSpan.WEEK -> start.plusDays(6)
    ReportSpan.MONTH -> YearMonth.from(start).atEndOfMonth()
}

/**
 * Builds the report for one period.
 *
 * @param days metres per app per day; a missing day is a day with nothing measured.
 * @param offset 0 for the period containing [today], 1 for the one before it.
 * @param limitMeters the daily limit, when there is one.
 * @param limitSince days before this are never judged against the limit.
 */
fun report(
    span: ReportSpan,
    days: Map<LocalDate, Map<String, Double>>,
    today: LocalDate = LocalDate.now(),
    offset: Int = 0,
    limitMeters: Double? = null,
    limitSince: LocalDate? = null,
): Report {
    val from = reportStart(span, today, offset)
    val end = reportEnd(span, from)
    val to = minOf(end, today)
    val previousFrom = reportStart(span, today, offset + 1)
    val previousTo = reportEnd(span, previousFrom)

    fun window(a: LocalDate, b: LocalDate) = days.filterKeys { it in a..b }
    val current = window(from, to)
    val previous = window(previousFrom, previousTo)

    // Sorted, so ties for the highest and lowest day resolve to the earlier date everywhere.
    val dayTotals = current.mapValues { it.value.values.sum() }.filterValues { it > 0.0 }.toSortedMap()
    val total = dayTotals.values.sum()
    val previousTotal = previous.values.sumOf { it.values.sum() }
    val elapsed = (to.toEpochDay() - from.toEpochDay() + 1).toInt().coerceAtLeast(1)

    // A day with nothing measured cannot be over the limit, but it is only counted as
    // clean when it was actually tracked: an empty day proves nothing either way.
    val judged = dayTotals.filterKeys { limitSince == null || it >= limitSince }
    val clean = limitMeters?.let { limit -> judged.count { isCleanDay(it.value, limit) } }

    val appTotals = HashMap<String, Double>()
    current.values.forEach { apps -> apps.forEach { (k, v) -> appTotals.merge(k, v, Double::plus) } }
    val previousApps = HashMap<String, Double>()
    previous.values.forEach { apps -> apps.forEach { (k, v) -> previousApps.merge(k, v, Double::plus) } }

    return Report(
        span = span,
        from = from,
        to = to,
        running = today in from..end && today < end,
        totalMeters = total,
        previousMeters = previousTotal,
        deltaPercent = percentDelta(Math.round(total), Math.round(previousTotal)),
        dailyAverageMeters = total / elapsed,
        activeDays = dayTotals.size,
        elapsedDays = elapsed,
        cleanDays = clean,
        judgedDays = if (limitMeters == null) 0 else judged.size,
        highest = dayTotals.maxByOrNull { it.value }?.toPair(),
        lowest = dayTotals.minByOrNull { it.value }?.toPair(),
        apps = appTotals.entries
            .filter { it.value > 0.0 }
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(REPORT_APPS)
            .map { ReportApp(it.key, it.value, previousApps[it.key] ?: 0.0) },
    )
}

/** "9 to 15 Nov" for a week, "November 2026" for a month. */
fun reportTitle(report: Report): String = when (report.span) {
    ReportSpan.MONTH -> report.from.format(MONTH)
    ReportSpan.WEEK -> {
        val end = report.from.plusDays(6)
        if (report.from.month == end.month) "${report.from.dayOfMonth} to ${end.format(DAY)}"
        else "${report.from.format(DAY)} to ${end.format(DAY)}"
    }
}

/** The one-sentence version, used by the weekly notification. */
fun reportSummary(report: Report): String {
    val noun = if (report.span == ReportSpan.WEEK) "week" else "month"
    if (report.totalMeters <= 0.0) return "Nothing scrolled last $noun."
    val change = report.deltaPercent?.let {
        when {
            it > 0 -> ", $it% more than the $noun before"
            it < 0 -> ", ${-it}% less than the $noun before"
            else -> ", the same as the $noun before"
        }
    }.orEmpty()
    val clean = report.cleanDays?.let { " ${it} of ${report.judgedDays} days within your limit." }.orEmpty()
    return "${formatDistance(report.totalMeters)} last $noun$change.$clean"
}
