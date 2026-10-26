/**
 * Settings, in `chrome.storage.local`. A port of `data/Prefs.kt`'s semantics.
 *
 * Two rules carried over from the phone, both of which are decisions rather than details:
 *
 *  - Tracking is **opt-out per site and on by default** for the four built-in feeds. "A
 *    tracker that tracks nothing on first run is just a broken app."
 *  - Everything social is **opt-in and off by default**. Nothing leaves the device until
 *    `leaderboardOptIn` is true (contract §5), and signing in by itself publishes nothing.
 *
 * And the structural one, straight from `Prefs.customApps`: the enabled set and the
 * user-added set are **separate**, so unchecking a site you added does not forget that it
 * exists. Collapsing them into one list is the bug that makes a user re-type a domain every
 * time they pause tracking it.
 */

import { api } from './browser.js';
import { DEFAULT_SITES } from './domain.js';
import { MAX_DAILY_LIMIT_M, MIN_DAILY_LIMIT_M } from './stats.js';
import { todayKey } from './dates.js';

const KEY = 'settings';

export const DEFAULTS = Object.freeze({
  /** Domains the service worker should count. Defaults to all four built-ins. */
  trackedSites: [...DEFAULT_SITES.keys()],
  /** Domains the user added beyond the built-ins, as `domain -> label`. */
  customSites: {},
  /** Count every site, not just the tracked list. Off by default -- see the README. */
  trackEverything: false,
  /** No score leaves the device until this is on. */
  leaderboardOptIn: false,
  /** Anonymous by default: publish a stable pseudonym and no photo until the user opts
   *  into their Google name. Same clean migration as the phone — a stored explicit choice
   *  wins over this default, so only the never-chosen inherit it. */
  anonymous: true,
  /** Daily limit in metres, or null for baseline mode. Local to this browser and never
   *  synced -- the phone keeps its own. Null by default, like a fresh phone install:
   *  nobody is judged against a cap they did not choose. */
  dailyLimitM: null,
  /** The day the current limit started counting (`yyyy-MM-dd`). Earlier days are never
   *  judged against it. Null for limits that predate this field: all history, as before. */
  limitSince: null,
  /** The day the baseline week began; non-null while one is being learned. Set on install
   *  and update by the service worker, so the default is learning, never a guessed cap. */
  baselineSince: null,
  /** The typical day behind a just-applied automatic limit, until it is acknowledged. */
  autoLimitNotice: null,
  /** True once the user has explicitly chosen no limit at all. Nothing overrides that. */
  noLimitChosen: false,
});

/** Reads the whole settings object, with defaults filled in for anything never written. */
export async function readSettings() {
  const stored = await api.storage.local.get(KEY);
  return { ...DEFAULTS, ...(stored?.[KEY] ?? {}) };
}

async function write(patch) {
  const next = { ...(await readSettings()), ...patch };
  await api.storage.local.set({ [KEY]: next });
  return next;
}

export function setSiteTracked(domain, tracked) {
  return readSettings().then((settings) => {
    const set = new Set(settings.trackedSites);
    if (tracked) set.add(domain); else set.delete(domain);
    return write({ trackedSites: [...set] });
  });
}

/** Adding a site both remembers it and switches it on, exactly like `Prefs.addCustomApp`. */
export async function addCustomSite(domain, label) {
  const settings = await readSettings();
  const set = new Set(settings.trackedSites);
  set.add(domain);
  return write({
    customSites: { ...settings.customSites, [domain]: label || domain },
    trackedSites: [...set],
  });
}

/** Forgetting a site removes it from both lists. Its measured history is untouched. */
export async function removeCustomSite(domain) {
  const settings = await readSettings();
  const custom = { ...settings.customSites };
  delete custom[domain];
  return write({
    customSites: custom,
    trackedSites: settings.trackedSites.filter((site) => site !== domain),
  });
}

export const setTrackEverything = (enabled) => write({ trackEverything: enabled });
export const setLeaderboardOptIn = (enabled) => write({ leaderboardOptIn: enabled });
export const setAnonymous = (enabled) => write({ anonymous: enabled });

/**
 * A hand-set limit in metres (clamped to the phone's bounds), or null for explicitly no
 * limit. Either way it is the user's call, so any baseline week and its pending notice end
 * here. A first limit counts from today; changing an existing one keeps its start date.
 */
export async function setDailyLimit(meters, today = todayKey()) {
  if (meters === null) {
    return write({
      dailyLimitM: null, limitSince: null, baselineSince: null, autoLimitNotice: null,
      noLimitChosen: true,
    });
  }
  const settings = await readSettings();
  const since = settings.limitSince ?? (dailyLimit(settings) === null ? today : null);
  return write({
    dailyLimitM: Math.min(MAX_DAILY_LIMIT_M, Math.max(MIN_DAILY_LIMIT_M, Math.round(meters))),
    limitSince: since,
    baselineSince: null,
    autoLimitNotice: null,
    noLimitChosen: false,
  });
}

/** Begin (or restart) a baseline week: no cap and no judgements until it is done. */
export const startBaseline = (today = todayKey()) => write({
  dailyLimitM: null, limitSince: null, baselineSince: today, autoLimitNotice: null,
  noLimitChosen: false,
});

/**
 * Starts a baseline week for anyone who has neither a limit nor an explicit "no limit".
 * Run on install and on update, so existing users get a fresh week rather than a limit
 * computed from older history -- the phone's migration rule.
 */
export async function ensureBaselineDefault(today = todayKey()) {
  const settings = await readSettings();
  if (dailyLimit(settings) !== null || settings.baselineSince || settings.noLimitChosen) return;
  await startBaseline(today);
}

/**
 * The baseline week is done: set its limit, starting today. ThumbTrek acting, not the user
 * editing. Does nothing once the week has been consumed, so callers can race safely.
 */
export async function applyAutoLimit(limitM, baselineM, today = todayKey()) {
  const settings = await readSettings();
  if (!settings.baselineSince) return settings;
  return write({
    dailyLimitM: limitM, limitSince: today, baselineSince: null,
    autoLimitNotice: Math.round(baselineM),
  });
}

export const dismissAutoLimitNotice = () => write({ autoLimitNotice: null });

/**
 * `parseDailyLimit`: a whole-ish number of metres inside the bounds, else null. Rejecting
 * rather than clamping at the input is what lets the form say *why* a value was refused.
 */
export function parseDailyLimit(input) {
  const text = String(input ?? '').trim();
  if (!/^\d+(\.\d+)?$/.test(text)) return null;
  const value = Number(text);
  return value >= MIN_DAILY_LIMIT_M && value <= MAX_DAILY_LIMIT_M ? value : null;
}

/** The stored limit, or null when unset or unusable -- baseline mode either way. */
export function dailyLimit(settings) {
  const value = settings?.dailyLimitM;
  return typeof value === 'number' && value > 0 ? value : null;
}

/**
 * The tracking decision, kept pure so it can be tested without a browser and so the service
 * worker -- the single authority on it -- can apply it without a round trip.
 *
 * A domain of '' (an IP literal, `localhost`, a `file://` page) is never tracked, even under
 * "track everything": there is no registrable domain to file it under, and a row keyed by
 * the empty string would be a bug wearing a data point's clothes.
 */
export function shouldTrack(domain, settings) {
  if (!domain) return false;
  if (settings.trackEverything) return true;
  return settings.trackedSites.includes(domain);
}

/** Every domain the options page should list: the built-ins first, then user additions. */
export function listedSites(settings) {
  const seen = new Set();
  const out = [];
  for (const [domain, label] of DEFAULT_SITES) {
    seen.add(domain);
    out.push({ domain, label, custom: false, tracked: settings.trackedSites.includes(domain) });
  }
  for (const [domain, label] of Object.entries(settings.customSites)) {
    if (seen.has(domain)) continue;
    seen.add(domain);
    out.push({ domain, label, custom: true, tracked: settings.trackedSites.includes(domain) });
  }
  // A domain that is enabled but in neither list can only come from an older build or a
  // hand-edited store. Show it rather than silently counting a site the user cannot see.
  for (const domain of settings.trackedSites) {
    if (seen.has(domain)) continue;
    out.push({ domain, label: domain, custom: true, tracked: true });
  }
  return out;
}

/** Labels for the popup breakdown, as a Map so `siteName` can take it directly. */
export function customLabels(settings) {
  return new Map(Object.entries(settings.customSites));
}
