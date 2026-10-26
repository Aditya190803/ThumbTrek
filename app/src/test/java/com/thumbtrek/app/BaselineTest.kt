package com.thumbtrek.app

import com.thumbtrek.app.stats.limitStreak
import com.thumbtrek.app.stats.medianMeters
import com.thumbtrek.app.stats.suggestedLimit
import com.thumbtrek.app.stats.trackedDaysSince
import com.thumbtrek.app.stats.typicalDayMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BaselineTest {

    private val today = LocalDate.of(2026, 10, 26)

    private fun week(vararg meters: Double, start: LocalDate = today.minusDays(meters.size.toLong())) =
        meters.mapIndexed { i, m -> start.plusDays(i.toLong()) to m }.toMap()

    @Test
    fun `today and zero days never count toward the baseline`() {
        val days = week(100.0, 0.0, 200.0) + (today to 999.0)
        assertEquals(listOf(200.0, 100.0), trackedDaysSince(days, today.minusDays(30), today))
    }

    @Test
    fun `days before the window start are ignored`() {
        val days = week(500.0, 100.0, 200.0)
        assertEquals(listOf(200.0, 100.0), trackedDaysSince(days, today.minusDays(2), today))
    }

    @Test
    fun `median resists a single binge day`() {
        assertEquals(305.0, medianMeters(listOf(280.0, 300.0, 320.0, 290.0, 310.0, 305.0, 5_000.0))!!, 1e-9)
        assertEquals(15.0, medianMeters(listOf(10.0, 20.0))!!, 1e-9)
        assertNull(medianMeters(emptyList()))
    }

    @Test
    fun `no baseline until seven tracked days exist`() {
        val six = week(100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        assertNull(typicalDayMeters(six, today.minusDays(30), today))
        val seven = week(100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0)
        assertEquals(100.0, typicalDayMeters(seven, today.minusDays(30), today)!!, 1e-9)
    }

    @Test
    fun `tracking-off days stretch the window instead of lowering it`() {
        val gappy = week(300.0, 0.0, 300.0, 0.0, 300.0, 300.0, 0.0, 300.0, 300.0, 300.0)
        assertEquals(300.0, typicalDayMeters(gappy, today.minusDays(30), today)!!, 1e-9)
    }

    @Test
    fun `suggested limit is ten percent under, rounded to a tidy figure`() {
        assertEquals(300f, suggestedLimit(340.0)) // 306 -> 300
        assertEquals(40f, suggestedLimit(45.0)) // 40.5 -> 40
        assertEquals(1_400f, suggestedLimit(1_550.0)) // 1395 -> 1400
    }

    @Test
    fun `suggested limit stays inside the editor bounds`() {
        assertEquals(10f, suggestedLimit(2.0))
        assertEquals(10_000f, suggestedLimit(50_000.0))
    }

    @Test
    fun `a limit set today does not judge the baseline week`() {
        // Over-limit learning days before the limit existed must not break today's run.
        val days = week(900.0, 900.0, 900.0) + (today to 50.0)
        assertEquals(1, limitStreak(days, 100.0, today, firstDay = today))
    }
}
