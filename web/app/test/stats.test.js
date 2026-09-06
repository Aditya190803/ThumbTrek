// Mirrors app/src/test/java/com/thumbtrek/app/StatsTest.kt.
//
// The numbers and strings asserted here are the ones the Kotlin test already pins, so this
// file is not "does the JavaScript agree with itself" — it is "does the JavaScript agree
// with the phone". Where a case has no Kotlin twin (the µm conversion, the per-app
// breakdown) it is marked as such.
//
//   node --test web/app/test/stats.test.js

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  addDays,
  appBreakdown,
  badges,
  comparison,
  dailyBuckets,
  dayOverDayDelta,
  formatDistance,
  landmarkFloor,
  metersFromUm,
  monthKey,
  monthlyBuckets,
  mondayOf,
  nextLandmark,
  percentDelta,
  personalRecord,
  todayIso,
  totalThisMonth,
  totalThisWeek,
  trekStreak,
  weekKey,
  weekOverWeekDelta,
  weeklyBuckets,
} from '../stats.js';

const TODAY = '2026-08-02'; // a Sunday, the same anchor date StatsTest uses
const back = (n) => addDays(TODAY, -n);

// --- the unit -----------------------------------------------------------------------

test('micrometres convert to metres', () => {
  // Contract §1: a metre is 1e6 µm, and the conversion is the only arithmetic the web
  // client is allowed to do to a distance it did not measure.
  assert.equal(metersFromUm(1_000_000), 1);
  assert.equal(metersFromUm(0), 0);
  assert.equal(metersFromUm(undefined), 0);
  assert.equal(metersFromUm(25_400), 0.0254); // one inch
});

test('distance formatting', () => {
  assert.equal(formatDistance(0), '0 m');
  assert.equal(formatDistance(340.2), '340 m');
  assert.equal(formatDistance(1000), '1 km');
  assert.equal(formatDistance(1234), '1.23 km');
  // The trimEnd('0').trimEnd('.') in Stats.kt is why these two differ from "1.20"/"1.00".
  assert.equal(formatDistance(1200), '1.2 km');
  assert.equal(formatDistance(999.6), '1000 m');
});

// --- dates --------------------------------------------------------------------------

test('todayIso reads the local calendar day', () => {
  assert.equal(todayIso(new Date(2026, 7, 2, 23, 30)), '2026-08-02');
  assert.equal(todayIso(new Date(2026, 0, 1, 0, 1)), '2026-01-01');
});

test('addDays crosses month and year boundaries', () => {
  assert.equal(addDays('2026-08-02', -3), '2026-07-30');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(addDays('2028-02-28', 1), '2028-02-29'); // leap year
});

test('weekKey matches Monday-start ISO weeks', () => {
  // Same week: Monday Jul 27 through Sunday Aug 2.
  assert.equal(weekKey('2026-07-27'), weekKey('2026-08-02'));
  // Sunday vs the following Monday: different weeks.
  assert.notEqual(weekKey('2026-08-02'), weekKey('2026-08-03'));
  assert.match(weekKey(TODAY), /^\d{4}-W\d{2}$/);
  assert.equal(weekKey('2026-07-27'), '2026-W31');
  // 2026 opens on a Thursday, so 1 Jan belongs to week 1 of 2026 and 28 Dec 2025 does not
  // belong to 2025 at all — the case that catches a naive day-of-year division.
  assert.equal(weekKey('2026-01-01'), '2026-W01');
  assert.equal(weekKey('2025-12-29'), '2026-W01');
  assert.equal(weekKey('2025-12-28'), '2025-W52');
});

test('mondayOf snaps to the start of the ISO week', () => {
  assert.equal(mondayOf('2026-08-02'), '2026-07-27'); // Sunday snaps back
  assert.equal(mondayOf('2026-07-27'), '2026-07-27'); // Monday stays
});

test('monthKey resets monthly like weekKey does weekly', () => {
  assert.equal(monthKey('2026-07-31'), '2026-07');
  assert.equal(monthKey(TODAY), '2026-08');
  assert.notEqual(monthKey('2026-07-31'), monthKey('2026-08-01'));
});

// --- streaks and totals ---------------------------------------------------------------

test('streak counts back from today', () => {
  assert.equal(trekStreak([TODAY, back(1), back(2)], TODAY), 3);
});

test('streak falls back to yesterday when today is empty', () => {
  assert.equal(trekStreak([back(1), back(2)], TODAY), 2);
});

test('streak breaks on gaps', () => {
  assert.equal(trekStreak([TODAY, back(3)], TODAY), 1);
  assert.equal(trekStreak([back(3)], TODAY), 0);
  assert.equal(trekStreak([], TODAY), 0);
});

test('week starts Monday and excludes future', () => {
  const days = new Map([
    [TODAY, 10], // Sunday
    [back(1), 20], // Saturday
    [back(6), 30], // Monday
    [back(7), 99], // last Sunday — previous week
    [addDays(TODAY, 1), 50], // future — excluded
  ]);
  assert.equal(totalThisWeek(days, TODAY), 60);
});

test('month totals stay within the month', () => {
  const days = new Map([
    [TODAY, 10],
    [back(1), 20], // Aug 1
    [back(2), 99], // Jul 31 — out
  ]);
  assert.equal(totalThisMonth(days, TODAY), 30);
});

test('personal record picks the biggest day', () => {
  const record = personalRecord(new Map([[TODAY, 5], [back(2), 50], [back(1), 20]]));
  assert.deepEqual(record, { date: back(2), um: 50 });
  assert.equal(personalRecord(new Map()), null);
});

// --- buckets --------------------------------------------------------------------------

test('daily buckets zero-fill the window and drop older days', () => {
  const days = new Map([
    [TODAY, 10], // Aug 2
    [back(2), 30], // Jul 31
    [back(9), 99], // outside the window
  ]);
  assert.deepEqual(dailyBuckets(days, 3, TODAY), [
    { key: '2026-07-31', label: 'Jul 31', um: 30 },
    { key: '2026-08-01', label: 'Aug 1', um: 0 },
    { key: '2026-08-02', label: 'Aug 2', um: 10 },
  ]);
});

test('daily buckets survive empty and degenerate windows', () => {
  assert.deepEqual(dailyBuckets(new Map(), 0, TODAY), []);
  assert.deepEqual(dailyBuckets(new Map([[TODAY, 10]]), -1, TODAY), []);
  assert.deepEqual(dailyBuckets(new Map(), 1, TODAY), [
    { key: '2026-08-02', label: 'Aug 2', um: 0 },
  ]);
});

test('weekly buckets group Monday through Sunday', () => {
  const days = new Map([
    [TODAY, 10], // Sun Aug 2 — this week
    [back(6), 20], // Mon Jul 27 — this week
    [back(7), 5], // Sun Jul 26 — previous week
    [back(21), 99], // Sun Jul 12 — outside the window
  ]);
  assert.deepEqual(weeklyBuckets(days, 3, TODAY), [
    { key: '2026-W29', label: 'Jul 13', um: 0 },
    { key: '2026-W30', label: 'Jul 20', um: 5 },
    { key: '2026-W31', label: 'Jul 27', um: 30 },
  ]);
});

test('weekly buckets roll over on the ISO week boundary', () => {
  const days = new Map([['2026-08-02', 7]]);
  assert.deepEqual(weeklyBuckets(days, 1, '2026-08-02'), [
    { key: '2026-W31', label: 'Jul 27', um: 7 },
  ]);
  assert.deepEqual(weeklyBuckets(days, 1, '2026-08-03'), [
    { key: '2026-W32', label: 'Aug 3', um: 0 },
  ]);
  assert.deepEqual(weeklyBuckets(days, 0, '2026-08-02'), []);
});

test('monthly buckets cross the year boundary', () => {
  const days = new Map([
    ['2025-10-31', 99], // outside the window
    ['2025-12-31', 40],
    ['2026-01-01', 2],
    ['2026-01-15', 3],
  ]);
  assert.deepEqual(monthlyBuckets(days, 3, '2026-01-15'), [
    { key: '2025-11', label: 'Nov', um: 0 },
    { key: '2025-12', label: 'Dec', um: 40 },
    { key: '2026-01', label: 'Jan', um: 5 },
  ]);
  assert.deepEqual(monthlyBuckets(days, 0, '2026-01-15'), []);
});

// --- deltas ---------------------------------------------------------------------------

test('percent delta needs a baseline', () => {
  assert.equal(percentDelta(340, 300), 13);
  assert.equal(percentDelta(150, 300), -50);
  assert.equal(percentDelta(0, 300), -100);
  assert.equal(percentDelta(500, 0), null);
  assert.equal(percentDelta(0, 0), null);
});

test('day over day compares today with yesterday', () => {
  assert.equal(dayOverDayDelta(new Map([[TODAY, 340], [back(1), 300]]), TODAY), 13);
  assert.equal(dayOverDayDelta(new Map([[back(1), 300]]), TODAY), -100);
  assert.equal(dayOverDayDelta(new Map([[TODAY, 340]]), TODAY), null);
  assert.equal(dayOverDayDelta(new Map(), TODAY), null);
});

test('week over week compares the same stretch of last week', () => {
  const wednesday = '2026-07-29';
  const days = new Map([
    ['2026-07-27', 10], // Mon, this week
    [wednesday, 10], // Wed, this week
    ['2026-07-30', 99], // Thu, still ahead — excluded
    ['2026-07-22', 10], // Wed, last week
    ['2026-07-26', 99], // Sun, last week but past the mirrored cutoff
  ]);
  assert.equal(weekOverWeekDelta(days, wednesday), 100);
  assert.equal(weekOverWeekDelta(new Map([[wednesday, 10]]), wednesday), null);
});

// --- landmarks ------------------------------------------------------------------------

test('comparisons scale with distance', () => {
  assert.equal(comparison(0), 'No trek yet — go scroll something.');
  assert.match(comparison(0.25), /banana/);
  assert.match(comparison(12.4), /school bus/);
  assert.match(comparison(1000), /Burj Khalifa/);
  assert.match(comparison(50000), /marathon/);
  // The exact sentence, not just the noun: it appears next to the phone's on a share card.
  assert.equal(comparison(1660), "That's 1.0× a mile.");
});

test('the landmark ladder gives progress something to aim at', () => {
  assert.equal(nextLandmark(0.2).label, 'a sneaker');
  assert.equal(landmarkFloor(0.2), 0.18);
  assert.equal(landmarkFloor(0.01), 0);
  assert.equal(nextLandmark(4e8), null); // past the Moon there is nothing left
});

// --- badges ---------------------------------------------------------------------------

test('badges earn at their milestones', () => {
  const all = badges(1_000_000, 9_000, 365);
  assert.ok(all.every((b) => b.earned));
  assert.equal(badges(0, 0, 0).length, all.length);
  assert.ok(badges(0, 0, 0).every((b) => !b.earned));
});

test('badge progress clamps to one', () => {
  const halfWay = badges(1_368.5, 0, 0).find((b) => b.id === 'bridge');
  assert.equal(halfWay.progress, 0.5);
  assert.equal(halfWay.earned, false);
  const over = badges(500_000, 0, 0).find((b) => b.id === 'bridge');
  assert.equal(over.progress, 1);
  assert.equal(over.earned, true);
});

// --- the per-app / per-site split (web only; the phone charts trends instead) -----------

test('the breakdown sums apps across sources inside the window', () => {
  const docs = [
    { date: '2026-08-02', source: 'android', um: 30, apps: { 'com.instagram.android': 30 } },
    { date: '2026-08-02', source: 'web', um: 50, apps: { 'youtube.com': 50 } },
    { date: '2026-08-01', source: 'web', um: 20, apps: { 'youtube.com': 20 } },
    { date: '2026-07-01', source: 'web', um: 999, apps: { 'youtube.com': 999 } }, // out of range
  ];
  assert.deepEqual(appBreakdown(docs, '2026-08-01', '2026-08-02'), [
    { key: 'youtube.com', um: 70 },
    { key: 'com.instagram.android', um: 30 },
  ]);
});

test('the breakdown breaks ties on the key and tolerates missing apps maps', () => {
  const docs = [
    { date: '2026-08-02', source: 'web', um: 0, apps: { 'b.example': 5, 'a.example': 5 } },
    { date: '2026-08-02', source: 'android', um: 5 }, // a day with no breakdown at all
  ];
  assert.deepEqual(appBreakdown(docs, '2026-08-01', '2026-08-02'), [
    { key: 'a.example', um: 5 },
    { key: 'b.example', um: 5 },
  ]);
  assert.deepEqual(appBreakdown([], '2026-08-01', '2026-08-02'), []);
});
