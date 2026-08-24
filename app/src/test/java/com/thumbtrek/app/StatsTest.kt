package com.thumbtrek.app

import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.social.normalizeFriendCode
import com.thumbtrek.app.stats.AppSeries
import com.thumbtrek.app.stats.Badge
import com.thumbtrek.app.stats.badges
import com.thumbtrek.app.stats.Bucket
import com.thumbtrek.app.stats.appTrends
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.dailyBuckets
import com.thumbtrek.app.stats.dayOverDayDelta
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.monthKey
import com.thumbtrek.app.stats.monthlyBuckets
import com.thumbtrek.app.stats.percentDelta
import com.thumbtrek.app.stats.personalRecord
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisMonth
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import com.thumbtrek.app.stats.weekKey
import com.thumbtrek.app.stats.weekOverWeekDelta
import com.thumbtrek.app.stats.weeklyBuckets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {

    private val today = LocalDate.of(2026, 8, 2) // a Sunday

    @Test
    fun `pixels to meters follows PRD formula`() {
        // 420 px at 420 dpi = exactly 1 inch = 0.0254 m
        assertEquals(0.0254, pixelsToMeters(420, 420), 1e-9)
        assertEquals(0.0, pixelsToMeters(0, 420), 1e-9)
    }

    @Test
    fun `distance formatting`() {
        assertEquals("0 m", formatDistance(0.0))
        assertEquals("340 m", formatDistance(340.2))
        assertEquals("1 km", formatDistance(1000.0))
        assertEquals("1.23 km", formatDistance(1234.0))
    }

    @Test
    fun `streak counts back from today`() {
        val days = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, trekStreak(days, today))
    }

    @Test
    fun `streak falls back to yesterday when today is empty`() {
        val days = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, trekStreak(days, today))
    }

    @Test
    fun `streak breaks on gaps`() {
        assertEquals(1, trekStreak(setOf(today, today.minusDays(3)), today))
        assertEquals(0, trekStreak(setOf(today.minusDays(3)), today))
        assertEquals(0, trekStreak(emptySet(), today))
    }

    @Test
    fun `week starts Monday and excludes future`() {
        val days = mapOf(
            today to 10L, // Sunday
            today.minusDays(1) to 20L, // Saturday
            today.minusDays(6) to 30L, // Monday
            today.minusDays(7) to 99L, // last Sunday — previous week
            today.plusDays(1) to 50L, // future — excluded
        )
        assertEquals(60L, totalThisWeek(days, today))
    }

    @Test
    fun `month totals stay within the month`() {
        val days = mapOf(
            today to 10L,
            today.minusDays(1) to 20L, // Aug 1
            today.minusDays(2) to 99L, // Jul 31 — out
        )
        assertEquals(30L, totalThisMonth(days, today))
    }

    @Test
    fun `personal record picks the biggest day`() {
        val best = today.minusDays(2)
        val record = personalRecord(mapOf(today to 5L, best to 50L, today.minusDays(1) to 20L))
        assertEquals(best to 50L, record)
        assertEquals(null, personalRecord(emptyMap()))
    }

    @Test
    fun `weekKey matches Monday-start ISO weeks`() {
        // Same week: Monday Jul 27 through Sunday Aug 2
        assertEquals(weekKey(LocalDate.of(2026, 7, 27)), weekKey(LocalDate.of(2026, 8, 2)))
        // Sunday vs the following Monday: different weeks
        assertTrue(weekKey(LocalDate.of(2026, 8, 2)) != weekKey(LocalDate.of(2026, 8, 3)))
        // Format
        assertTrue(weekKey(today).matches(Regex("\\d{4}-W\\d{2}")))
    }

    @Test
    fun `comparisons scale with distance`() {
        assertEquals("No trek yet — go scroll something.", comparison(0.0))
        assertTrue(comparison(0.25).contains("banana"))
        assertTrue(comparison(12.4).contains("school bus"))
        assertTrue(comparison(1000.0).contains("Burj Khalifa"))
        assertTrue(comparison(50000.0).contains("marathon"))
    }

    @Test
    fun `daily buckets zero-fill the window and drop older days`() {
        val days = mapOf(
            today to 10L, // Aug 2
            today.minusDays(2) to 30L, // Jul 31
            today.minusDays(9) to 99L, // outside the window
        )
        assertEquals(
            listOf(
                Bucket("2026-07-31", "Jul 31", 30L),
                Bucket("2026-08-01", "Aug 1", 0L),
                Bucket("2026-08-02", "Aug 2", 10L),
            ),
            dailyBuckets(days, 3, today),
        )
    }

    @Test
    fun `daily buckets survive empty and degenerate windows`() {
        assertEquals(emptyList<Bucket>(), dailyBuckets(emptyMap(), 0, today))
        assertEquals(emptyList<Bucket>(), dailyBuckets(mapOf(today to 10L), -1, today))
        assertEquals(listOf(Bucket("2026-08-02", "Aug 2", 0L)), dailyBuckets(emptyMap(), 1, today))
    }

    @Test
    fun `weekly buckets group Monday through Sunday`() {
        val days = mapOf(
            today to 10L, // Sun Aug 2 — this week
            today.minusDays(6) to 20L, // Mon Jul 27 — this week
            today.minusDays(7) to 5L, // Sun Jul 26 — previous week
            today.minusDays(21) to 99L, // Sun Jul 12 — outside the window
        )
        assertEquals(
            listOf(
                Bucket("2026-W29", "Jul 13", 0L),
                Bucket("2026-W30", "Jul 20", 5L),
                Bucket("2026-W31", "Jul 27", 30L),
            ),
            weeklyBuckets(days, 3, today),
        )
    }

    @Test
    fun `weekly buckets roll over on the ISO week boundary`() {
        val sunday = LocalDate.of(2026, 8, 2)
        val days = mapOf(sunday to 7L)
        assertEquals(Bucket("2026-W31", "Jul 27", 7L), weeklyBuckets(days, 1, sunday).single())
        assertEquals(
            Bucket("2026-W32", "Aug 3", 0L),
            weeklyBuckets(days, 1, sunday.plusDays(1)).single(),
        )
        assertEquals(emptyList<Bucket>(), weeklyBuckets(days, 0, sunday))
    }

    @Test
    fun `monthly buckets cross the year boundary`() {
        val january = LocalDate.of(2026, 1, 15)
        val days = mapOf(
            LocalDate.of(2025, 10, 31) to 99L, // outside the window
            LocalDate.of(2025, 12, 31) to 40L,
            LocalDate.of(2026, 1, 1) to 2L,
            january to 3L,
        )
        assertEquals(
            listOf(
                Bucket("2025-11", "Nov", 0L),
                Bucket("2025-12", "Dec", 40L),
                Bucket("2026-01", "Jan", 5L),
            ),
            monthlyBuckets(days, 3, january),
        )
        assertEquals(emptyList<Bucket>(), monthlyBuckets(days, 0, january))
    }

    @Test
    fun `app trends zero-fill days and rank the busiest app first`() {
        val rows = listOf(
            DailyScroll("com.instagram.android", "2026-08-02", 10L),
            DailyScroll("com.instagram.android", "2026-07-31", 5L),
            DailyScroll("com.google.android.youtube", "2026-08-01", 50L),
            DailyScroll("com.google.android.youtube", "2026-07-01", 999L), // outside the window
            DailyScroll("com.reddit.frontpage", "2026-07-01", 7L), // never inside the window
        )
        val trends = appTrends(rows, 3, today)
        assertEquals(
            listOf("com.google.android.youtube", "com.instagram.android"),
            trends.map { it.packageName },
        )
        assertEquals(listOf(0L, 50L, 0L), trends[0].points.map { it.pixels })
        assertEquals(listOf(5L, 0L, 10L), trends[1].points.map { it.pixels })
        assertEquals(
            listOf("2026-07-31", "2026-08-01", "2026-08-02"),
            trends[1].points.map { it.key },
        )
        assertEquals(listOf("Jul 31", "Aug 1", "Aug 2"), trends[1].points.map { it.label })
    }

    @Test
    fun `app trends keep zero-pixel days and tie-break by package`() {
        val rows = listOf(
            DailyScroll("com.twitter.android", "2026-08-02", 0L),
            DailyScroll("com.instagram.android", "2026-08-02", 0L),
        )
        val trends = appTrends(rows, 2, today)
        assertEquals(
            listOf("com.instagram.android", "com.twitter.android"),
            trends.map { it.packageName },
        )
        assertEquals(listOf(0L, 0L), trends[0].points.map { it.pixels })
    }

    @Test
    fun `app trends survive empty input`() {
        assertEquals(emptyList<AppSeries>(), appTrends(emptyList(), 7, today))
        assertEquals(
            emptyList<AppSeries>(),
            appTrends(listOf(DailyScroll("com.instagram.android", "2026-08-02", 10L)), 0, today),
        )
    }

    @Test
    fun `percent delta needs a baseline`() {
        assertEquals(13, percentDelta(340, 300))
        assertEquals(-50, percentDelta(150, 300))
        assertEquals(-100, percentDelta(0, 300))
        assertNull(percentDelta(500, 0))
        assertNull(percentDelta(0, 0))
    }

    @Test
    fun `day over day compares today with yesterday`() {
        assertEquals(13, dayOverDayDelta(mapOf(today to 340L, today.minusDays(1) to 300L), today))
        assertEquals(-100, dayOverDayDelta(mapOf(today.minusDays(1) to 300L), today))
        assertNull(dayOverDayDelta(mapOf(today to 340L), today))
        assertNull(dayOverDayDelta(emptyMap(), today))
    }

    @Test
    fun `week over week compares the same stretch of last week`() {
        val wednesday = LocalDate.of(2026, 7, 29)
        val days = mapOf(
            LocalDate.of(2026, 7, 27) to 10L, // Mon, this week
            wednesday to 10L, // Wed, this week
            LocalDate.of(2026, 7, 30) to 99L, // Thu, still ahead — excluded
            LocalDate.of(2026, 7, 22) to 10L, // Wed, last week
            LocalDate.of(2026, 7, 26) to 99L, // Sun, last week but past the mirrored cutoff
        )
        assertEquals(100, weekOverWeekDelta(days, wednesday))
        assertNull(weekOverWeekDelta(mapOf(wednesday to 10L), wednesday))
    }

    @Test
    fun `badges earn at their milestones`() {
        val all = badges(totalMeters = 1_000_000.0, bestDayMeters = 9_000.0, streak = 365)
        assertTrue(all.all { it.earned })
        // Every badge reports which target it tracks.
        assertEquals(all.size, badges(0.0, 0.0, 0).size)
        assertTrue(badges(0.0, 0.0, 0).none { it.earned })
    }

    @Test
    fun `badge progress clamps to one`() {
        val halfWay = badges(totalMeters = 1_368.5, bestDayMeters = 0.0, streak = 0) // half a bridge
            .first { it.id == "bridge" }
        assertEquals(0.5, halfWay.progress, 1e-9)
        assertFalse(halfWay.earned)
        val over = badges(totalMeters = 500_000.0, bestDayMeters = 0.0, streak = 0)
            .first { it.id == "bridge" }
        assertEquals(1.0, over.progress, 1e-9)
        assertTrue(over.earned)
    }

    @Test
    fun `monthKey resets monthly like weekKey does weekly`() {
        assertEquals("2026-07", monthKey(LocalDate.of(2026, 7, 31)))
        assertEquals("2026-08", monthKey(today))
        assertTrue(monthKey(LocalDate.of(2026, 7, 31)) != monthKey(LocalDate.of(2026, 8, 1)))
    }

    @Test
    fun `friend codes normalize what people paste`() {
        assertEquals("ABCD1234", normalizeFriendCode("abcd-1234"))
        assertEquals("ABCD1234", normalizeFriendCode(" https://thumbtrek.app/i/abcd1234 "))
        // Crockford disambiguation: I/L→1, O→0, U→V
        assertEquals("101VABCD", normalizeFriendCode("IOLUABCD"))
        assertEquals(8, normalizeFriendCode("waytoolongcode").length)
    }

    @Test
    fun `custom app prefs round-trip through their encoded form`() {
        val apps = mapOf(
            "com.example.reader" to "Reader",
            "com.example.news" to "Daily News",
        )
        assertEquals(apps, Prefs.decodeCustomApps(Prefs.encodeCustomApps(apps)))
        assertEquals(emptyMap<String, String>(), Prefs.decodeCustomApps(null))
        assertEquals(emptyMap<String, String>(), Prefs.decodeCustomApps("\n\nbroken|\n|x"))
    }

    @Test
    fun `calibration prefs round-trip through their encoded form`() {
        val factors = mapOf(
            "com.instagram.android" to 1.25f,
            "com.reddit.frontpage" to 0.75f,
        )
        assertEquals(factors, Prefs.decodeCalibration(Prefs.encodeCalibration(factors)))
        assertEquals(emptyMap<String, Float>(), Prefs.decodeCalibration(null))
        // Malformed entries are dropped, not crashed on.
        assertEquals(emptyMap<String, Float>(), Prefs.decodeCalibration("pkg:notanumber,pkg2:"))
    }
}
