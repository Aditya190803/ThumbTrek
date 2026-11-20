package com.thumbtrek.app

import com.thumbtrek.app.stats.OvalState
import com.thumbtrek.app.stats.lapScale
import com.thumbtrek.app.stats.lapsText
import com.thumbtrek.app.stats.ovalDistance
import com.thumbtrek.app.stats.ovalModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OvalTest {
    private val apps = listOf("Instagram" to 640.0, "YouTube" to 470.0, "X" to 250.0, "Reddit" to 120.0)

    @Test
    fun withALimitOneLapIsTheLimit() {
        val model = ovalModel(1480.0, apps, 2000.0)
        assertEquals(2000.0, model.scaleMeters, 0.0)
        assertEquals(OvalState.UNDER, model.state)
        assertEquals("Under the limit", model.stateText)
        assertEquals("520 m left of 2.00 km", model.caption)
        assertEquals(0.74f, model.lanes[0].fraction, 0.0001f)
        assertEquals(0.32f, model.lanes[1].fraction, 0.0001f)
        assertEquals(listOf(1, 2, 3, 4, 5), model.lanes.map { it.lane })
        assertEquals(listOf("Total", "Instagram", "YouTube", "X", "Reddit"), model.lanes.map { it.name })
    }

    @Test
    fun withNoLimitOneLapIsTheNextWhole400Metres() {
        assertEquals(400.0, lapScale(0.0, null), 0.0)
        assertEquals(400.0, lapScale(399.0, null), 0.0)
        assertEquals(400.0, lapScale(400.0, null), 0.0)
        assertEquals(800.0, lapScale(401.0, null), 0.0)
        assertEquals(2000.0, lapScale(1840.0, null), 0.0)

        val model = ovalModel(1840.0, apps)
        assertEquals(OvalState.OPEN, model.state)
        assertNull(model.stateText)
        assertEquals("4.6 laps, no limit", model.caption)
        assertEquals(0.92f, model.lanes[0].fraction, 0.0001f)
    }

    @Test
    fun from80PercentOfTheLimitTheDayIsNear() {
        assertEquals(OvalState.UNDER, ovalModel(1599.0, limitMeters = 2000.0).state)
        val near = ovalModel(1600.0, limitMeters = 2000.0)
        assertEquals(OvalState.NEAR, near.state)
        assertEquals("Close to the limit", near.stateText)
        assertEquals("400 m left of 2.00 km", near.caption)
        // Exactly on the limit is still inside it.
        assertEquals(OvalState.NEAR, ovalModel(2000.0, limitMeters = 2000.0).state)
    }

    @Test
    fun overTheLimitTheTotalLaneIsAFullLap() {
        val model = ovalModel(2240.0, apps, 2000.0)
        assertEquals(OvalState.OVER, model.state)
        assertEquals("Over the limit", model.stateText)
        assertEquals("240 m over 2.00 km", model.caption)
        assertEquals(1f, model.lanes[0].fraction, 0f)
    }

    @Test
    fun moreThanFourAppsSumTheRestIntoOther() {
        val many = apps + listOf("Threads" to 60.0, "Pinterest" to 30.0)
        val model = ovalModel(1570.0, many, 2000.0)
        assertEquals(5, model.lanes.size)
        assertEquals(listOf("Total", "Instagram", "YouTube", "X", "Other"), model.lanes.map { it.name })
        assertEquals(210.0, model.lanes[4].meters, 0.0001)
        assertTrue(model.lanes[4].isOther)
    }

    @Test
    fun anEmptyDayDrawsOnlyTheTotalLaneWithNoProgress() {
        val model = ovalModel(0.0)
        assertEquals(1, model.lanes.size)
        assertEquals(0f, model.lanes[0].fraction, 0f)
        assertEquals("0.0 laps, no limit", model.caption)
        assertEquals(400.0, model.scaleMeters, 0.0)
    }

    @Test
    fun distancesKeepAFixedShape() {
        assertEquals("0 m", ovalDistance(0.0))
        assertEquals("999 m", ovalDistance(999.4))
        assertEquals("1.00 km", ovalDistance(999.6))
        assertEquals("2.00 km", ovalDistance(2000.0))
        assertEquals("4.6 laps", lapsText(1840.0))
    }

    @Test
    fun appsWithNoDistanceAreDroppedAndTiesBreakOnName() {
        val model = ovalModel(200.0, listOf("B" to 100.0, "A" to 100.0, "Idle" to 0.0))
        assertEquals(listOf("Total", "A", "B"), model.lanes.map { it.name })
    }
}
