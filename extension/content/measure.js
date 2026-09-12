/**
 * The content script. Listens, batches, hands pixels to the service worker.
 *
 * This runs on every page load in the browser, so the hot path is one WeakMap lookup, one
 * subtraction and one comparison -- see measure-core.js for the rule it applies. Everything
 * expensive (settings, storage, the unit conversion) lives in the service worker.
 *
 * ## Why it measures unconditionally
 *
 * It does not ask whether this site is tracked. The service worker is the single authority
 * on that and drops batches for untracked domains on arrival. The alternatives were both
 * worse: asking at startup costs a message round trip on every page load *and* leaves
 * already-open tabs measuring under stale settings until they are reloaded; broadcasting
 * settings changes to every tab fixes the staleness by adding a fan-out and a second source
 * of truth. Filtering at the one place that owns the answer means a settings change takes
 * effect on the next flush, everywhere, with no plumbing. The cost is a passive listener on
 * pages we will discard -- and nothing about an untracked page is stored or sent anywhere
 * outside the extension.
 *
 * ## Why one listener, in the capture phase
 *
 * `scroll` does not bubble from elements, so a listener on `window` only hears the document
 * -- which is exactly the bug that makes a naive implementation report zero on YouTube and
 * X. It *does* capture, though: a capture-phase listener on `window` sees scroll events
 * targeting any element in the document. One listener therefore covers the page and every
 * inner feed container, present and future, which is also why an SPA swapping its DOM
 * cannot leak listeners here -- there is only ever the one, for the life of the document.
 */

(function () {
  'use strict';

  const api = globalThis.browser?.runtime ? globalThis.browser : globalThis.chrome;
  const core = globalThis.ThumbTrekMeasure;
  if (!api?.runtime?.id || !core) return;

  /** Long enough that a burst of scrolling is one message; short enough to survive a crash. */
  const FLUSH_INTERVAL_MS = 5000;

  /** Below this a flush is not worth a message: ~0.5 mm of thumb travel. */
  const MIN_FLUSH_PIXELS = 2;

  /** Stands in for the page document, which is a scroller like any other but not an Element. */
  const PAGE = { page: true };

  const accumulator = core.createAccumulator();
  let timer = null;
  let stopped = false;

  function viewportHeight() {
    return window.innerHeight || document.documentElement?.clientHeight || 0;
  }

  // --- YouTube Shorts pager --------------------------------------------------------
  // `/shorts/<id>` never scrolls: the container stays put while a wheel/tap swaps the
  // video through history navigation, so the capture-phase scroll listener above sees
  // nothing and a Shorts session would read as zero. Each *new* Short banks one viewport
  // height -- the web equivalent of Android's ScrollPath.PAGED.
  //
  // Counting URL changes rather than wheel deltas is deliberate: a wheel that doesn't
  // change the video is a bounce, not travel, and a keyboard/tap/button advance that
  // does change it is travel however it was triggered. The first Short seen is a baseline
  // (mirroring the accumulator's first-sample rule); leaving /shorts resets it, so
  // returning later doesn't bank the landing itself -- opening Shorts is a tap, not a
  // scroll.
  let lastShortsId = null;

  function shortsId() {
    try {
      if (!/(^|\.)youtube\.com$/.test(location.hostname)) return null;
      const match = location.pathname.match(/^\/shorts\/([^/]+)/);
      return match ? match[1] : null;
    } catch {
      return null;
    }
  }

  function checkShorts() {
    const id = shortsId();
    if (id === null) {
      lastShortsId = null;
      return;
    }
    if (lastShortsId === null) {
      lastShortsId = id; // baseline only -- see above
      return;
    }
    if (id !== lastShortsId) {
      lastShortsId = id;
      const viewport = viewportHeight();
      if (viewport > 0) accumulator.add(viewport);
    }
  }

  // YouTube is an SPA: Shorts navigation is a history entry, not a document load, so the
  // content script stays alive across swipes and has to watch the URL itself.
  try {
    for (const method of ['pushState', 'replaceState']) {
      const original = history[method];
      if (typeof original !== 'function') continue;
      history[method] = function (...args) {
        const result = original.apply(this, args);
        checkShorts();
        return result;
      };
    }
  } catch {
    // A page that freezes its own history object keeps working; Shorts counting just
    // falls back to the event listeners below.
  }

  function onScroll(event) {
    const target = event.target;
    // A document-level scroll arrives with `target` as the Document (or, in quirks mode,
    // the body). `scrollingElement` is the one property that is correct in both modes.
    if (!target || target === document || target === window || target.nodeType === 9) {
      accumulator.sample(PAGE, window.scrollY, viewportHeight());
      return;
    }
    const top = target.scrollTop;
    if (typeof top !== 'number') return;
    accumulator.sample(target, top, viewportHeight());
  }

  function flush(reason) {
    if (stopped) return;
    const pixels = accumulator.take();
    if (pixels < MIN_FLUSH_PIXELS) return;
    // The extension can be reloaded or updated under a live tab, which orphans this
    // context: `runtime.id` goes undefined and sendMessage throws. Stop rather than throw
    // on a timer forever -- the page keeps working, it just stops being measured until the
    // next navigation injects a fresh content script.
    if (!api.runtime?.id) {
      stopped = true;
      return;
    }
    try {
      const sent = api.runtime.sendMessage({
        type: 'trek/batch',
        hostname: location.hostname,
        pixels,
        reason,
      });
      // Chrome rejects the promise when no receiver is listening (a sleeping worker that
      // failed to wake). Swallow it: the pixels are gone either way, and an unhandled
      // rejection in a content script shows up in the *page's* console, which would look
      // like the extension breaking the site.
      if (sent?.catch) sent.catch(() => {});
    } catch {
      stopped = true;
    }
  }

  function schedule() {
    if (timer !== null || stopped) return;
    timer = setInterval(() => flush('interval'), FLUSH_INTERVAL_MS);
  }

  // Passive: this handler never calls preventDefault, and saying so lets the compositor
  // keep scrolling on its own thread instead of waiting on us. Capture: see the header.
  window.addEventListener('scroll', onScroll, { passive: true, capture: true });

  // Shorts advances arrive as history entries plus whatever input triggered them; watch
  // both, since either one alone misses a navigation style (button taps fire no wheel,
  // replaceState fires no popstate). checkShorts is id-based, so duplicates are free.
  window.addEventListener('wheel', checkShorts, { passive: true, capture: true });
  window.addEventListener('touchend', checkShorts, { passive: true, capture: true });
  window.addEventListener('keydown', checkShorts, { capture: true });
  window.addEventListener('popstate', checkShorts);
  window.addEventListener('hashchange', checkShorts);
  checkShorts(); // baseline the Shorts ID when landing directly on /shorts/<id>

  // The two events that actually fire when a tab goes away. `pagehide` covers navigation
  // and bfcache eviction; `visibilitychange` covers tab switches and the phone-style
  // "browser backgrounded" case, and is the only one guaranteed to run on mobile. `unload`
  // is deliberately not used -- registering it disqualifies the page from the bfcache.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flush('hidden');
  });
  window.addEventListener('pagehide', () => flush('pagehide'), { capture: true });

  schedule();
})();
