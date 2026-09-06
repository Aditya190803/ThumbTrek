// Leaderboard scoring — the pure half of SocialRepository, with the unit change from
// docs/sync-protocol.md §1 applied.
//
// Everything here is a plain function over a plain object so it can be tested without a
// Firestore, and so the "which number ranks?" question has exactly one answer in one place.
// The queries that fetch these rows live in data.js.

import { metersFromUm } from './stats.js';

/** Which stretch of time a board covers. Labels match Period.label in SocialRepository. */
export const PERIODS = [
  { id: 'week', label: 'This week', um: 'weekUm', pixels: 'weekPixels', keyField: 'weekKey' },
  { id: 'month', label: 'This month', um: 'monthUm', pixels: 'monthPixels', keyField: 'monthKey' },
  // All-time has no period key: a total never resets, so nothing gates it.
  { id: 'all', label: 'All time', um: 'totalUm', pixels: 'totalPixels', keyField: null },
];

export function periodById(id) {
  return PERIODS.find((p) => p.id === id) ?? PERIODS[0];
}

/**
 * The density a legacy row is read at.
 *
 * `SyncModel.rankUm` converts a pixels-only row through *this device's* densityDpi, which
 * is what keeps an old client roughly where it has always been on the board instead of
 * sinking to the bottom and vanishing mid-migration. A browser has no such number: a CSS
 * pixel says nothing about the phone that logged those pixels, and `devicePixelRatio` on a
 * laptop is not that phone's density.
 *
 * So the web substitutes one stated constant — a fairly ordinary phone — used for ORDERING
 * ONLY. The converted figure is never shown: `meters` stays null for these rows and the row
 * renders a dash, because a number derived from a density nobody measured would be a
 * distance the dashboard invented. Ranking with an assumption is a guess about position;
 * printing it is a lie about metres.
 */
const LEGACY_DPI = 420;

function pixelsToUm(pixels, densityDpi = LEGACY_DPI) {
  if (densityDpi <= 0) return 0;
  return Math.round((num(pixels) / densityDpi) * 25_400);
}

function num(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : 0;
}

/** Present-and-numeric, as distinct from absent. A legitimate zero is not a missing field. */
function umOrNull(data, field) {
  const value = Number(data?.[field]);
  return Number.isFinite(value) ? value : null;
}

/**
 * One row's score for one period, in µm — the `rankUm` of SyncModel.kt, field by field.
 *
 * Two rules, in this order, both taken from the reference implementation:
 *
 *  1. A row whose `weekKey` is not the current one reads as **zero** for that period rather
 *     than dropping off, and the legacy fallback is not consulted for a stale period either.
 *  2. `*Um` when the field is present, otherwise the legacy `*Pixels` count. Per field, not
 *     per row: a row carrying `totalUm` but no `weekUm`… has no `weekUm`, and a legitimate
 *     zero is never mistaken for a missing field.
 *
 * `meters` is null exactly when the fallback fired — see [LEGACY_DPI].
 */
export function scoreOf(data, periodId, keys = {}) {
  const period = periodById(periodId);
  const stale = period.keyField && data?.[period.keyField] !== keys[period.keyField];
  if (stale) return { value: 0, unit: 'um', meters: 0 };
  const um = umOrNull(data, period.um);
  if (um !== null) return { value: um, unit: 'um', meters: metersFromUm(um) };
  return { value: pixelsToUm(data?.[period.pixels]), unit: 'pixels', meters: null };
}

/** True for a row that carries no `*Um` field at all — every figure on it is a guess. */
export function isLegacyRow(data) {
  return !['weekUm', 'monthUm', 'totalUm'].some((f) => umOrNull(data, f) !== null);
}

/** Field-by-field and untyped on purpose: a half-written doc must not break the board. */
export function boardEntry(uid, data) {
  const name = typeof data?.displayName === 'string' ? data.displayName.trim() : '';
  return {
    uid,
    displayName: name || 'Trekker',
    photoUrl: typeof data?.photoUrl === 'string' ? data.photoUrl : '',
    data: data ?? {},
  };
}

/**
 * Sorts entries for [periodId] and stamps a rank on each.
 *
 * Ties keep their input order (Array#sort is stable), which matches Kotlin's
 * `sortedByDescending`. Mixing a µm row and a legacy pixel row in one sort compares two
 * different units — that is the contract's explicit compromise, and it is confined to the
 * friends board: the global board is ordered by a Firestore `orderBy` on `*Um`, and
 * Firestore never returns documents missing the ordered field, so legacy rows cannot
 * appear there at all.
 */
export function rankEntries(entries, periodId, keys = {}) {
  return entries
    .map((entry) => ({ ...entry, score: scoreOf(entry.data, periodId, keys) }))
    .sort((a, b) => b.score.value - a.score.value)
    .map((entry, index) => ({ ...entry, rank: index + 1 }));
}

/** Position of [uid] in a ranked list, or null when they are not on it. */
export function rankOf(ranked, uid) {
  return ranked.find((entry) => entry.uid === uid)?.rank ?? null;
}

/**
 * The per-source split off the board row — the one thing the dashboard shows that the
 * phone's own screen cannot show as well.
 *
 * **Each source carries its own `weekKey` / `monthKey`, and that is load-bearing** (contract
 * §3). The row-level key cannot do the job once a row has several writers: if the extension
 * last synced in W36 and the phone syncs in W37, the phone stamps the row W37 while
 * `sources.web.weekUm` still holds last week's distance. So a source contributes to a period
 * only when its *own* key matches, and reads as zero otherwise — `stale: true` here, so the
 * UI can say "nothing this week" instead of quietly showing last week's number under this
 * week's heading. A source with no key at all counts as stale rather than grandfathered:
 * reading it as current is the one mistake that inflates a live figure.
 *
 * Renders defensively by contract: `sources` may be absent entirely (a phone on an older
 * build), or hold a single client. Neither is an error, and neither means "no data" — the
 * combined totals on the row are still real. Callers get an empty array and are expected to
 * say so in words rather than draw an empty chart.
 */
export function sourceSplit(data, periodId = 'all', keys = {}) {
  const period = periodById(periodId);
  const sources = data?.sources;
  if (!sources || typeof sources !== 'object') return [];
  return Object.entries(sources)
    .filter(([, entry]) => entry && typeof entry === 'object')
    .map(([source, entry]) => {
      const stale = Boolean(period.keyField) && entry[period.keyField] !== keys[period.keyField];
      const um = stale ? 0 : num(entry[period.um]);
      return {
        source,
        um,
        meters: metersFromUm(um),
        stale,
        updatedAt: entry.updatedAt ?? null,
      };
    })
    // Sorted by slug, like SyncModel.parseSources: a stable order between syncs beats one
    // that reshuffles the list whenever two clients trade places.
    .sort((a, b) => (a.source < b.source ? -1 : a.source > b.source ? 1 : 0));
}

/** What a source calls itself on screen. Unknown slugs show as themselves, not "Other". */
export function sourceLabel(source) {
  if (source === 'android') return 'Phone';
  if (source === 'web') return 'Browser';
  return source ? source.charAt(0).toUpperCase() + source.slice(1) : 'Unknown';
}

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * "just now" / "12 min ago" / "3 h ago" / "5 days ago" — SyncModel.syncedAgo, so the two
 * screens describe the same sync in the same words. Null when there is no timestamp, so
 * the caller can say "never" in its own words rather than rendering a fake one.
 *
 * Takes a Firestore Timestamp, a Date, or epoch millis. Clock skew between two clients can
 * put [then] slightly ahead of now; that reads as "just now" rather than a negative age.
 */
export function syncedAgo(then, now = Date.now()) {
  const millis =
    then?.toMillis?.() ?? then?.toDate?.()?.getTime() ?? (then instanceof Date ? then.getTime() : Number(then));
  if (!Number.isFinite(millis) || millis <= 0) return null;
  const age = now - millis;
  if (age < 2 * MINUTE) return 'just now';
  if (age < HOUR) return `${Math.floor(age / MINUTE)} min ago`;
  if (age < DAY) return `${Math.floor(age / HOUR)} h ago`;
  if (age < 2 * DAY) return 'yesterday';
  return `${Math.floor(age / DAY)} days ago`;
}

/**
 * One archived week's row. `um` is the ranked figure; `pixels` is kept for rows frozen
 * before the unit change (firestore.rules, archive/{weekKey}/scores/{uid}).
 */
export function archiveScore(data) {
  const um = Number(data?.um);
  if (Number.isFinite(um)) return { value: um, unit: 'um', meters: metersFromUm(um) };
  return { value: num(data?.pixels), unit: 'pixels', meters: null };
}

/** "1.24 km", or a dash when the row is in a unit this page refuses to guess at. */
export function scoreText(score, formatDistance) {
  return score.meters === null ? '—' : formatDistance(score.meters);
}
