/**
 * The service worker: the one place that decides what is counted and when it is pushed.
 *
 * MV3 workers are not long-lived -- Chrome stops them after ~30s idle and restarts them on
 * the next event. So there is no state in this file beyond what is in IndexedDB and
 * `chrome.storage`, every handler is idempotent, and the periodic sync is an alarm rather
 * than a `setInterval` (which would die with the worker and never come back).
 */

import { api } from '../lib/browser.js';
import { accumulate, dayTotals, wipeLocalData } from '../lib/db.js';
import { cssPixelsToMicrometres } from '../lib/units.js';
import { registrableDomain } from '../lib/domain.js';
import { readSettings, shouldTrack } from '../lib/settings.js';
import { todayKey } from '../lib/dates.js';
import {
  backfillEverything, optOutRemote, syncNow, syncStatus,
} from '../lib/sync.js';

const SYNC_ALARM = 'thumbtrek-sync';

/**
 * Half an hour. Long enough that the board is never more than a coffee break stale, short
 * enough that a browser closed for the night has usually pushed the day before it went. The
 * Firebase ID token expires after an hour, so every second sync refreshes it -- which is
 * exactly the case `freshIdToken` exists to make invisible.
 */
const SYNC_PERIOD_MINUTES = 30;

function ensureAlarm() {
  // `create` with the same name replaces the existing alarm rather than stacking one, so
  // calling this on every startup and install is safe and keeps a dropped alarm from being
  // permanent.
  api.alarms.create(SYNC_ALARM, { periodInMinutes: SYNC_PERIOD_MINUTES, delayInMinutes: 1 });
}

api.runtime.onInstalled.addListener(ensureAlarm);
api.runtime.onStartup.addListener(ensureAlarm);

api.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SYNC_ALARM) syncNow();
});

/**
 * A batch of measured pixels from a content script.
 *
 * This is the authority on whether a page counts. The content script measures
 * unconditionally and asks nothing -- see content/measure.js for why -- so an untracked
 * domain is dropped right here, before it reaches storage or the network.
 *
 * Pixels arrive as a float and become µm exactly once, here, at the storage boundary. Doing
 * the conversion per scroll event would round ~200 times a second and lose a metre a day to
 * accumulated bias; doing it per flush bounds the error at half a micrometre.
 */
async function ingest(message, sender) {
  const hostname = message.hostname || (sender?.url ? new URL(sender.url).hostname : '');
  const domain = registrableDomain(hostname);
  const settings = await readSettings();
  if (!shouldTrack(domain, settings)) return { counted: 0 };
  const um = cssPixelsToMicrometres(message.pixels);
  if (um <= 0) return { counted: 0 };
  // The day key is resolved on arrival, in the browser's local timezone, matching the
  // phone's "yyyy-MM-dd in device timezone". A flush that crosses midnight files under the
  // day it landed, which is at most five seconds of scrolling on the wrong side of the
  // boundary and needs no clock reconciliation to get there.
  await accumulate(todayKey(), domain, um);
  return { counted: um };
}

/**
 * Message router. Returning `true` synchronously is what keeps the reply channel open for
 * an async handler in MV3 -- an `async` listener returns a promise, which Chrome treats as
 * "no reply", and the caller hangs until the port closes.
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
    // Errors are values here, not throws: every caller is a UI that needs to render the
    // reason, and an exception crossing the message boundary arrives as `undefined`.
    .catch((error) => sendResponse({ ok: false, error: error?.message ?? String(error) }));
  return true;
});
