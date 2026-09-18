/**
 * The shared background core: alarms, ingest, and the message router.
 *
 * A classic script on purpose, not a module. Chrome MV3 wants a service
 * worker (see service-worker.js, which just imports this file); Firefox
 * does not do service workers yet and wants background.scripts instead
 * (see manifest.firefox.json). Classic code parses in both, so the logic
 * lives here exactly once — no static import/export, one async init that
 * dynamic-imports the ESM libs both engines already share.
 */

(function () {
  'use strict';

  const ext = globalThis.browser?.runtime ? globalThis.browser : globalThis.chrome;
  if (!ext?.runtime?.getURL) return;

  const lib = (name) => import(ext.runtime.getURL(`lib/${name}.js`));

  /** Half an hour: fresh enough, and the hourly token refresh lands on every second sync. */
  const SYNC_PERIOD_MINUTES = 30;
  const SYNC_ALARM = 'thumbtrek-sync';

  async function init() {
    const [{ api }, { accumulate, dayTotals, wipeLocalData },
      { cssPixelsToMicrometres }, { registrableDomain }, { readSettings, shouldTrack },
      { todayKey }, sync, { forwardToDesktop },
    ] = await Promise.all([
      lib('browser'), lib('db'), lib('units'), lib('domain'),
      lib('settings'), lib('dates'), lib('sync'), lib('native-bridge'),
    ]);
    const { backfillEverything, optOutRemote, syncNow, syncStatus } = sync;

    function ensureAlarm() {
      // `create` with the same name replaces the existing alarm rather than
      // stacking one, so calling this on every startup and install is safe.
      api.alarms.create(SYNC_ALARM, { periodInMinutes: SYNC_PERIOD_MINUTES, delayInMinutes: 1 });
    }

    api.runtime.onInstalled.addListener(ensureAlarm);
    api.runtime.onStartup.addListener(ensureAlarm);

    api.alarms.onAlarm.addListener((alarm) => {
      if (alarm.name === SYNC_ALARM) syncNow();
    });

    /**
     * A batch of measured pixels from a content script. The authority on
     * whether a page counts: untracked domains are dropped here, before
     * storage or network. Pixels become µm exactly once, at this boundary,
     * so per-event rounding never accumulates bias.
     */
    async function ingest(message, sender) {
      const hostname = message.hostname || (sender?.url ? new URL(sender.url).hostname : '');
      const domain = registrableDomain(hostname);
      const settings = await readSettings();
      if (!shouldTrack(domain, settings)) return { counted: 0 };
      const um = cssPixelsToMicrometres(message.pixels);
      if (um <= 0) return { counted: 0 };
      // Day key resolved on arrival, browser-local timezone — matching the
      // phone's "yyyy-MM-dd in device timezone". At most five seconds of
      // scrolling can land on the wrong side of midnight.
      const day = todayKey();
      await accumulate(day, domain, um);
      // Best-effort desktop feed (Linux `thumbtrek extension install`).
      // Never awaited: without a host this is a silent no-op after one try.
      forwardToDesktop({ type: 'trek/batch', date: day, domain, um });
      return { counted: um };
    }

    /**
     * Message router. Returning `true` synchronously keeps the reply channel
     * open for an async handler in MV3 — an `async` listener returns a
     * promise, which Chrome treats as "no reply".
     */
    api.runtime.onMessage.addListener((message, sender, sendResponse) => {
      const handlers = {
        'trek/batch': () => ingest(message, sender),
        'trek/sync-now': () => syncNow({ force: true }),
        'trek/sync-status': () => syncStatus(),
        'trek/opted-in': () => backfillEverything().then(() => syncNow({ force: true })),
        'trek/opted-out': () => optOutRemote(),
        'trek/wipe-local': () => wipeLocalData(),
        'trek/day-totals': () => dayTotals().then((totals) => [...totals]),
      };
      const handler = handlers[message?.type];
      if (!handler) return false;
      handler()
        .then((result) => sendResponse({ ok: true, result }))
        // Errors are values, not throws: callers render the reason, and an
        // exception crossing the message boundary arrives as `undefined`.
        .catch((error) => sendResponse({ ok: false, error: error?.message ?? String(error) }));
      return true;
    });
  }

  init().catch(() => {
    // A background that throws during init is dead until the next event in
    // Chrome and until restart in Firefox; swallowing here keeps one bad
    // import from bricking the install, and every handler re-checks anyway.
  });
})();
