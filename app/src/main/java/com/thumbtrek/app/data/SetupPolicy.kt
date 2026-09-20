package com.thumbtrek.app.data

import com.thumbtrek.app.stats.DEFAULT_DAILY_LIMIT_M
import com.thumbtrek.app.stats.MIN_DAILY_LIMIT_M
import com.thumbtrek.app.stats.MAX_DAILY_LIMIT_M

/** Only pre-onboarding installs inherit the old default; fresh installs have no cap. */
internal fun initialDailyLimit(stored: Float?, legacyInstall: Boolean): Float? =
    stored ?: if (legacyInstall) DEFAULT_DAILY_LIMIT_M.toFloat() else null

fun parseDailyLimit(input: String): Float? = input.trim().toFloatOrNull()?.takeIf {
    it.isFinite() && it >= MIN_DAILY_LIMIT_M && it <= MAX_DAILY_LIMIT_M
}
