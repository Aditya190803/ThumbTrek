package com.thumbtrek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.AppSeries
import com.thumbtrek.app.stats.Bucket
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.monthlyBuckets
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.weeklyBuckets
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------

private val RANGES = listOf("7 days", "8 weeks", "6 months")

@Composable
internal fun History(
    state: DashboardViewModel.UiState,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE d MMM") }
    val monthFormat = remember { DateTimeFormatter.ofPattern("MMMM yyyy") }
    var range by rememberSaveable { mutableIntStateOf(0) }
    val today = state.today

    val byDate = remember(state.days) {
        state.days.associate { LocalDate.parse(it.date) to it.pixels }
    }
    val buckets = remember(byDate, range, today) {
        when (range) {
            0 -> dailyBuckets(byDate, 7, today)
            1 -> weeklyBuckets(byDate, 8, today)
            else -> monthlyBuckets(byDate, 6, today)
        }
    }
    val allTime = remember(byDate) { byDate.values.sum() }

    if (!state.loaded) return

    if (state.days.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GUTTER, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            PageHeading("History", "See how your habits are changing.")
            TrekPanel {
                HistoryBars(List(7) { ChartBar(listOf("M", "T", "W", "T", "F", "S", "S")[it], 0f) })
            }
            EmptyState(
                title = "Your story starts today",
                body = "Your daily totals, app trends and achievements will appear here after your first scroll.",
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
    ) {
        item("heading") { PageHeading("History", "See how your habits are changing.") }
        item("total") {
            Column {
                Text("Total distance", style = TrekCaption, color = Trek.textDim)
                Spacer(modifier = Modifier.height(4.dp))
                CountedDistance(
                    pixelsToMeters(allTime, dpi),
                    style = MaterialTheme.typography.displayLarge,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "over ${state.days.size} tracked ${if (state.days.size == 1) "day" else "days"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.textDim,
                )
            }
        }

        item("report") {
            ReportSection(
                today = today,
                limitM = state.limitM,
                limitSince = state.limitSince,
                customLabels = state.customLabels,
            )
        }

        item("range") {
            // Latest period selected up front, so the detail below answers "how far was
            // that bar" before the first tap. Reset per range; tapping the active bar
            // clears back to an unselected chart.
            var selected by rememberSaveable(range) { mutableIntStateOf(buckets.lastIndex) }
            val haptics = LocalHapticFeedback.current
            TrekPanel {
                TrekSegmented(RANGES, range, onSelect = { range = it })
                Spacer(modifier = Modifier.height(18.dp))
                HistoryBars(
                    bars = buckets.map {
                        ChartBar(it.label, pixelsToMeters(it.pixels, dpi).toFloat())
                    },
                    highlight = buckets.lastIndex,
                    selected = selected,
                    onSelect = { tapped ->
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        selected = if (tapped == selected) -1 else tapped
                    },
                    // The limit is per day, so it only means something on the day range.
                    guide = state.limitM.takeIf { range == 0 },
                )
                if (range == 0 && state.limitM != null && buckets.any { it.pixels > 0 }) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LimitKey()
                }
                Spacer(modifier = Modifier.height(14.dp))
                buckets.getOrNull(selected)?.let { bucket ->
                    PeriodDetail(
                        bucket = bucket,
                        range = range,
                        dpi = dpi,
                        trends = state.appTrends,
                        customLabels = state.customLabels,
                        dateFormat = dateFormat,
                        monthFormat = monthFormat,
                    )
                }
            }
        }

        if (state.limitM != null) item("cleanMonth") {
            var calDay by rememberSaveable { mutableStateOf<String?>(null) }
            // Months back from the current one. Bounded by the first tracked month, so
            // paging back never lands on a calendar that can only ever be empty.
            var monthsBack by rememberSaveable { mutableIntStateOf(0) }
            val thisMonth = YearMonth.from(today)
            val firstMonth = remember(byDate) {
                byDate.keys.minOrNull()?.let { YearMonth.from(it) } ?: thisMonth
            }
            val maxBack = (thisMonth.year - firstMonth.year) * 12 +
                (thisMonth.monthValue - firstMonth.monthValue)
            val shownMonth = thisMonth.minusMonths(monthsBack.coerceIn(0, maxBack).toLong())
            val (cleanDays, trackedDays) = remember(byDate, shownMonth, state.limitM, today) {
                val days = (1..shownMonth.lengthOfMonth()).map { shownMonth.atDay(it) }
                    .filter { !it.isAfter(today) && byDate[it] != null }
                    .filter { date -> state.limitSince?.let { !date.isBefore(it) } ?: true }
                days.count { pixelsToMeters(byDate.getValue(it), dpi) <= state.limitM } to days.size
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Days in balance", style = MaterialTheme.typography.titleMedium, color = Trek.text)
                        Text(
                            if (trackedDays == 0) monthFormat.format(shownMonth)
                            else "${monthFormat.format(shownMonth)}: $cleanDays of $trackedDays within limit",
                            style = MaterialTheme.typography.bodySmall,
                            color = Trek.textDim,
                        )
                    }
                    IconAction(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        label = "Previous month",
                        enabled = monthsBack < maxBack,
                        onClick = { monthsBack++; calDay = null },
                    )
                    IconAction(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        label = "Next month",
                        enabled = monthsBack > 0,
                        onClick = { monthsBack--; calDay = null },
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                CleanMonthGrid(
                    month = shownMonth,
                    today = today,
                    judgeFrom = state.limitSince,
                    byDate = byDate,
                    dpi = dpi,
                    limitM = state.limitM,
                    selectedDay = calDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                    onSelect = { date ->
                        calDay = date?.toString()
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CalendarKey(Trek.good, "Clean")
                    CalendarKey(Trek.danger, "Over")
                    CalendarKey(Trek.line, "No data")
                    if (state.limitSince != null && byDate.keys.any { it.isBefore(state.limitSince) }) {
                        CalendarKey(Trek.info, "Before limit")
                    }
                }
                calDay?.let { key ->
                    val px = byDate[LocalDate.parse(key)] ?: 0L
                    Spacer(modifier = Modifier.height(14.dp))
                    PeriodDetail(
                        bucket = Bucket(
                            key,
                            LocalDate.parse(key).format(dateFormat),
                            px,
                        ),
                        range = 0,
                        dpi = dpi,
                        trends = state.appTrends,
                        customLabels = state.customLabels,
                        dateFormat = dateFormat,
                        monthFormat = monthFormat,
                    )
                }
            }
        }

        item("records") {
            Column {
                SectionHead("Records")
                val record = state.record
                if (record != null) {
                    LedgerLine(
                        label = "Longest single day",
                        value = formatDistance(pixelsToMeters(record.second, dpi)),
                        caption = record.first.format(dateFormat),
                        accent = Trek.warn,
                    )
                }
                val bestWeek = remember(byDate, today) {
                    weeklyBuckets(byDate, 52, today).maxByOrNull { it.pixels }
                }
                if (bestWeek != null && bestWeek.pixels > 0) {
                    LedgerLine(
                        label = "Best week",
                        value = formatDistance(pixelsToMeters(bestWeek.pixels, dpi)),
                        caption = "week of ${bestWeek.label}",
                        accent = Trek.warn,
                    )
                }
                // Average over the days since tracking began, capped at a week, so a
                // two-day-old install isn't divided by seven empty days.
                val average = remember(byDate, today) {
                    val first = byDate.keys.minOrNull() ?: today
                    val span = (java.time.temporal.ChronoUnit.DAYS.between(first, today) + 1)
                        .coerceIn(1, 7).toInt()
                    dailyBuckets(byDate, span, today).sumOf { it.pixels } / span to span
                }
                if (average.first > 0) {
                    LedgerLine(
                        label = "Daily average",
                        value = formatDistance(pixelsToMeters(average.first, dpi)),
                        caption = if (average.second == 7) "over the last 7 days"
                            else "over your first ${average.second} ${if (average.second == 1) "day" else "days"}",
                        accent = Trek.text,
                    )
                }
                LedgerLine(
                    label = "Current streak",
                    value = if (state.streak > 0) "${state.streak} ${if (state.streak == 1) "day" else "days"}" else "None",
                    caption = if (state.streak > 0) "days in a row with tracked scrolling" else "scroll today to start one",
                    accent = if (state.streak > 0) Trek.warn else Trek.textFaint,
                )
                if (state.limitM != null) {
                    LedgerLine(
                        label = "Within your limit",
                        value = "${state.cleanStreak} ${if (state.cleanStreak == 1) "day" else "days"}",
                        caption = if (state.cleanStreak > 0) "in a row, including today"
                            else "stay under ${formatDistance(state.limitM.toDouble())} today to start a run",
                        accent = if (state.cleanStreak > 0) Trek.good else Trek.textFaint,
                    )
                }
            }
        }

        if (state.appTrends.isNotEmpty()) {
            item("trends") {
                Column {
                    SectionHead("App trends", trailing = "14 days")
                    TrendLines(
                        series = state.appTrends.map { trend ->
                            ChartSeries(
                                trend.packageName,
                                appName(trend.packageName, state.customLabels),
                                trend.points.map { pixelsToMeters(it.pixels, dpi).toFloat() },
                            )
                        },
                        labels = state.appTrends.first().points.map { it.label },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    state.appTrends.forEach { trend ->
                        ChartLegendRow(
                            color = chartColor(trend.packageName),
                            label = appName(trend.packageName, state.customLabels),
                            value = formatDistance(
                                pixelsToMeters(trend.points.sumOf { it.pixels }, dpi),
                            ),
                            modifier = Modifier.padding(vertical = 5.dp),
                        )
                    }
                }
            }
        }

        if (state.badges.isNotEmpty()) {
            item("checkpoints") {
                CheckpointShelf(state.badges)
            }
        }

        item("logHead") {
            SectionHead("Day log", trailing = "${state.days.size} days")
        }

        val busiestDay = state.days.maxOfOrNull { it.pixels } ?: 1L
        var lastMonth: String? = null
        state.days.forEach { day ->
            val date = LocalDate.parse(day.date)
            val month = monthFormat.format(date)
            if (month != lastMonth) {
                lastMonth = month
                item("m-$month") {
                    Text(
                        month,
                        style = TrekCaption,
                        color = Trek.textFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(day.date) {
                val meters = pixelsToMeters(day.pixels, dpi)
                MeasureRow(
                    label = date.format(dateFormat),
                    value = formatDistance(meters),
                    fraction = if (busiestDay > 0) day.pixels.toFloat() / busiestDay else 0f,
                    color = if (date == today) Trek.accent else Trek.info,
                    labelColor = if (date == today) Trek.text else Trek.textDim,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${date.format(dateFormat)}: ${formatDistance(meters)}"
                    },
                )
            }
        }
    }
}

/**
 * The month as discipline at a glance: each past day is clean (mint wash), over
 * (danger wash), or untracked (hollow). Tapping a day selects it; the caller reads it back
 * through PeriodDetail. Monday-start, matching the app's week convention.
 */
@Composable
private fun CleanMonthGrid(
    month: YearMonth,
    today: LocalDate,
    judgeFrom: LocalDate?,
    byDate: Map<LocalDate, Long>,
    dpi: Int,
    limitM: Float,
    selectedDay: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    val limit = limitM.toDouble()
    val cells = remember(month) {
        val first = month.atDay(1)
        val blanks = List(first.dayOfWeek.value - 1) { null as LocalDate? }
        (blanks + (1..month.lengthOfMonth()).map { month.atDay(it) }).chunked(7)
    }
    val shape = RoundedCornerShape(10.dp)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { head ->
                Text(
                    head,
                    modifier = Modifier.weight(1f),
                    style = TrekCaption,
                    color = Trek.textFaint,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
        cells.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val px = byDate[date]
                        val meters = pixelsToMeters(px ?: 0L, dpi)
                        val future = date.isAfter(today)
                        // Days before the limit existed are shown, never scored.
                        val unjudged = judgeFrom != null && date.isBefore(judgeFrom)
                        val tracked = !future && px != null
                        val over = tracked && !unjudged && meters > limit
                        val fill = when {
                            future || !tracked -> Color.Transparent
                            unjudged -> Trek.infoWash
                            over -> Trek.dangerWash
                            else -> Trek.goodWash
                        }
                        val ink = when {
                            future -> Trek.textFaint
                            over -> Trek.danger
                            tracked -> Trek.text
                            else -> Trek.textDim
                        }
                        val selected = date == selectedDay
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(shape)
                                .background(fill, shape)
                                .border(
                                    1.dp,
                                    when {
                                        selected -> Trek.text
                                        date == today -> Trek.good
                                        tracked || over -> Color.Transparent
                                        else -> Trek.lineSoft
                                    },
                                    shape,
                                )
                                .clickable(enabled = !future, role = Role.Button) {
                                    onSelect(if (selected) null else date)
                                }
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "${date.format(
                                        DateTimeFormatter.ofPattern("EEE d MMM"),
                                    )}: " + when {
                                        future -> "upcoming"
                                        !tracked -> "no data"
                                        unjudged -> "${formatDistance(meters)}, before your limit"
                                        over -> "${formatDistance(meters)}, over the limit"
                                        else -> "${formatDistance(meters)}, clean"
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = ink,
                            )
                        }
                    }
                }
                // Pad short final weeks so every row spans the full width.
                repeat(7 - week.size) {
                    Spacer(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

/** Legend swatch for the calendar: a dot and a word. */
@Composable
private fun CalendarKey(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SeriesDot(color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
    }
}

/**
 * The tapped bar, read back: full period label, total, and — for single days inside the
 * trends window — the per-app split. Weeks, months, and older days read as a total plus
 * the landmark line.
 */
@Composable
private fun PeriodDetail(
    bucket: Bucket,
    range: Int,
    dpi: Int,
    trends: List<AppSeries>,
    customLabels: Map<String, String>,
    dateFormat: DateTimeFormatter,
    monthFormat: DateTimeFormatter,
) {
    val meters = pixelsToMeters(bucket.pixels, dpi)
    val title = when (range) {
        0 -> LocalDate.parse(bucket.key).format(dateFormat)
        1 -> "Week of ${bucket.label}"
        else -> YearMonth.parse(bucket.key).format(monthFormat)
    }
    val noun = when (range) {
        0 -> "day"
        1 -> "week"
        else -> "month"
    }
    val split = remember(bucket.key, trends) {
        if (range != 0) return@remember emptyList()
        // The trends window only reaches back 14 days; older days read as a total so a
        // missing split never prints "nothing measured" over a day that has metres.
        if (trends.none { trend -> trend.points.any { it.key == bucket.key } }) {
            return@remember null
        }
        trends.mapNotNull { trend ->
            trend.points.find { it.key == bucket.key }?.let { trend.packageName to it.pixels }
        }.sortedByDescending { it.second }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = Trek.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDistance(meters),
                style = MaterialTheme.typography.headlineSmall,
                color = if (meters > 0.0) Trek.accent else Trek.textFaint,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (meters > 0.0) comparison(meters) else "Nothing scrolled that $noun.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.textDim,
        )
        if (range == 0 && meters > 0.0 && split != null) {
            Spacer(modifier = Modifier.height(10.dp))
            if (split.isEmpty()) {
                Text(
                    "Nothing measured that day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.textFaint,
                )
            } else {
                split.forEach { (pkg, px) ->
                    ChartLegendRow(
                        color = chartColor(pkg),
                        label = appName(pkg, customLabels),
                        value = formatDistance(pixelsToMeters(px, dpi)),
                        modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }
        }
    }
}

/** A record: label on the left, figure on the right, the "why" underneath in small type. */
@Composable
private fun LedgerLine(label: String, value: String, caption: String, accent: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Trek.text,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent)
        }
        Text(caption, style = MaterialTheme.typography.bodySmall, color = Trek.textFaint)
    }
}

/**
 * Checkpoints as a shelf you walk along, not a wall of locks. Earned badges lead, then the
 * ones closest to falling, each showing how far in it actually is.
 */
@Composable
private fun CheckpointShelf(badges: List<Badge>) {
    val ordered = remember(badges) {
        badges.sortedWith(
            compareByDescending<Badge> { it.earned }.thenByDescending { it.progress },
        )
    }
    val earned = ordered.count { it.earned }
    val next = ordered.firstOrNull { !it.earned }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val focus = ordered.firstOrNull { it.id == picked }

    Column {
        SectionHead("Achievements", trailing = "$earned of ${badges.size} earned")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(ordered, key = { it.id }) { badge ->
                CheckpointTile(
                    badge = badge,
                    selected = badge.id == picked,
                    onClick = { picked = if (picked == badge.id) null else badge.id },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // A tapped tile explains itself; otherwise the shelf points at the nearest goal.
        Text(
            when {
                focus?.earned == true -> "${focus.label}: earned. ${focus.detail}."
                focus != null -> "${focus.label}: ${focus.detail.lowercase()} (${percentCaption(focus.progress)} there)."
                next != null -> "Next up: ${next.detail.lowercase()} (${percentCaption(next.progress)})."
                else -> "Every achievement earned."
            },
            style = MaterialTheme.typography.bodySmall,
            color = Trek.textDim,
        )
    }
}

@Composable
private fun CheckpointTile(badge: Badge, selected: Boolean, onClick: () -> Unit) {
    val tint = if (badge.earned) Trek.accent else Trek.textFaint
    Column(
        modifier = Modifier
            .width(116.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (badge.earned) Trek.accentWash else Trek.surface,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                if (selected) Trek.text else Color.Transparent,
                MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (badge.earned) {
                    "${badge.label}, earned"
                } else {
                    "${badge.label}, ${percentCaption(badge.progress)} complete. ${badge.detail}"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProgressRing(
            progress = if (badge.earned) 1f else badge.progress.toFloat(),
            size = 52.dp,
            color = if (badge.earned) Trek.accent else Trek.info,
        ) {
            // The state in the ring is written, not drawn: "Done" or how far along.
            Text(
                if (badge.earned) "Done" else percentCaption(badge.progress),
                style = MaterialTheme.typography.labelSmall,
                color = if (badge.earned) Trek.text else Trek.textDim,
            )
        }
        Text(
            badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
