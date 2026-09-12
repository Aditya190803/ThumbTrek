/**
 * The parity suite. Every expectation here is lifted from
 * `app/src/test/java/com/thumbtrek/app/StatsTest.kt` -- same inputs, same answers. When the
 * two files disagree, one of the two clients is showing a user the wrong number.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  badges, comparison, dailyBuckets, dayOverDayDelta, distanceParts, formatDistance, monthKey,
  monthlyBuckets, percentDelta, personalRecord, siteTrends, totalThisMonth, totalThisWeek,
  trekStreak, weekKey, weeklyBuckets, weekOverWeekDelta,
} from '../lib/stats.js';
import { addDays, dayLabel, mondayOf, todayKey } from '../lib/dates.js';

const TODAY = '2026-08-02'; // a Sunday, exactly as in StatsTest
const back = (n) => addDays(TODAY, -n);

test('distance formatting matches the Kotlin', () => {
  assert.equal(formatDistance(0), '0 m');
  assert.equal(formatDistance(340.2), '340 m');
  assert.equal(formatDistance(1000), '1 km');
  assert.equal(formatDistance(1234), '1.23 km');
});

test('distance parts split the figure from the unit', () => {
  assert.deepEqual(distanceParts(1234), ['1.23', 'km']);
  assert.deepEqual(distanceParts(12), ['12', 'm']);
});

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

test('a streak survives a month boundary and a leap day', () => {
  // The bug a naive `date - 1` has: February 29th 2028 exists, and March 1st must reach it.
  assert.equal(trekStreak(['2028-03-01', '2028-02-29', '2028-02-28'], '2028-03-01'), 3);
});

test('week starts Monday and excludes the future', () => {
  const days = new Map([
    [TODAY, 10], // Sunday
    [back(1), 20], // Saturday
    [back(6), 30], // Monday
    [back(7), 99], // last Sunday -- previous week
    [addDays(TODAY, 1), 50], // future -- excluded
  ]);
  assert.equal(totalThisWeek(days, TODAY), 60);
});

test('month totals stay within the month', () => {
  const days = new Map([[TODAY, 10], [back(1), 20], [back(2), 99]]); // back(2) is Jul 31
  assert.equal(totalThisMonth(days, TODAY), 30);
});

test('personal record picks the biggest day', () => {
  const best = back(2);
  assert.deepEqual(
    personalRecord(new Map([[TODAY, 5], [best, 50], [back(1), 20]])),
    [best, 50],
  );
  assert.equal(personalRecord(new Map()), null);
});

test('weekKey matches Monday-start ISO weeks', () => {
  assert.equal(weekKey('2026-07-27'), weekKey('2026-08-02'));
  assert.notEqual(weekKey('2026-08-02'), weekKey('2026-08-03'));
  assert.match(weekKey(TODAY), /^\d{4}-W\d{2}$/);
  assert.equal(weekKey('2026-08-02'), '2026-W31');
});

test('weekKey uses the ISO week-based year at a new year', () => {
  // 2026-01-01 is a Thursday, so its week belongs to 2026 even though it starts in 2025.
  assert.equal(weekKey('2026-01-01'), '2026-W01');
  assert.equal(weekKey('2025-12-31'), '2026-W01');
  // 2027-01-01 is a Friday, so that week belongs to 2026.
  assert.equal(weekKey('2027-01-01'), '2026-W53');
});

test('mondayOf lands on Monday for every day of a week', () => {
  for (let offset = 0; offset < 7; offset++) {
    assert.equal(mondayOf(addDays('2026-07-27', offset)), '2026-07-27');
  }
});

test('comparisons scale with distance', () => {
  assert.equal(comparison(0), 'No trek yet — go scroll something.');
  assert.match(comparison(0.25), /banana/);
  assert.match(comparison(12.4), /school bus/);
  assert.match(comparison(1000), /Burj Khalifa/);
  assert.match(comparison(50000), /marathon/);
  // Wording, not just the landmark: the phone says exactly this.
  assert.equal(comparison(12.4), "That's 1.0× a school bus.");
  // The ladder moves on rather than reporting a big multiple of a small thing: 24 m is
  // past the bowling lane (19 m), so it is not "2.0× a school bus".
  assert.equal(comparison(24), "That's 1.3× a bowling lane.");
  // Below the smallest landmark it stays on the banana rather than dividing by nothing.
  assert.equal(comparison(0.09), "That's 0.5× a banana.");
});

test('comparison rounds its multiplier the way the phone does', () => {
  // 1.15 m against the 1 m doorway is a decimal tie: toFixed(1) prints "1.1", Java's
  // %.1f prints "1.2". This is the difference showing up in a sentence a user reads.
  assert.equal((1.15).toFixed(1), '1.1');
  assert.equal(comparison(1.15), "That's 1.2× a doorway.");
});

test('daily buckets zero-fill the window and drop older days', () => {
  const days = new Map([[TODAY, 10], [back(2), 30], [back(9), 99]]);
  assert.deepEqual(dailyBuckets(days, 3, TODAY), [
    { key: '2026-07-31', label: 'Jul 31', value: 30 },
    { key: '2026-08-01', label: 'Aug 1', value: 0 },
    { key: '2026-08-02', label: 'Aug 2', value: 10 },
  ]);
});

test('daily buckets survive empty and degenerate windows', () => {
  assert.deepEqual(dailyBuckets(new Map(), 0, TODAY), []);
  assert.deepEqual(dailyBuckets(new Map([[TODAY, 10]]), -1, TODAY), []);
  assert.deepEqual(dailyBuckets(new Map(), 1, TODAY), [
    { key: '2026-08-02', label: 'Aug 2', value: 0 },
  ]);
});

test('weekly buckets group Monday through Sunday', () => {
  const days = new Map([[TODAY, 10], [back(6), 20], [back(7), 5], [back(21), 99]]);
  assert.deepEqual(weeklyBuckets(days, 3, TODAY), [
    { key: '2026-W29', label: 'Jul 13', value: 0 },
    { key: '2026-W30', label: 'Jul 20', value: 5 },
    { key: '2026-W31', label: 'Jul 27', value: 30 },
  ]);
});

test('weekly buckets roll over on the ISO week boundary', () => {
  const days = new Map([['2026-08-02', 7]]);
  assert.deepEqual(weeklyBuckets(days, 1, '2026-08-02'), [
    { key: '2026-W31', label: 'Jul 27', value: 7 },
  ]);
  assert.deepEqual(weeklyBuckets(days, 1, '2026-08-03'), [
    { key: '2026-W32', label: 'Aug 3', value: 0 },
  ]);
  assert.deepEqual(weeklyBuckets(days, 0, '2026-08-02'), []);
});

test('monthly buckets cross the year boundary', () => {
  const days = new Map([
    ['2025-10-31', 99], ['2025-12-31', 40], ['2026-01-01', 2], ['2026-01-15', 3],
  ]);
  assert.deepEqual(monthlyBuckets(days, 3, '2026-01-15'), [
    { key: '2025-11', label: 'Nov', value: 0 },
    { key: '2025-12', label: 'Dec', value: 40 },
    { key: '2026-01', label: 'Jan', value: 5 },
  ]);
  assert.deepEqual(monthlyBuckets(days, 0, '2026-01-15'), []);
});

test('site trends zero-fill days and rank the busiest site first', () => {
  const rows = [
    { domain: 'instagram.com', date: '2026-08-02', um: 10 },
    { domain: 'instagram.com', date: '2026-07-31', um: 5 },
    { domain: 'youtube.com', date: '2026-08-01', um: 50 },
    { domain: 'youtube.com', date: '2026-07-01', um: 999 }, // outside the window
    { domain: 'reddit.com', date: '2026-07-01', um: 7 }, // never inside the window
  ];
  const trends = siteTrends(rows, 3, TODAY);
  assert.deepEqual(trends.map((t) => t.domain), ['youtube.com', 'instagram.com']);
  assert.deepEqual(trends[0].points.map((p) => p.value), [0, 50, 0]);
  assert.deepEqual(trends[1].points.map((p) => p.value), [5, 0, 10]);
  assert.deepEqual(trends[1].points.map((p) => p.key), ['2026-07-31', '2026-08-01', '2026-08-02']);
  assert.deepEqual(trends[1].points.map((p) => p.label), ['Jul 31', 'Aug 1', 'Aug 2']);
});

test('site trends tie-break by domain so an all-zero list stops reordering', () => {
  const rows = [
    { domain: 'x.com', date: '2026-08-02', um: 0 },
    { domain: 'instagram.com', date: '2026-08-02', um: 0 },
  ];
  assert.deepEqual(siteTrends(rows, 2, TODAY).map((t) => t.domain), ['instagram.com', 'x.com']);
});

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
    ['2026-07-30', 99], // Thu, still ahead -- excluded
    ['2026-07-22', 10], // Wed, last week
    ['2026-07-26', 99], // Sun, last week but past the mirrored cutoff
  ]);
  assert.equal(weekOverWeekDelta(days, wednesday), 100);
  assert.equal(weekOverWeekDelta(new Map([[wednesday, 10]]), wednesday), null);
});

test('badges earn at their milestones', () => {
  const all = badges(1_000_000, 9_000, 365, 30);
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

test('the badge ladder is the same list, in the same order, as the phone', () => {
  assert.deepEqual(badges(0, 0, 0).map((b) => b.id), [
    'first_trek', 'banana', 'kilometer', 'bridge', 'fivek', 'burj_day', 'tenk',
    'half_marathon', 'everest', 'marathon', 'ultra', 'century', 'quarter_million',
    'half_million', 'million', 'streak_3', 'streak_7', 'streak_14', 'streak_30',
    'streak_100', 'streak_365', 'clean_3', 'clean_7', 'clean_14', 'clean_30',
  ]);
});

test('clean badges track the under-limit run, not the trek streak', () => {
  const week = badges(0, 0, 0, 7);
  assert.equal(week.find((b) => b.id === 'clean_3').earned, true);
  assert.equal(week.find((b) => b.id === 'clean_7').earned, true);
  assert.equal(week.find((b) => b.id === 'clean_14').earned, false);
  const trekker = badges(1_000_000, 9_000, 365, 0);
  assert.ok(trekker.filter((b) => b.id.startsWith('clean_')).every((b) => !b.earned));
});

test('monthKey resets monthly like weekKey does weekly', () => {
  assert.equal(monthKey('2026-07-31'), '2026-07');
  assert.equal(monthKey(TODAY), '2026-08');
  assert.notEqual(monthKey('2026-07-31'), monthKey('2026-08-01'));
});

test('day labels use the Locale.US month abbreviations', () => {
  assert.equal(dayLabel('2026-09-06'), 'Sep 6');
  assert.equal(dayLabel('2026-01-31'), 'Jan 31');
});

test('todayKey reads the local calendar day, not UTC', () => {
  // Constructed in local time; the key must be that same local date whatever the offset.
  const local = new Date(2026, 8, 6, 23, 30); // 6 Sep 2026, 23:30 local
  assert.equal(todayKey(local), '2026-09-06');
});
