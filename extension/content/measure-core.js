/**
 * The measurement rule, with no DOM in it.
 *
 * A classic script, not a module: MV3 content scripts cannot be ES modules, and the
 * alternative -- injecting a module from the service worker on every navigation -- is a
 * round trip and a race on every page load for no gain. It publishes one global into the
 * content script's *isolated world* (never the page's), and `measure.js` in the same
 * `js: []` array picks it up. The tests load this file straight into a `node:vm` context,
 * so what is tested is the file that ships.
 *
 * ## What this has to get right, and why it is not one line
 *
 * 1. **Sub-element scroll.** A naive `window.scrollY` listener measures *nothing* on
 *    YouTube or X, because their feeds live in an inner scroll container and the document
 *    itself never moves. Every scroller is therefore tracked separately, keyed by its
 *    element, and the page document is just one more scroller.
 *
 * 2. **First sample is a baseline, never a distance.** The first scroll event from a
 *    scroller tells us where it is, not how far it came -- the thumb travel that got it
 *    there happened before we were looking, or (much worse) the container was restored to
 *    a saved offset. Counting it would bank the entire scroll height of a restored feed.
 *    This mirrors `ScrollDelta.kt`'s `previousScrollY == null` rule.
 *
 * 3. **Jumps are dropped, not clamped.** One event that moves more than `MAX_JUMP_VIEWPORTS`
 *    viewport heights is not a thumb: it is an anchor link, a "back to top" button, a
 *    history restore, or a virtualised list re-seating its content under a reused element.
 *    We *drop* it and re-baseline, rather than clamping it to the limit. Clamping would
 *    still bank three screens of distance the thumb never travelled, and worse, it makes a
 *    bug upstream look like enthusiasm -- the same reasoning as `MAX_DELTA_PX` on Android,
 *    which drops "so a bug upstream can't quietly manufacture kilometres". The baseline is
 *    always updated, so the next real scroll measures from where the page actually is.
 *
 * 4. **Vertical only.** A sideways scroll is a carousel or a tab swipe; Android's
 *    `ScrollPath.HORIZONTAL` is deliberately worth zero and this agrees with it. Feeds are
 *    vertical, and counting carousels would make an Instagram story reel outrank a day of
 *    real scrolling.
 *
 * 5. **Pixels out, µm converted later.** This accumulates fractional CSS pixels (a
 *    `scrollTop` is fractional on a zoomed or fractionally-scaled page) and hands them over
 *    as pixels. The single `round(px / 96 * 25400)` happens once per flush at the storage
 *    boundary, so the rounding error is bounded at half a micrometre per flush instead of
 *    per scroll event.
 *
 * 6. **Some feeds never scroll.** YouTube Shorts on the web (`/shorts/*`) is a pager:
 *    the container stays put while a wheel/tap swaps the video via history navigation, so
 *    no `scroll` event ever fires and rules 1-4 see nothing. Each new Short therefore
 *    banks one viewport height through `add()` (see measure.js for the navigation
 *    detection) -- the web equivalent of Android's `ScrollPath.PAGED`, where one swipe
 *    is one screen.
 */

(function () {
  'use strict';

  /** More than three screens in one event is a jump, not a thumb. */
  const MAX_JUMP_VIEWPORTS = 3;

  /** Floor for the jump limit, so a 0-height viewport can't drop every sample. */
  const MIN_JUMP_LIMIT_PX = 600;

  function createAccumulator(options) {
    const maxViewports = options?.maxJumpViewports ?? MAX_JUMP_VIEWPORTS;
    // WeakMap, so a scroller that an SPA route change detaches is collected with the node.
    // A Map here would be a slow leak on exactly the sites this extension exists to
    // measure: an infinite feed that swaps its container on every navigation.
    const positions = new WeakMap();
    let pixels = 0;
    let dropped = 0;

    return {
      /**
       * Record where `scroller` now is, and bank the distance since we last saw it.
       *
       * @param scroller  any object identifying the scroller (an Element, or a sentinel
       *                  standing for the page document).
       * @param position  its current vertical offset, in CSS pixels.
       * @param viewport  the viewport height in CSS pixels, for the jump limit.
       * @returns the pixels banked by this sample: 0 for a baseline or a dropped jump.
       */
      sample(scroller, position, viewport) {
        if (!Number.isFinite(position)) return 0;
        const previous = positions.get(scroller);
        positions.set(scroller, position);
        if (previous === undefined) return 0; // baseline only -- see (2) above
        const moved = Math.abs(position - previous);
        if (moved === 0) return 0;
        const limit = Math.max(MIN_JUMP_LIMIT_PX, (viewport || 0) * maxViewports);
        if (moved > limit) {
          dropped++;
          return 0; // dropped, not clamped -- see (3) above
        }
        pixels += moved;
        return moved;
      },

      /** Pixels banked since the last take. Reading resets, so a flush cannot double-count. */
      take() {
        const banked = pixels;
        pixels = 0;
        return banked;
      },

      /** Pixels banked but not yet taken. For assertions and diagnostics only. */
      pending() {
        return pixels;
      },

      /**
       * Bank a discrete page advance directly, with no baseline and no jump check.
       *
       * For pagers that never emit scroll (YouTube Shorts on web): one new page is one
       * viewport of travel by construction, so there is no position to diff and nothing
       * a jump limit could protect against -- the caller already decided this advance
       * happened (a new Short ID in the URL). Non-finite and non-positive inputs bank
       * nothing and leave the baseline scrollers untouched.
       */
      add(extra) {
        if (!Number.isFinite(extra) || extra <= 0) return 0;
        pixels += extra;
        return extra;
      },

      /** How many jumps have been dropped. Surfaced nowhere; useful when something is off. */
      droppedJumps() {
        return dropped;
      },
    };
  }

  const exported = { createAccumulator, MAX_JUMP_VIEWPORTS, MIN_JUMP_LIMIT_PX };

  // Isolated-world global for measure.js; the page's own scripts cannot see this.
  globalThis.ThumbTrekMeasure = exported;
})();
