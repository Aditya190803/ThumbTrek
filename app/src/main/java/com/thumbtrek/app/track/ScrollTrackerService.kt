package com.thumbtrek.app.track

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

/**
 * Listens for TYPE_VIEW_SCROLLED events and accumulates scroll pixels into Room, batched
 * every few seconds. Events are not package-filtered by the system (custom tracked apps
 * exist), so each event is checked against Prefs.trackedApps first — an in-memory read,
 * cheap enough to sit on the hot path.
 *
 * All the "how many pixels is this event worth" reasoning lives in [resolveScrollDelta],
 * which is pure and unit-tested; this class only decides *which scroller* an event belongs
 * to, so the position-diff paths compare like with like. See ScrollDelta.kt for why an
 * event from Instagram and an event from a Compose feed carry completely different fields.
 */
class ScrollTrackerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao by lazy { ScrollDatabase.get(this).dao() }
    private val prefs by lazy { Prefs.get(this) }

    private val pending = mutableMapOf<String, Long>() // package -> px since last flush
    private var flushJob: Job? = null

    /**
     * Last `scrollY` per scroller, for the position-diff paths.
     *
     * Keyed by package + window + view class, not just package + class: a position from
     * another window is a different feed, and diffing across the two produces exactly the
     * kind of garbage jump that then gets thrown away as OUT_OF_RANGE. Cleared whenever the
     * foreground app changes and hard-capped, so it can never grow into a leak or serve a
     * position that has been stale since yesterday.
     */
    private val lastScrollY = mutableMapOf<String, Int>()
    private var foregroundPkg: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onForegroundChanged(pkg)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> onScrolled(pkg, event)
            else -> Unit
        }
    }

    /**
     * Window changes are cheap and rare, and they answer the question the diagnostics exist
     * for: did we ever hear from this app at all? An app that never shows up here is a
     * tracking-toggle or package-name problem, not a pixel-counting one.
     */
    private fun onForegroundChanged(pkg: String) {
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        // Positions belonging to the app we just left can only mislead us now.
        lastScrollY.clear()
        // PRD §11 Q1: only tracked apps are ever named, even in in-memory diagnostics.
        if (prefs.isTracked(pkg)) ScrollDiagnostics.recordForeground(pkg)
    }

    private fun onScrolled(pkg: String, event: AccessibilityEvent) {
        // PRD §11 Q1: tracking is opt-out per app. In-memory read, no disk hit per event.
        if (!prefs.isTracked(pkg)) return

        val sample = ScrollSample(
            // getScrollDeltaX/Y arrived in API 28; before that the field does not exist and
            // every app is on the position paths, same as Compose.
            scrollDeltaX = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaX
            } else {
                UNDEFINED_SCROLL
            },
            scrollDeltaY = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                event.scrollDeltaY
            } else {
                UNDEFINED_SCROLL
            },
            scrollX = event.scrollX,
            scrollY = event.scrollY,
            maxScrollX = event.maxScrollX,
            maxScrollY = event.maxScrollY,
        )

        val key = "$pkg|${event.windowId}|${event.className}"
        if (lastScrollY.size >= MAX_TRACKED_SCROLLERS) lastScrollY.clear()
        val previousScrollY = lastScrollY.put(key, sample.scrollY)

        val delta = resolveScrollDelta(sample, previousScrollY, resources.displayMetrics.heightPixels)
        ScrollDiagnostics.recordScroll(pkg, delta.path, delta.pixels, System.currentTimeMillis())
        if (delta.pixels > 0) {
            synchronized(pending) {
                pending[pkg] = (pending[pkg] ?: 0L) + delta.pixels
            }
        }
        // Scheduled even when the event banked nothing: the same tick pushes the diagnostics
        // summary out, and an app that never banks a pixel is precisely the one whose
        // summary needs to reach logcat. flush() is a no-op when there is nothing pending.
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
            // Off the event callback and already throttled to once a minute inside.
            ScrollDiagnostics.logSummary(System.currentTimeMillis())
        }
    }

    private suspend fun flush() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toMap().also { pending.clear() }
        }
        val today = LocalDate.now().toString()
        batch.forEach { (pkg, px) ->
            // Raw pixels only — no user multiplier. Leaderboards and local stats stay
            // comparable; a Settings slider would have been an honor-system cheat dial.
            dao.accumulate(pkg, today, px)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runBlocking { flush() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FLUSH_DELAY_MS = 3_000L

        /** A phone shows a handful of scrollers at once; more than this means stale keys. */
        private const val MAX_TRACKED_SCROLLERS = 32

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val full = ComponentName(context, ScrollTrackerService::class.java).flattenToString()
            return enabled.split(':').any {
                it.equals(full, ignoreCase = true) || it.endsWith(".track.ScrollTrackerService")
            }
        }
    }
}
