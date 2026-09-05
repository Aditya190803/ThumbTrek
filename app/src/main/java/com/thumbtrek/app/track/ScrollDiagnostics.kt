package com.thumbtrek.app.track

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which detection path is actually firing, per app.
 *
 * v0.1.0 shipped a tracking fix that silently did nothing for three of the four feeds and
 * nobody could tell, because a scroll counter that undercounts looks exactly like a quiet
 * week. This exists so the next regression is one glance away instead of one bisect away:
 * every event lands in a counter tagged with the [ScrollPath] that decided it, so
 * "YouTube: 0 events" (we never hear from it), "YouTube: 4 000 NO_MAGNITUDE" (we hear from
 * it and it tells us nothing) and "YouTube: 4 000 PIXEL_DELTA" are three different bugs.
 *
 * Costs on the hot path: one map lookup and two array increments under a lock, no
 * allocation after an app is first seen. Nothing is written to disk and nothing leaves the
 * process — the tallies are per-package counters, never content, and they die with the
 * process. The accessibility service and the UI share one process, so a debug screen can
 * read [snapshot] directly.
 */
object ScrollDiagnostics {

    private const val TAG = "ThumbTrekScroll"
    private const val LOG_INTERVAL_MS = 60_000L

    private val paths = ScrollPath.entries

    private class Tally {
        val events = LongArray(ScrollPath.entries.size)
        val pixels = LongArray(ScrollPath.entries.size)
        var foregroundSightings = 0L
        var lastEventAtMs = 0L
    }

    private val lock = Any()
    private val tallies = LinkedHashMap<String, Tally>()
    private var lastLogAtMs = 0L

    private val _revision = MutableStateFlow(0L)

    /**
     * Bumped whenever a summary is emitted (a few seconds apart at most, and only while
     * someone is scrolling). A debug screen can collect this to know when to re-[snapshot];
     * it is deliberately not bumped per event, which would be a recomposition storm.
     */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Hot path. Called for every scroll event of a tracked app, counted or not. */
    fun recordScroll(pkg: String, path: ScrollPath, pixels: Int, nowMs: Long) {
        synchronized(lock) {
            val tally = tallies.getOrPut(pkg) { Tally() }
            tally.events[path.ordinal]++
            tally.pixels[path.ordinal] += pixels.toLong()
            tally.lastEventAtMs = nowMs
        }
    }

    /**
     * A tracked app came to the foreground. This is the control question — an app that is
     * never seen here is a tracking-permission or package-name problem, not a delta bug.
     */
    fun recordForeground(pkg: String) {
        synchronized(lock) { tallies.getOrPut(pkg) { Tally() }.foregroundSightings++ }
    }

    /** Snapshot for a debug screen, busiest app first. Allocates; never call per event. */
    fun snapshot(): List<PackageDiagnostics> = synchronized(lock) {
        tallies.map { (pkg, tally) ->
            PackageDiagnostics(
                packageName = pkg,
                foregroundSightings = tally.foregroundSightings,
                lastEventAtMs = tally.lastEventAtMs,
                byPath = paths.indices
                    .filter { tally.events[it] > 0 }
                    .associate { paths[it] to PathTally(tally.events[it], tally.pixels[it]) },
            )
        }
    }.sortedByDescending { it.countedPixels }

    fun clear() = synchronized(lock) {
        tallies.clear()
        lastLogAtMs = 0L
    }

    /**
     * One compact line per active app in logcat (`adb logcat -s ThumbTrekScroll`), at most
     * once a minute. Called from the flush coroutine, never from the event callback.
     */
    fun logSummary(nowMs: Long) {
        synchronized(lock) {
            if (nowMs - lastLogAtMs < LOG_INTERVAL_MS) return
            lastLogAtMs = nowMs
        }
        snapshot().forEach { Log.i(TAG, it.summary()) }
        _revision.value = _revision.value + 1
    }
}

data class PathTally(val events: Long, val pixels: Long)

data class PackageDiagnostics(
    val packageName: String,
    val foregroundSightings: Long,
    val lastEventAtMs: Long,
    val byPath: Map<ScrollPath, PathTally>,
) {
    val totalEvents: Long get() = byPath.values.sumOf { it.events }

    val countedPixels: Long
        get() = byPath.entries.sumOf { (path, tally) -> if (path.counted) tally.pixels else 0L }

    /** The path banking the most pixels — the one to sanity-check when a number looks wrong. */
    val dominantPath: ScrollPath?
        get() = byPath.entries.filter { it.key.counted }.maxByOrNull { it.value.pixels }?.key

    /** e.g. `com.twitter.android fg=12 px=184320 PIXEL_DELTA=812/184320 NO_MAGNITUDE=9/0`. */
    fun summary(): String = buildString {
        append(packageName)
        append(" fg=").append(foregroundSightings)
        append(" px=").append(countedPixels)
        byPath.entries.sortedByDescending { it.value.events }.forEach { (path, tally) ->
            append(' ').append(path.name).append('=').append(tally.events).append('/').append(tally.pixels)
        }
    }
}
