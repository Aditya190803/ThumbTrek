package com.thumbtrek.app

import com.thumbtrek.app.data.initialDailyLimit
import com.thumbtrek.app.data.parseDailyLimit
import org.junit.Assert.*
import org.junit.Test

class SetupPolicyTest {
    @Test fun freshInstallDoesNotReceiveADefaultLimit() {
        assertNull(initialDailyLimit(null, legacyInstall = false))
    }

    @Test fun upgradePreservesBothExplicitAndLegacyLimits() {
        assertEquals(250f, initialDailyLimit(250f, legacyInstall = true))
        assertEquals(100f, initialDailyLimit(null, legacyInstall = true))
    }

    @Test fun baselineModeStaysWithoutALimitOnRelaunch() {
        assertNull(initialDailyLimit(null, legacyInstall = false))
        assertEquals(75f, initialDailyLimit(75f, legacyInstall = false))
    }

    @Test fun inputRequiresAFiniteDistanceWithinBounds() {
        listOf("", "abc", "NaN", "Infinity", "-1", "0", "9", "10001").forEach {
            assertNull("Rejected: $it", parseDailyLimit(it))
        }
        assertEquals(10f, parseDailyLimit("10"))
        assertEquals(10000f, parseDailyLimit("10000"))
        assertEquals(250f, parseDailyLimit(" 250 "))
    }
}
