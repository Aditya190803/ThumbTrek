package com.thumbtrek.app

import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.personalRecord
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.totalThisMonth
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.trekStreak
import com.thumbtrek.app.stats.weekKey
import org.junit.Assert.assertEquals
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
        assertTrue(comparison(12.4).contains("banana"))
        assertTrue(comparison(1000.0).contains("Burj Khalifa"))
        assertTrue(comparison(50000.0).contains("marathon"))
    }
}
