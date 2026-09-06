// The leaderboard's arithmetic: the µm/pixels fallback, the period gates, the per-source
// rollup rules. Mirrors app/src/test/java/com/thumbtrek/app/SyncModelTest.kt, whose
// SyncModel.kt is the reference implementation for anything the contract leaves ambiguous.
//
//   node --test web/app/test/board.test.js

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  PERIODS,
  archiveScore,
  isLegacyRow,
  periodById,
  rankEntries,
  rankOf,
  scoreOf,
  scoreText,
  sourceLabel,
  sourceSplit,
  syncedAgo,
} from '../board.js';
import { formatDistance } from '../stats.js';

const KEYS = { weekKey: '2026-W31', monthKey: '2026-08' };

const row = (fields) => ({
  displayName: 'Trekker',
  photoUrl: '',
  weekKey: KEYS.weekKey,
  monthKey: KEYS.monthKey,
  ...fields,
});

// --- §1 the unit and the fallback -------------------------------------------------------

test('a row with um ranks on um', () => {
  const score = scoreOf(row({ weekUm: 500, weekPixels: 999_999 }), 'week', KEYS);
  assert.equal(score.value, 500);
  assert.equal(score.unit, 'um');
  assert.equal(score.meters, 0.0005);
});

test('a legitimate zero um is not mistaken for a missing field', () => {
  // The one that would bite: a user with no distance this week must read as 0, not
  // silently fall back to a stale pixel count.
  const score = scoreOf(row({ weekUm: 0, weekPixels: 999_999 }), 'week', KEYS);
  assert.equal(score.value, 0);
  assert.equal(score.unit, 'um');
});

test('rows with no um fall back to pixels, and refuse to name a distance', () => {
  // An old client's row keeps roughly the position it always had instead of vanishing.
  // 420 px read at the nominal 420 dpi is one inch, so 25400 µm — the same figure
  // SyncModelTest pins for rankUm(null, 420, 420).
  const score = scoreOf(row({ weekPixels: 420 }), 'week', KEYS);
  assert.equal(score.value, 25_400);
  assert.equal(score.unit, 'pixels');
  // …but the metres are null, because no browser knows that phone's real density.
  assert.equal(score.meters, null);
  assert.equal(scoreText(score, formatDistance), '—');
  assert.equal(scoreOf(row({ weekPixels: 0 }), 'week', KEYS).value, 0);
});

test('the fallback is decided per field, not per row', () => {
  // A row carrying totalUm but no weekUm has no weekUm — reading weekPixels for the week
  // while ranking totalUm for all time is exactly what rankUm() does on the phone.
  const data = row({ totalUm: 9_000, weekPixels: 420 });
  assert.equal(scoreOf(data, 'all', KEYS).unit, 'um');
  assert.equal(scoreOf(data, 'week', KEYS).unit, 'pixels');
  assert.equal(isLegacyRow(data), false);
  assert.equal(isLegacyRow(row({ weekPixels: 1 })), true);
});

test('a stale period key reads as zero rather than dropping off', () => {
  const stale = row({ weekKey: '2026-W30', weekUm: 5_000_000, monthUm: 7 });
  assert.equal(scoreOf(stale, 'week', KEYS).value, 0);
  // The fallback is not consulted for a stale period either.
  assert.equal(scoreOf(row({ weekKey: '2026-W30', weekPixels: 999 }), 'week', KEYS).value, 0);
  // The month is gated by its own key, independently.
  assert.equal(scoreOf(stale, 'month', KEYS).value, 7);
  // All time has no key to go stale.
  assert.equal(scoreOf(row({ weekKey: '1999-W01', totalUm: 3 }), 'all', KEYS).value, 3);
});

test('missing and malformed fields degrade to zero instead of NaN', () => {
  assert.equal(scoreOf({}, 'all', KEYS).value, 0);
  assert.equal(scoreOf({ totalUm: 'lots' }, 'all', KEYS).value, 0);
  assert.equal(scoreOf(null, 'all', KEYS).value, 0);
});

// --- ranking ----------------------------------------------------------------------------

test('entries rank descending and carry their position', () => {
  const entries = [
    { uid: 'a', displayName: 'A', data: row({ weekUm: 10 }) },
    { uid: 'b', displayName: 'B', data: row({ weekUm: 30 }) },
    { uid: 'c', displayName: 'C', data: row({ weekUm: 20 }) },
  ];
  const ranked = rankEntries(entries, 'week', KEYS);
  assert.deepEqual(ranked.map((e) => e.uid), ['b', 'c', 'a']);
  assert.deepEqual(ranked.map((e) => e.rank), [1, 2, 3]);
  assert.equal(rankOf(ranked, 'a'), 3);
  assert.equal(rankOf(ranked, 'nobody'), null);
});

test('ties keep the order they arrived in', () => {
  const entries = ['x', 'y', 'z'].map((uid) => ({ uid, displayName: uid, data: row({ weekUm: 5 }) }));
  assert.deepEqual(rankEntries(entries, 'week', KEYS).map((e) => e.uid), ['x', 'y', 'z']);
});

test('a legacy row is ranked against um rows on the friends board', () => {
  // The mixed comparison is the contract's explicit compromise; what matters is that the
  // old row lands in a plausible place rather than at zero. 100_000 px at the nominal
  // 420 dpi is 6_047_619 µm — a bit over six metres — so it sits between the two µm rows.
  const ranked = rankEntries(
    [
      { uid: 'new', displayName: 'New', data: row({ weekUm: 10_000_000 }) },
      { uid: 'old', displayName: 'Old', data: row({ weekPixels: 100_000 }) },
      { uid: 'tiny', displayName: 'Tiny', data: row({ weekUm: 1_000 }) },
    ],
    'week',
    KEYS,
  );
  assert.equal(scoreOf(row({ weekPixels: 100_000 }), 'week', KEYS).value, 6_047_619);
  assert.deepEqual(ranked.map((e) => e.uid), ['new', 'old', 'tiny']);
});

test('the three periods are the ones the app names', () => {
  assert.deepEqual(PERIODS.map((p) => p.label), ['This week', 'This month', 'All time']);
  assert.equal(periodById('month').um, 'monthUm');
  assert.equal(periodById('nonsense').id, 'week'); // unknown ids fall back, never crash
});

// --- §3 the per-source split ------------------------------------------------------------

test('each source is gated by its own week key, not the row s', () => {
  // The row says W31 because the phone synced today; the browser last synced in W30 and
  // its weekUm is last week's. Counting it would be a board that pays out for stopping.
  const data = row({
    weekUm: 30,
    sources: {
      android: { weekKey: '2026-W31', weekUm: 30, monthKey: '2026-08', monthUm: 100, totalUm: 900 },
      web: { weekKey: '2026-W30', weekUm: 70, monthKey: '2026-07', monthUm: 400, totalUm: 500 },
    },
  });
  const split = sourceSplit(data, 'week', KEYS);
  assert.deepEqual(split.map((s) => [s.source, s.um, s.stale]), [
    ['android', 30, false],
    ['web', 0, true],
  ]);
  // The month is gated separately, and all-time never goes stale.
  assert.deepEqual(sourceSplit(data, 'month', KEYS).map((s) => s.um), [100, 0]);
  assert.deepEqual(sourceSplit(data, 'all', KEYS).map((s) => s.um), [900, 500]);
});

test('a source with no key of its own counts as stale', () => {
  // It can only be an entry written before the per-source keys existed. Reading it as
  // current is the one mistake that inflates a live figure.
  const data = row({ sources: { web: { weekUm: 70, totalUm: 70 } } });
  assert.equal(sourceSplit(data, 'week', KEYS)[0].stale, true);
  assert.equal(sourceSplit(data, 'week', KEYS)[0].um, 0);
});

test('an absent or single-client sources block is normal, not an error', () => {
  assert.deepEqual(sourceSplit(row({ totalUm: 5 }), 'all', KEYS), []);
  assert.deepEqual(sourceSplit(row({ sources: 'broken' }), 'all', KEYS), []);
  assert.equal(sourceSplit(row({ sources: { android: { totalUm: 5 } } }), 'all', KEYS).length, 1);
  // A malformed entry is skipped rather than taking the section down with it.
  assert.equal(sourceSplit(row({ sources: { android: 7 } }), 'all', KEYS).length, 0);
});

test('sources are labelled the way the app labels them', () => {
  assert.equal(sourceLabel('android'), 'Phone');
  assert.equal(sourceLabel('web'), 'Browser');
  assert.equal(sourceLabel('desktop'), 'Desktop'); // an unknown slug shows as itself
});

// --- the archive ------------------------------------------------------------------------

test('the archive ranks on um and keeps pixels rows readable', () => {
  assert.deepEqual(archiveScore({ um: 2_000_000, pixels: 1 }), {
    value: 2_000_000,
    unit: 'um',
    meters: 2,
  });
  const frozen = archiveScore({ pixels: 5_000 });
  assert.equal(frozen.unit, 'pixels');
  assert.equal(frozen.meters, null);
  assert.equal(scoreText(frozen, formatDistance), '—');
});

// --- freshness --------------------------------------------------------------------------

test('sync ages read the way the app words them', () => {
  const now = Date.parse('2026-08-02T12:00:00Z');
  const ago = (ms) => syncedAgo(now - ms, now);
  assert.equal(ago(30_000), 'just now');
  assert.equal(ago(12 * 60_000), '12 min ago');
  assert.equal(ago(3 * 3_600_000), '3 h ago');
  assert.equal(ago(30 * 3_600_000), 'yesterday');
  assert.equal(ago(5 * 86_400_000), '5 days ago');
  // Clock skew between two clients must not produce a negative age.
  assert.equal(syncedAgo(now + 60_000, now), 'just now');
  assert.equal(syncedAgo(null), null);
  assert.equal(syncedAgo(0), null);
  // Firestore hands back Timestamps, not numbers.
  assert.equal(syncedAgo({ toMillis: () => now - 30_000 }, now), 'just now');
  assert.equal(syncedAgo(new Date(now - 5 * 86_400_000), now), '5 days ago');
});
