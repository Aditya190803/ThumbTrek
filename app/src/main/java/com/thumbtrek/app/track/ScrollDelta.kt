package com.thumbtrek.app.track

import kotlin.math.abs

/**
 * How many pixels a single TYPE_VIEW_SCROLLED event is worth, and which rule decided it.
 *
 * Deliberately pure, with no Android types on it: the reason v0.1.0's fix shipped broken
 * is that none of this was testable, so a wrong assumption about what AccessibilityEvent
 * actually carries survived review. See ScrollDeltaTest.
 *
 * ## What the platform really puts in a scroll event
 *
 * - `scrollDeltaX/Y` default to **-1** (`AccessibilityRecord.UNDEFINED`), *not* 0, and are
 *   only ever written by `View.postSendViewScrolledAccessibilityEventCallback()` — i.e. by
 *   classic Views. `abs(-1) == 1`, which is why the old code banked exactly one pixel per
 *   event for every app that isn't classic Views, and never reached its own fallback.
 * - Classic Views (RecyclerView, ScrollView, ListView, WebView) do report honest pixel
 *   deltas, accumulated and throttled to one event per 100 ms per view. RecyclerView has
 *   passed real deltas through `onScrollChanged` since 2019 (RecyclerView 1.1.0).
 * - Jetpack Compose's accessibility delegate sends TYPE_VIEW_SCROLLED with **only**
 *   `scrollX/scrollY/maxScrollX/maxScrollY`; it never calls `setScrollDeltaY`. So every
 *   Compose feed lands on the position paths below.
 * - A Compose `Modifier.verticalScroll` reports real pixels in `scrollY`.
 * - A Compose `LazyColumn`/`VerticalPager` **cannot** report pixels — it has never measured
 *   the items above the viewport — so it reports a pseudo-offset:
 *   `scrollY = firstVisibleItemScrollOffset + firstVisibleItemIndex * 500`, with
 *   `maxScrollY = scrollY + 100` while it can still scroll forward. That +100 is the
 *   fingerprint we key off; see [isLazyPseudoOffset].
 */
enum class ScrollPath {
    /** Honest pixels from `scrollDeltaY`. Classic Views. The path we want to be on. */
    PIXEL_DELTA,

    /** Honest pixels, diffed from `scrollY`. Compose `verticalScroll`, and API 26/27. */
    PIXEL_POSITION,

    /** Compose lazy pseudo-units, diffed. Approximate: ~pixels, plus 500 per item boundary. */
    LAZY_POSITION,

    /** A page-snapped lazy feed (Reels/Shorts-style). Counted as whole screen heights. */
    PAGED,

    /** A sideways scroll — a carousel or a tab swipe. Deliberately worth zero. */
    HORIZONTAL,

    /** The event moved nothing we can measure, or it is the first sample of a stream. */
    NO_MAGNITUDE,

    /** A jump too big to be a thumb: a fling-to-top, a feed swap, a stale baseline. */
    OUT_OF_RANGE,
    ;

    /** True for the paths that put pixels in the bank. Used to group the diagnostics. */
    val counted: Boolean
        get() = this == PIXEL_DELTA || this == PIXEL_POSITION ||
            this == LAZY_POSITION || this == PAGED
}

/** The fields of a TYPE_VIEW_SCROLLED event this logic reads, and nothing else. */
data class ScrollSample(
    val scrollDeltaX: Int = UNDEFINED_SCROLL,
    val scrollDeltaY: Int = UNDEFINED_SCROLL,
    val scrollX: Int = 0,
    val scrollY: Int = 0,
    val maxScrollX: Int = 0,
    val maxScrollY: Int = 0,
)

data class ScrollDelta(val pixels: Int, val path: ScrollPath)

/** `AccessibilityRecord.UNDEFINED`: the default of both scroll deltas, and it is -1, not 0. */
const val UNDEFINED_SCROLL = -1

/**
 * A single event worth more than this is not a thumb, it's a jump — a fling-to-top, a feed
 * swap under a reused view, or a baseline left over from another scroller. Dropped rather
 * than clamped, so a bug upstream can't quietly manufacture kilometres.
 */
const val MAX_DELTA_PX = 50_000

/** Compose's pseudo-offset quantum: `estimatedLazyScrollOffset` adds this per item index. */
private const val LAZY_UNITS_PER_ITEM = 500

/** `estimatedLazyMaxScrollOffset` sits exactly this far ahead while more items remain. */
private const val LAZY_MAX_LOOKAHEAD = 100

/**
 * The one decision this service makes, in strict priority order:
 *
 * 1. [ScrollPath.PIXEL_DELTA] — real pixels, when the view bothered to report them.
 * 2. [ScrollPath.PIXEL_POSITION] — diff `scrollY`, when it is a pixel position.
 * 3. [ScrollPath.PAGED] — a page-snapped Compose feed: one item swiped = one screen.
 * 4. [ScrollPath.LAZY_POSITION] — a Compose lazy feed: pseudo-units, close enough to pixels
 *    to be worth counting, and the per-app calibration slider exists for the rest.
 * 5. Nothing. [ScrollPath.NO_MAGNITUDE], [ScrollPath.HORIZONTAL] and
 *    [ScrollPath.OUT_OF_RANGE] all bank zero, but each is tallied separately so that the
 *    next regression shows up in the diagnostics instead of as a silent flat line.
 *
 * @param previousScrollY the last `scrollY` seen from this same scroller, or null when this
 *   is the first sample — a first sample can only establish a baseline, never count.
 * @param screenHeightPx used only by [ScrollPath.PAGED], where one item is one screenful.
 */
fun resolveScrollDelta(
    sample: ScrollSample,
    previousScrollY: Int?,
    screenHeightPx: Int,
): ScrollDelta {
    // 1. Real pixels. -1 means "never set" (Compose, API < 28), so it is not a 1px scroll;
    //    a genuine 1px scroll is noise and losing it costs nothing.
    if (sample.scrollDeltaY != UNDEFINED_SCROLL) {
        val dy = abs(sample.scrollDeltaY)
        if (dy > 0) return capped(dy, ScrollPath.PIXEL_DELTA)
        // A vertical delta of exactly 0 next to a live sideways one: carousel or tab swipe.
        if (sample.scrollDeltaX != UNDEFINED_SCROLL && sample.scrollDeltaX != 0) {
            return ScrollDelta(0, ScrollPath.HORIZONTAL)
        }
        // Both zero: the view moved and told us nothing about how far. Fall through — a
        // classic View fills scrollY too, so the position paths may still have an answer.
    }

    // 2-4. Position stream. Compose writes the Y fields only when the scroller really has a
    // vertical axis, so all-zero Y beside a live X axis is a horizontal-only scroller.
    if (sample.scrollY == 0 && sample.maxScrollY == 0) {
        val horizontal = sample.scrollX != 0 || sample.maxScrollX != 0
        return ScrollDelta(0, if (horizontal) ScrollPath.HORIZONTAL else ScrollPath.NO_MAGNITUDE)
    }
    if (previousScrollY == null) return ScrollDelta(0, ScrollPath.NO_MAGNITUDE)
    val moved = abs(sample.scrollY - previousScrollY)
    if (moved == 0) return ScrollDelta(0, ScrollPath.NO_MAGNITUDE)

    if (!isLazyPseudoOffset(sample.scrollY, sample.maxScrollY)) {
        return capped(moved, ScrollPath.PIXEL_POSITION)
    }

    // A lazy feed sitting exactly on an item boundary in both samples is a pager — Reels,
    // Shorts, any snapping full-screen feed — where the pseudo-offset advances 500 per swipe
    // however tall the page is. Count the screen the thumb actually travelled. A
    // free-scrolling feed practically never lands on an exact multiple twice in a row,
    // because firstVisibleItemScrollOffset is a continuous pixel value.
    val onItemBoundary = sample.scrollY % LAZY_UNITS_PER_ITEM == 0 &&
        previousScrollY % LAZY_UNITS_PER_ITEM == 0
    if (onItemBoundary && screenHeightPx > 0) {
        return capped((moved / LAZY_UNITS_PER_ITEM) * screenHeightPx, ScrollPath.PAGED)
    }
    return capped(moved, ScrollPath.LAZY_POSITION)
}

/**
 * True when this position stream is Compose's lazy pseudo-offset rather than pixels.
 *
 * `estimatedLazyMaxScrollOffset` returns `value + 100` whenever the list can still scroll
 * forward, which is the whole time someone is scrolling a feed. A real pixel scroller is
 * only ever exactly 100 px from its own end for one accidental frame, and a frame at the
 * very bottom of a feed is worth at most a few hundred px of misattribution.
 */
private fun isLazyPseudoOffset(scrollY: Int, maxScrollY: Int): Boolean =
    maxScrollY - scrollY == LAZY_MAX_LOOKAHEAD

private fun capped(pixels: Int, path: ScrollPath): ScrollDelta = when {
    pixels in 1..MAX_DELTA_PX -> ScrollDelta(pixels, path)
    pixels > MAX_DELTA_PX -> ScrollDelta(0, ScrollPath.OUT_OF_RANGE)
    else -> ScrollDelta(0, ScrollPath.NO_MAGNITUDE)
}
