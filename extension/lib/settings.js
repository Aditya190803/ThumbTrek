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
