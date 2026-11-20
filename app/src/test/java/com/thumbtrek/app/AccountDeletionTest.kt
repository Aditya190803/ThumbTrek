package com.thumbtrek.app

import com.thumbtrek.app.social.archiveWeeks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AccountDeletionTest {

    @Test
    fun `archive weeks cover the ISO year boundary without gaps or repeats`() {
        // 2025-12-29 is a Monday that belongs to ISO week 2026-W01.
        val weeks = archiveWeeks(LocalDate.of(2025, 12, 20), LocalDate.of(2026, 1, 12))
        assertEquals(listOf("2025-W51", "2025-W52", "2026-W01", "2026-W02", "2026-W03"), weeks)
    }

    @Test
    fun `the current week is included even mid-week`() {
        // Sunday start, Monday end: two different weeks one day apart.
        val weeks = archiveWeeks(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2))
        assertEquals(listOf("2026-W09", "2026-W10"), weeks)
    }

    @Test
    fun `a 53-week year keeps week 53`() {
        // 2026 has 53 ISO weeks (it starts on a Thursday).
        val weeks = archiveWeeks(LocalDate.of(2026, 12, 21), LocalDate.of(2027, 1, 5))
        assertEquals(listOf("2026-W52", "2026-W53", "2027-W01"), weeks)
    }

    @Test
    fun `a start after the end yields nothing`() {
        assertTrue(archiveWeeks(LocalDate.of(2026, 5, 2), LocalDate.of(2026, 5, 1)).isEmpty())
    }
}
