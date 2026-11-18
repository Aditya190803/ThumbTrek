package com.thumbtrek.app.stats

// Two additions to the single daily limit: a limit shared with the browser, and a limit
// per app. Pure functions, so the rules are testable without a device (sync-protocol §7).

/**
 * The daily limit as it crosses the network: micrometres, with 0 meaning "no limit", and
 * the wall-clock moment the user last set it. The newest [setAt] wins on every client.
 */
data class SharedLimit(val limitUm: Long, val setAt: Long)

/** What a sync should do about the shared limit. */
sealed interface LimitSync {
    /** This device changed the limit last: write it. */
    data class Push(val limit: SharedLimit) : LimitSync

    /** Another device changed it last: take theirs. */
    data class Adopt(val limit: SharedLimit) : LimitSync

    data object InSync : LimitSync
}

/**
 * Newest edit wins. A tie keeps the server's copy, so two devices that both believe they
 * are current never write back and forth.
 */
fun reconcileLimit(local: SharedLimit, remote: SharedLimit?): LimitSync = when {
    remote == null -> LimitSync.Push(local)
    local.setAt > remote.setAt -> LimitSync.Push(local)
    local.setAt < remote.setAt || local.limitUm != remote.limitUm -> LimitSync.Adopt(remote)
    else -> LimitSync.InSync
}

fun metresToUm(meters: Double): Long = Math.round(meters * 1_000_000.0)

/** One app measured against its own limit today. */
data class AppLimitStatus(
    val key: String,
    val meters: Double,
    val limitMeters: Double,
) {
    val over: Boolean get() = !isCleanDay(meters, limitMeters)

    /** 0 quiet, 1 at 80%, 2 over: the same levels the daily limit nudges at. */
    val level: Int get() = limitNudgeLevel(meters, limitMeters)
}

/** Every app that has a limit, measured against it, furthest over first. */
fun appLimitStatuses(
    todayMeters: Map<String, Double>,
    limits: Map<String, Float>,
): List<AppLimitStatus> = limits
    .map { (key, limit) -> AppLimitStatus(key, todayMeters[key] ?: 0.0, limit.toDouble()) }
    .sortedByDescending { it.meters / it.limitMeters }

/**
 * The app nudges still owed today. [fired] maps an app to the highest level already sent
 * today, so each level speaks once per app per day, the same rule the daily limit follows.
 */
fun appNudgesDue(
    statuses: List<AppLimitStatus>,
    fired: Map<String, Int>,
): List<AppLimitStatus> = statuses.filter { it.level > 0 && it.level > (fired[it.key] ?: 0) }

/** "640 m of 500 m" for a lane row, or just the distance when the app has no limit. */
fun appLimitCaption(meters: Double, limitMeters: Double?): String =
    if (limitMeters == null) ovalDistance(meters)
    else "${ovalDistance(meters)} of ${ovalDistance(limitMeters)}"
