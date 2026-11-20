// The shared limit and per-app limits, the pure half (docs/sync-protocol.md §7). A port of
// app/src/main/java/com/thumbtrek/app/stats/Limits.kt.
//
// This file is shared. extension/lib/limits.js and web/app/limits.js are byte-identical,
// and a test in each suite fails if they drift. It imports nothing for that reason.

/** Premium is not on sale, so nothing is held back (§9). Flip with firestore.rules. */
export const BILLING_ENFORCED = false;

/** What Premium includes. The same five strings on every client, in the same order. */
export const PREMIUM_FEATURES = [
  'One limit across your phone and your browser',
  'A separate limit for each app',
  'Weekly and monthly reports',
  'Private groups and challenges with friends',
  'Backup and restore of your full history',
];

export const UM_PER_METRE = 1_000_000;
export const MIN_APP_LIMIT_M = 5;
export const MAX_APP_LIMIT_M = 10_000;
const WARN_FRACTION = 0.8;

/**
 * What a sync should do about the shared limit. `local` and `remote` are
 * `{ limitUm, setAt }`, with limitUm 0 meaning "no limit"; `remote` is null when the
 * account has no copy yet. Newest edit wins. A tie keeps the server's copy, so two clients
 * that both believe they are current never write back and forth.
 */
export function reconcileLimit(local, remote) {
  if (!remote) return { action: 'push', limit: local };
  if (local.setAt > remote.setAt) return { action: 'push', limit: local };
  if (local.setAt < remote.setAt || local.limitUm !== remote.limitUm) {
    return { action: 'adopt', limit: remote };
  }
  return { action: 'none', limit: local };
}

/** 0 quiet, 1 at 80% of the limit, 2 over it: the levels the phone nudges at. */
export function limitLevel(meters, limitMeters) {
  if (!(limitMeters > 0) || !(meters > 0)) return 0;
  if (meters > limitMeters) return 2;
  return meters >= limitMeters * WARN_FRACTION ? 1 : 0;
}

/**
 * Every app or site that has its own limit, measured against it, furthest over first.
 *
 * @param todayMeters Map<key, metres>
 * @param limits      plain object, key -> metres
 */
export function appLimitStatuses(todayMeters, limits) {
  return Object.entries(limits ?? {})
    .filter(([, limit]) => limit > 0)
    .map(([key, limit]) => {
      const meters = todayMeters.get(key) ?? 0;
      return { key, meters, limitMeters: limit, over: meters > limit, level: limitLevel(meters, limit) };
    })
    .sort((a, b) => b.meters / b.limitMeters - a.meters / a.limitMeters);
}

/** A whole number of metres inside the bounds, else null, so a form can say why. */
export function parseAppLimit(input) {
  const text = String(input ?? '').trim();
  if (!/^\d+$/.test(text)) return null;
  const value = Number(text);
  return value >= MIN_APP_LIMIT_M && value <= MAX_APP_LIMIT_M ? value : null;
}
