/**
 * Turns a finished baseline week into a daily limit -- `BaselineLimit.kt`, for the browser.
 *
 * Called from the service worker's alarm, so the week ends on schedule with the popup
 * closed, and from the popup itself, so whoever opens it on day eight sees the result at
 * once. `applyAutoLimit` is a no-op after the first call, so the two can race.
 */

import { dayTotals } from './db.js';
import { micrometresToMetres } from './units.js';
import { suggestedLimit, typicalDayMeters } from './stats.js';
import { applyAutoLimit, readSettings } from './settings.js';
import { todayKey } from './dates.js';

/** Applies the limit when the week is complete; returns the baseline in metres, or null. */
export async function applyBaselineIfReady(today = todayKey()) {
  const settings = await readSettings();
  if (!settings.baselineSince) return null;
  const totals = await dayTotals();
  const dayMeters = new Map([...totals].map(([key, um]) => [key, micrometresToMetres(um)]));
  const typical = typicalDayMeters(dayMeters, settings.baselineSince, today);
  if (typical === null) return null;
  await applyAutoLimit(suggestedLimit(typical), typical, today);
  return typical;
}
