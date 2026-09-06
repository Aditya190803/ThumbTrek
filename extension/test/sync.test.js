/**
 * The protocol arithmetic: day-document rollups, the combine rule, and which days are dirty.
 * These are the parts of syncing that have answers worth checking without a network.
 *
 * Reference: `app/src/main/java/com/thumbtrek/app/social/SyncModel.kt`.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  boardRowIsOursAlone, buildDayDocument, combinedTotals, dirtyDays, MAX_APPS_PER_DAY,
  SOURCE, syncedAgo,
} from '../lib/sync.js';
import { toFields, fromFields, toValue } from '../lib/firestore.js';

const NOW = new Date('2026-09-06T10:00:00.000Z');

test('a day document carries the source and rolls the sites up', () => {
  const doc = buildDayDocument('2026-09-06', [
    { date: '2026-09-06', domain: 'youtube.com', um: 940_000 },
    { date: '2026-09-06', domain: 'instagram.com', um: 900_000 },
  ], NOW);
  assert.equal(doc.date, '2026-09-06');
  assert.equal(doc.source, 'web');
  assert.equal(doc.um, 1_840_000);
  assert.deepEqual(doc.apps, { 'youtube.com': 940_000, 'instagram.com': 900_000 });
});

test('the day document id the rules re-derive is date + __ + source', () => {
  const doc = buildDayDocument('2026-09-06', [], NOW);
  // validDay(): `dayId == data.date + '__' + data.source`.
  assert.equal(`${doc.date}__${doc.source}`, '2026-09-06__web');
  assert.ok(!SOURCE.includes('.'), 'a source slug sits inside a dotted field path');
});

test('apps stays inside the 64-key cap, with the tail summed rather than dropped', () => {
  const rows = [];
  for (let i = 0; i < 100; i++) rows.push({ domain: `site${i}.com`, um: 1000 - i });
  const doc = buildDayDocument('2026-09-06', rows, NOW);

  const keys = Object.keys(doc.apps);
  assert.ok(keys.length <= MAX_APPS_PER_DAY, `${keys.length} keys would be rejected`);
  assert.equal(keys.length, 64);
  assert.ok(keys.includes('other'));
  // The distance survives the truncation even though the breakdown does not.
  const declared = Object.values(doc.apps).reduce((a, b) => a + b, 0);
  assert.equal(doc.um, rows.reduce((a, row) => a + row.um, 0));
  assert.equal(declared, doc.um);
  // The busiest sites are the ones kept by name.
  assert.ok(keys.includes('site0.com'));
  assert.ok(!keys.includes('site99.com'));
});

test('zero-µm rows never reach the document', () => {
  const doc = buildDayDocument('2026-09-06', [
    { domain: 'youtube.com', um: 5 },
    { domain: 'ghost.com', um: 0 },
  ], NOW);
  assert.deepEqual(doc.apps, { 'youtube.com': 5 });
  assert.equal(doc.um, 5);
});

test('an empty day is a valid document, not a crash', () => {
  const doc = buildDayDocument('2026-09-06', [], NOW);
  assert.equal(doc.um, 0);
  assert.deepEqual(doc.apps, {});
});

// ————————————————————— combine, and the stale-key rule —————————————————————

const WEEK = '2026-W37';
const MONTH = '2026-09';

test('combine sums every source, including slugs this build has never seen', () => {
  const combined = combinedTotals({
    android: { weekKey: WEEK, weekUm: 100, monthKey: MONTH, monthUm: 300, totalUm: 900 },
    web: { weekKey: WEEK, weekUm: 20, monthKey: MONTH, monthUm: 60, totalUm: 180 },
    tv: { weekKey: WEEK, weekUm: 1, monthKey: MONTH, monthUm: 2, totalUm: 3 },
  }, WEEK, MONTH);
  assert.deepEqual(combined, { weekUm: 121, monthUm: 362, totalUm: 1083 });
});

test('a source with a stale weekKey contributes zero for the week, not last week', () => {
  // The leaderboard-that-pays-out-for-stopping bug: web last synced in W36.
  const combined = combinedTotals({
    android: { weekKey: WEEK, weekUm: 100, monthKey: MONTH, monthUm: 300, totalUm: 900 },
    web: { weekKey: '2026-W36', weekUm: 5_000, monthKey: MONTH, monthUm: 60, totalUm: 180 },
  }, WEEK, MONTH);
  assert.equal(combined.weekUm, 100);
  // The month is still current, so it still counts...
  assert.equal(combined.monthUm, 360);
  // ...and all-time has no key to go stale.
  assert.equal(combined.totalUm, 1080);
});

test('a stale monthKey is gated independently of the week', () => {
  const combined = combinedTotals({
    web: { weekKey: WEEK, weekUm: 7, monthKey: '2026-08', monthUm: 5_000, totalUm: 9 },
  }, WEEK, MONTH);
  assert.deepEqual(combined, { weekUm: 7, monthUm: 0, totalUm: 9 });
});

test('a source with no keys at all reads as stale, never as grandfathered-fresh', () => {
  const combined = combinedTotals({
    legacy: { weekUm: 5_000, monthUm: 5_000, totalUm: 42 },
  }, WEEK, MONTH);
  assert.deepEqual(combined, { weekUm: 0, monthUm: 0, totalUm: 42 });
});

test('combine degrades to zero on a missing or half-written sources map', () => {
  assert.deepEqual(combinedTotals(undefined, WEEK, MONTH), { weekUm: 0, monthUm: 0, totalUm: 0 });
  assert.deepEqual(combinedTotals({}, WEEK, MONTH), { weekUm: 0, monthUm: 0, totalUm: 0 });
  assert.deepEqual(
    combinedTotals({ web: null, android: 'nonsense', tv: { totalUm: 'x' } }, WEEK, MONTH),
    { weekUm: 0, monthUm: 0, totalUm: 0 },
  );
});

// ——————————————————————————— dirty days ———————————————————————————

test('an empty pushed map means backfill everything', () => {
  const totals = new Map([['2026-09-04', 10], ['2026-09-05', 20], ['2026-09-06', 30]]);
  assert.deepEqual(dirtyDays(totals, new Map()), ['2026-09-04', '2026-09-05', '2026-09-06']);
});

test('only days whose total moved are pushed again', () => {
  const totals = new Map([['2026-09-04', 10], ['2026-09-05', 20], ['2026-09-06', 30]]);
  const pushed = new Map([['2026-09-04', 10], ['2026-09-05', 15]]);
  assert.deepEqual(dirtyDays(totals, pushed), ['2026-09-05', '2026-09-06']);
});

test('nothing is pushed when nothing changed', () => {
  const totals = new Map([['2026-09-06', 30]]);
  assert.deepEqual(dirtyDays(totals, new Map([['2026-09-06', 30]])), []);
});

// ————————————————————————— opting out —————————————————————————

test('the board row is ours alone only when nothing else stands on it', () => {
  assert.equal(boardRowIsOursAlone({ sources: { web: { totalUm: 1 } } }), true);
  assert.equal(boardRowIsOursAlone({ sources: {} }), true);
  // An active phone must survive an opt-out here.
  assert.equal(
    boardRowIsOursAlone({ sources: { web: { totalUm: 1 }, android: { totalUm: 2 } } }),
    false,
  );
  // A phone on an old build has no `sources`, only legacy pixels. Still not ours to delete.
  assert.equal(boardRowIsOursAlone({ totalPixels: 5_000 }), false);
  assert.equal(boardRowIsOursAlone(null), false);
});

// ————————————————————————— wire encoding —————————————————————————

test('integers cross the wire as quoted int64, never as doubles', () => {
  // `firestore.rules` asserts `value is int`; a doubleValue is a permission-denied that
  // looks like an auth problem.
  assert.deepEqual(toValue(1_840_000), { integerValue: '1840000' });
  assert.deepEqual(toValue(0), { integerValue: '0' });
  assert.throws(() => toValue(1.5), TypeError);
});

test('a day document round-trips through the typed encoding unchanged', () => {
  const doc = buildDayDocument('2026-09-06', [
    { domain: 'youtube.com', um: 940_000 },
    { domain: 'instagram.com', um: 900_000 },
  ], NOW);
  const back = fromFields(toFields(doc));
  assert.equal(back.date, doc.date);
  assert.equal(back.source, 'web');
  assert.equal(back.um, 1_840_000);
  assert.deepEqual(back.apps, doc.apps);
  assert.equal(back.updatedAt, NOW.toISOString());
});

test('a sources entry encodes its own week and month keys', () => {
  const fields = toFields({
    sources: { web: { weekKey: WEEK, weekUm: 12, monthKey: MONTH, monthUm: 34, totalUm: 56 } },
  });
  const back = fromFields(fields);
  assert.deepEqual(back.sources.web, {
    weekKey: WEEK, weekUm: 12, monthKey: MONTH, monthUm: 34, totalUm: 56,
  });
});

// ————————————————————————— freshness wording —————————————————————————

test('syncedAgo reads the way SyncModel words it', () => {
  const now = Date.parse('2026-09-06T12:00:00Z');
  const ago = (ms) => syncedAgo(now - ms, now);
  assert.equal(syncedAgo(null, now), null);
  assert.equal(syncedAgo(0, now), null);
  assert.equal(ago(30_000), 'just now');
  assert.equal(ago(12 * 60_000), '12 min ago');
  assert.equal(ago(3 * 3_600_000), '3 h ago');
  assert.equal(ago(30 * 3_600_000), 'yesterday');
  assert.equal(ago(5 * 86_400_000), '5 days ago');
  // Clock skew must not print a negative age.
  assert.equal(syncedAgo(now + 60_000, now), 'just now');
});
