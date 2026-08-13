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
 * Listens for TYPE_VIEW_SCROLLED from the four tracked apps (filtered by the
 * system via android:packageNames in the service config, so we only wake for them)
 * and accumulates scroll pixels into Room, batched every few seconds.
 */
class ScrollTrackerService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dao by lazy { ScrollDatabase.get(this).dao() }
    private val prefs by lazy { Prefs.get(this) }

    private val pending = mutableMapOf<String, Long>() // package -> px since last flush
    private var flushJob: Job? = null

    // API 26–27 fallback: events lack scrollDeltaY, so diff scrollY per view class.
    private val lastScrollY = mutableMapOf<String, Int>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = event.packageName?.toString() ?: return
        // PRD §11 Q1: tracking is opt-out per app. In-memory read, no disk hit per event.
        if (!prefs.isTracked(pkg)) return

        val delta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            abs(event.scrollDeltaY)
        } else {
            val key = "$pkg:${event.className}"
            val last = lastScrollY.put(key, event.scrollY)
            if (last == null) 0 else abs(event.scrollY - last)
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
        batch.forEach { (pkg, px) -> dao.accumulate(pkg, today, px) }
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
