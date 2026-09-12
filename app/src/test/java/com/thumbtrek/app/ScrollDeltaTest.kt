package com.thumbtrek.app

import com.thumbtrek.app.track.MAX_DELTA_PX
import com.thumbtrek.app.track.ScrollDiagnostics
import com.thumbtrek.app.track.ScrollPath
import com.thumbtrek.app.track.ScrollSample
import com.thumbtrek.app.track.UNDEFINED_SCROLL
import com.thumbtrek.app.track.resolveScrollDelta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression suite for the bug that made ThumbTrek an Instagram-only tracker: every
 * case here is a shape of TYPE_VIEW_SCROLLED that a real feed actually emits.
 */
class ScrollDeltaTest {

    private val screen = 2400 // a tall phone, in px

    private fun resolve(sample: ScrollSample, previous: Int? = null) =
        resolveScrollDelta(sample, previous, screen)

    // --- classic Views: RecyclerView, ScrollView, WebView ---

    @Test
    fun `a real pixel delta is taken at face value`() {
        val delta = resolve(ScrollSample(scrollDeltaY = 480))
        assertEquals(480, delta.pixels)
        assertEquals(ScrollPath.PIXEL_DELTA, delta.path)
    }

    @Test
    fun `scrolling back up counts the same as scrolling down`() {
        assertEquals(480, resolve(ScrollSample(scrollDeltaY = -480)).pixels)
    }

    @Test
    fun `a real delta beats a position that also happens to be set`() {
        // RecyclerView leaves scrollY at 0 forever; the delta is the only truth it has.
        val delta = resolve(ScrollSample(scrollDeltaY = 300, scrollY = 0), previous = 900)
        assertEquals(300, delta.pixels)
        assertEquals(ScrollPath.PIXEL_DELTA, delta.path)
    }

    // --- the v0.1.0 bug ---

    @Test
    fun `an unset scroll delta is not a one pixel scroll`() {
        // AccessibilityRecord initialises mScrollDeltaY to UNDEFINED = -1, so abs() of it
        // is 1. v0.1.0 banked that single pixel for every Compose event and so never once
        // reached the fallback it had just added. -1 must mean "ask the position instead".
        val compose = ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 4200, maxScrollY = 9000)
        val delta = resolve(compose, previous = 3900)
        assertEquals(300, delta.pixels)
        assertEquals(ScrollPath.PIXEL_POSITION, delta.path)
    }

    @Test
    fun `an unset delta with no position at all banks nothing`() {
        val delta = resolve(ScrollSample(scrollDeltaY = UNDEFINED_SCROLL))
        assertEquals(0, delta.pixels)
        assertEquals(ScrollPath.NO_MAGNITUDE, delta.path)
    }

    // --- Compose ---

    @Test
    fun `a Compose verticalScroll reports honest pixels in its position`() {
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 1200, maxScrollY = 8000),
            previous = 800,
        )
        assertEquals(400, delta.pixels)
        assertEquals(ScrollPath.PIXEL_POSITION, delta.path)
    }

    @Test
    fun `a lazy feed is spotted by its hundred-unit lookahead`() {
        // estimatedLazyMaxScrollOffset() == value + 100 while more items remain, and the
        // value itself is firstVisibleItemScrollOffset + 500 per item index.
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 1720, maxScrollY = 1820),
            previous = 1300,
        )
        assertEquals(420, delta.pixels)
        assertEquals(ScrollPath.LAZY_POSITION, delta.path)
    }

    @Test
    fun `a page-snapped feed is counted in screens, not in pseudo-units`() {
        // Reels/Shorts: the pseudo-offset advances exactly 500 per page however tall the
        // page is, so the raw diff would undercount a full swipe roughly fivefold.
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 2000, maxScrollY = 2100),
            previous = 1000,
        )
        assertEquals(2 * screen, delta.pixels)
        assertEquals(ScrollPath.PAGED, delta.path)
    }

    @Test
    fun `a lazy feed mid-item is not mistaken for a page swipe`() {
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 2000, maxScrollY = 2100),
            previous = 1740,
        )
        assertEquals(260, delta.pixels)
        assertEquals(ScrollPath.LAZY_POSITION, delta.path)
    }

    // --- things that must stay at zero ---

    @Test
    fun `the first sample of a stream only sets a baseline`() {
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 4200, maxScrollY = 9000),
            previous = null,
        )
        assertEquals(0, delta.pixels)
        assertEquals(ScrollPath.NO_MAGNITUDE, delta.path)
    }

    @Test
    fun `a standing still event banks nothing`() {
        val sample = ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 4200, maxScrollY = 9000)
        assertEquals(ScrollPath.NO_MAGNITUDE, resolve(sample, previous = 4200).path)
    }

    @Test
    fun `sideways scrolling is recognised and not counted`() {
        // A carousel or a tab swipe, both flavours: one that reports a delta and one that
        // reports only a position.
        assertEquals(
            ScrollPath.HORIZONTAL,
            resolve(ScrollSample(scrollDeltaX = 900, scrollDeltaY = 0)).path,
        )
        assertEquals(
            ScrollPath.HORIZONTAL,
            resolve(
                ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollX = 1080, maxScrollX = 4320),
                previous = 0,
            ).path,
        )
    }

    @Test
    fun `a view that moved without saying how far banks nothing`() {
        // Pre-1_1_0 RecyclerView reported both deltas as a literal 0 and no position. It
        // is worth zero pixels, but it must show up in the diagnostics as its own case.
        val delta = resolve(ScrollSample(scrollDeltaX = 0, scrollDeltaY = 0))
        assertEquals(0, delta.pixels)
        assertEquals(ScrollPath.NO_MAGNITUDE, delta.path)
    }

    @Test
    fun `a jump too big for a thumb is dropped, loudly`() {
        val delta = resolve(ScrollSample(scrollDeltaY = MAX_DELTA_PX + 1))
        assertEquals(0, delta.pixels)
        assertEquals(ScrollPath.OUT_OF_RANGE, delta.path)
        // A stale baseline from another feed produces exactly this shape.
        val stale = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 10, maxScrollY = 900_000),
            previous = 800_000,
        )
        assertEquals(ScrollPath.OUT_OF_RANGE, stale.path)
    }

    @Test
    fun `a page estimate needs a screen height`() {
        val delta = resolveScrollDelta(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 2000, maxScrollY = 2100),
            previousScrollY = 1000,
            screenHeightPx = 0,
        )
        assertEquals(1000, delta.pixels)
        assertEquals(ScrollPath.LAZY_POSITION, delta.path)
    }

    @Test
    fun `only the banking paths are marked as counted`() {
        assertTrue(ScrollPath.PIXEL_DELTA.counted)
        assertTrue(ScrollPath.PAGED.counted)
        assertTrue(!ScrollPath.HORIZONTAL.counted)
        assertTrue(!ScrollPath.NO_MAGNITUDE.counted)
        assertTrue(!ScrollPath.OUT_OF_RANGE.counted)
    }

    // --- YouTube: the shapes com.google.android.youtube actually emits ---
    // These pin the contract the deep audit verified, so a future "optimisation" of the
    // priority order can't silently zero YouTube while Instagram keeps working.

    @Test
    fun `youtube home feed RecyclerView deltas count as pixels`() {
        // Classic Views report honest deltas; RecyclerView leaves scrollY at 0 forever.
        val delta = resolve(ScrollSample(scrollDeltaY = 640, scrollY = 0), previous = 0)
        assertEquals(640, delta.pixels)
        assertEquals(ScrollPath.PIXEL_DELTA, delta.path)
    }

    @Test
    fun `youtube Shorts pager swipe counts a full screen, not pseudo-units`() {
        // A Shorts swipe advances the Compose pager pseudo-offset exactly 500 per page
        // however tall the page is; banking the raw 500 would undercount ~5x on a phone.
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 1500, maxScrollY = 1600),
            previous = 1000,
        )
        assertEquals(screen, delta.pixels)
        assertEquals(ScrollPath.PAGED, delta.path)
    }

    @Test
    fun `youtube comments position stream diffs honestly`() {
        // Nested scrollers that only fill scrollY/maxScrollY (Compose verticalScroll,
        // API 26/27) diff like pixels.
        val delta = resolve(
            ScrollSample(scrollDeltaY = UNDEFINED_SCROLL, scrollY = 2600, maxScrollY = 12000),
            previous = 2100,
        )
        assertEquals(500, delta.pixels)
        assertEquals(ScrollPath.PIXEL_POSITION, delta.path)
    }

    // --- diagnostics ---

    @After
    fun resetDiagnostics() = ScrollDiagnostics.clear()

    @Test
    fun `diagnostics tally every path per package and rank by counted pixels`() {
        ScrollDiagnostics.recordForeground("com.twitter.android")
        ScrollDiagnostics.recordScroll("com.twitter.android", ScrollPath.PIXEL_DELTA, 480, 1L)
        ScrollDiagnostics.recordScroll("com.twitter.android", ScrollPath.PIXEL_DELTA, 520, 2L)
        ScrollDiagnostics.recordScroll("com.twitter.android", ScrollPath.NO_MAGNITUDE, 0, 3L)
        ScrollDiagnostics.recordScroll("com.reddit.frontpage", ScrollPath.LAZY_POSITION, 300, 4L)

        val snapshot = ScrollDiagnostics.snapshot()
        assertEquals(listOf("com.twitter.android", "com.reddit.frontpage"), snapshot.map { it.packageName })

        val x = snapshot.first()
        assertEquals(1L, x.foregroundSightings)
        assertEquals(3L, x.totalEvents)
        assertEquals(1000L, x.countedPixels)
        assertEquals(ScrollPath.PIXEL_DELTA, x.dominantPath)
        assertEquals(2L, x.byPath.getValue(ScrollPath.PIXEL_DELTA).events)
        assertEquals(3L, x.lastEventAtMs)
        // The line the owner reads in logcat.
        assertTrue(x.summary().startsWith("com.twitter.android fg=1 px=1000 PIXEL_DELTA=2/1000"))
    }

    @Test
    fun `an app we never hear a scroll from is still visible as foreground-only`() {
        ScrollDiagnostics.recordForeground("com.google.android.youtube")
        val report = ScrollDiagnostics.snapshot().single()
        assertEquals(0L, report.totalEvents)
        assertEquals(0L, report.countedPixels)
        assertEquals(null, report.dominantPath)
    }
}
