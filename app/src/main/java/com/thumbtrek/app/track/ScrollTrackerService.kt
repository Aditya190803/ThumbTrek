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
import kotlin.math.abs

/**
 * Listens for TYPE_VIEW_SCROLLED events and accumulates scroll pixels into Room,
 * batched every few seconds. Events are not package-filtered by the system (custom
 * tracked apps exist), so each event is checked against Prefs.trackedApps first —
 * an in-memory read, cheap enough to sit on the hot path.
 */
class ScrollTrackerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao by lazy { ScrollDatabase.get(this).dao() }
    private val prefs by lazy { Prefs.get(this) }

    private val pending = mutableMapOf<String, Long>() // package -> px since last flush
    private var flushJob: Job? = null

    // Diffed scrollY per view class — the fallback path below, always kept warm.
    private val lastScrollY = mutableMapOf<String, Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = event.packageName?.toString() ?: return
        // PRD §11 Q1: tracking is opt-out per app. In-memory read, no disk hit per event.
        if (!prefs.isTracked(pkg)) return

        // Jetpack Compose's scrollable semantics report position (scrollY/maxScrollY) on
        // TYPE_VIEW_SCROLLED but leave scrollDeltaY unset, unlike classic Views/RecyclerView.
        // X and YouTube lean on Compose for their feeds now, so scrollDeltaY alone silently
        // dropped every one of their events. Diff scrollY ourselves whenever delta is missing —
        // this is also the API 26/27 path, since those OS versions never populate scrollDeltaY.
        val fromDelta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            abs(event.scrollDeltaY)
        } else {
            0
        }
        val key = "$pkg:${event.className}"
        val previousScrollY = lastScrollY.put(key, event.scrollY)
        val delta = if (fromDelta > 0) {
            fromDelta
        } else if (previousScrollY != null && previousScrollY >= 0 && event.scrollY >= 0) {
            abs(event.scrollY - previousScrollY)
        } else {
            0
        }
        // ponytail: cap single-event deltas; recycled/WebView feeds occasionally report garbage jumps.
        if (delta <= 0 || delta > MAX_DELTA_PX) return

        synchronized(pending) {
            pending[pkg] = (pending[pkg] ?: 0L) + delta
        }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    private suspend fun flush() {
        val batch = synchronized(pending) {
            if (pending.isEmpty()) return
            pending.toMap().also { pending.clear() }
        }
        val today = LocalDate.now().toString()
        batch.forEach { (pkg, px) ->
            // Calibration is applied here, once, so every downstream number — dashboard,
            // history, boards, widget, export — sees the same adjusted pixels. Only
            // future scrolls are affected when the user moves a slider.
            val adjusted = (px * prefs.calibrationFactor(pkg)).toLong()
            dao.accumulate(pkg, today, adjusted)
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
        private const val MAX_DELTA_PX = 50_000

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
