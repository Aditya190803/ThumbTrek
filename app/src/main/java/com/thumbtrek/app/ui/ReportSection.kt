package com.thumbtrek.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.stats.Report
import com.thumbtrek.app.stats.ReportSpan
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.report
import com.thumbtrek.app.stats.reportTitle
import com.thumbtrek.app.work.reportDays
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val REPORT_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)

/**
 * Weekly and monthly reports, on the History tab. The same figures the weekly notification
 * summarises, with the per-app split under them. Reads the rows it needs itself, because
 * nothing else on this screen wants a per-app history two months deep.
 */
@Composable
internal fun ReportSection(
    today: LocalDate,
    limitM: Float?,
    limitSince: LocalDate?,
    customLabels: Map<String, String>,
) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    var span by rememberSaveable { mutableIntStateOf(0) }
    var back by rememberSaveable { mutableIntStateOf(0) }

    // Two full months back covers "last month" and the month it is compared against.
    val since = remember(today) { today.withDayOfMonth(1).minusMonths(2).toString() }
    val rows by remember(since) { ScrollDatabase.get(context).dao().observeRowsSince(since) }
        .collectAsStateWithLifecycle(emptyList())
    val days = remember(rows) { reportDays(rows, dpi) }
    val current = remember(days, span, back, today, limitM, limitSince) {
        report(
            span = if (span == 0) ReportSpan.WEEK else ReportSpan.MONTH,
            days = days,
            today = today,
            offset = back,
            limitMeters = limitM?.toDouble(),
            limitSince = limitSince,
        )
    }

    Column {
        SectionHead("Report", trailing = reportTitle(current))
        TrekSegmented(listOf("Week", "Month"), span, { span = it })
        Spacer(Modifier.height(8.dp))
        TrekSegmented(
            if (span == 0) listOf("This week", "Last week") else listOf("This month", "Last month"),
            back,
            { back = it },
        )
        Spacer(Modifier.height(12.dp))
        if (current.totalMeters <= 0.0) {
            Text(
                "Nothing was measured in this ${if (span == 0) "week" else "month"}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
        } else {
            ReportFigures(current)
            if (current.apps.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                val top = current.apps.first().meters.coerceAtLeast(1.0)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    current.apps.forEach { app ->
                        MeasureRow(
                            label = appName(app.key, customLabels),
                            value = formatDistance(app.meters) + appChange(app.meters, app.previousMeters),
                            fraction = (app.meters / top).toFloat(),
                            color = chartColor(app.key),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportFigures(report: Report) {
    val period = if (report.span == ReportSpan.WEEK) "week" else "month"
    Column {
        LaneLine()
        ReportLine(
            "Total",
            formatDistance(report.totalMeters),
            report.deltaPercent?.let {
                when {
                    it > 0 -> "$it% more than the $period before"
                    it < 0 -> "${-it}% less than the $period before"
                    else -> "The same as the $period before"
                }
            },
        )
        LaneLine()
        ReportLine(
            "Daily average",
            formatDistance(report.dailyAverageMeters),
            "${report.activeDays} of ${report.elapsedDays} days tracked" +
                if (report.running) ", so far" else "",
        )
        report.cleanDays?.let { clean ->
            LaneLine()
            ReportLine(
                "Days within your limit",
                "$clean of ${report.judgedDays}",
                if (report.judgedDays == 0) "No tracked days were judged against the limit" else null,
            )
        }
        report.highest?.let { (day, meters) ->
            LaneLine()
            ReportLine("Highest day", formatDistance(meters), day.format(REPORT_DAY))
        }
        // With one tracked day the lowest is the highest again; saying it twice adds nothing.
        report.lowest?.takeIf { report.activeDays > 1 }?.let { (day, meters) ->
            LaneLine()
            ReportLine("Lowest day", formatDistance(meters), day.format(REPORT_DAY))
        }
        LaneLine()
    }
}

@Composable
private fun ReportLine(label: String, value: String, caption: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Trek.text)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
        }
        Text(value, style = TrekFigure, color = Trek.text, maxLines = 1)
    }
}

/** ", up from 320 m" beside an app's distance, when there is a previous figure to compare. */
private fun appChange(meters: Double, previous: Double): String = when {
    previous <= 0.0 -> ""
    meters > previous -> ", up from ${formatDistance(previous)}"
    meters < previous -> ", down from ${formatDistance(previous)}"
    else -> ""
}
