// The baseline week, mirrored from BaselineTest.kt so the browser and the phone suggest the
// same limit from the same days.
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  limitStreak, medianMeters, suggestedLimit, trackedDaysSince, typicalDayMeters,
} from '../lib/stats.js';
import { addDays } from '../lib/dates.js';

const today = '2026-10-26';
const week = (...meters) => new Map(meters.map((m, i) => [addDays(today, i - meters.length), m]));

test('today and zero days never count toward the baseline', () => {
  const days = week(100, 0, 200);
  days.set(today, 999);
  assert.deepEqual(trackedDaysSince(days, addDays(today, -30), today), [200, 100]);
});

test('days before the window start are ignored', () => {
  assert.deepEqual(trackedDaysSince(week(500, 100, 200), addDays(today, -2), today), [200, 100]);
});

test('median resists a single binge day', () => {
  assert.equal(medianMeters([280, 300, 320, 290, 310, 305, 5000]), 305);
  assert.equal(medianMeters([10, 20]), 15);
  assert.equal(medianMeters([]), null);
});

test('no baseline until seven tracked days exist', () => {
  assert.equal(typicalDayMeters(week(100, 100, 100, 100, 100, 100), addDays(today, -30), today), null);
  assert.equal(typicalDayMeters(week(100, 100, 100, 100, 100, 100, 100), addDays(today, -30), today), 100);
});

test('tracking-off days stretch the window instead of lowering it', () => {
  const gappy = week(300, 0, 300, 0, 300, 300, 0, 300, 300, 300);
  assert.equal(typicalDayMeters(gappy, addDays(today, -30), today), 300);
});

test('suggested limit is ten percent under, rounded to a tidy figure', () => {
  assert.equal(suggestedLimit(340), 300);
  assert.equal(suggestedLimit(45), 40);
  assert.equal(suggestedLimit(1550), 1400);
});

test('suggested limit stays inside the editor bounds', () => {
  assert.equal(suggestedLimit(2), 10);
  assert.equal(suggestedLimit(50000), 10000);
});

test('a limit set today does not judge the baseline week', () => {
  const days = week(900, 900, 900);
  days.set(today, 50);
  assert.equal(limitStreak(days, 100, today, today), 1);
});
