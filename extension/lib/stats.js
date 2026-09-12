/**
 * A port of `app/src/main/java/com/thumbtrek/app/stats/Stats.kt`.
 *
 * This file exists to produce *the same numbers and the same words* as the phone. A user
 * with both clients installed will put the two screens side by side, and "1.23 km / That's
 * 1.5x a school bus" here against "1.24 km / That's 1.4x a school bus" there reads as one
 * of them being broken. So:
 *
 *  - the landmark ladder, the badge ladder and their copy are transcribed verbatim, not
 *    paraphrased or "improved";
 *  - rounding goes through `formatFixed`, which reproduces Java's `%.Nf` rather than JS's
 *    `toFixed` (see units.js for why those two disagree);
 *  - the day maps are keyed by `yyyy-MM-dd` strings, which order identically to the
 *    `LocalDate` keys they stand in for.
 *
 * The one deliberate difference: the phone's maps hold raw pixels, ours hold µm, because µm
 * is the only unit the browser can honestly produce (contract §1). Every function here is
 * unit-agnostic -- it sums and compares whatever it is given -- except `formatDistance`,
 * `comparison` and `badges`, which take metres exactly as the Kotlin does.
 */

import { formatFixed } from './units.js';
import {
  addDays,
  dayLabel,
  epochDay,
  inRange,
  monthKeyOf,
  monthLabel,
  mondayOf,
  todayKey,
  weekKeyOf,
} from './dates.js';

export { weekKeyOf as weekKey, monthKeyOf as monthKey };

/** `formatDistance(meters)`. Metres under a kilometre, then km to at most 2 decimals. */
export function formatDistance(meters) {
  if (meters < 1000) return `${Math.round(meters)} m`;
  // Kotlin: %.2f, then trimEnd('0'), then trimEnd('.'). "1.00" -> "1", "1.20" -> "1.2".
  const km = formatFixed(meters / 1000, 2).replace(/0+$/, '').replace(/\.$/, '');
  return `${km} km`;
}

/** Splits "1.24 km" into figure and unit, so the two can be set at different sizes. */
export function distanceParts(meters) {
  const text = formatDistance(meters);
  const cut = text.lastIndexOf(' ');
  return cut < 0 ? [text, ''] : [text.slice(0, cut), text.slice(cut + 1)];
}

/** Consecutive days with activity, ending today (or yesterday if today is still empty). */
export function trekStreak(activeDates, today = todayKey()) {
  const days = activeDates instanceof Set ? activeDates : new Set(activeDates);
  let cursor = days.has(today) ? today : addDays(today, -1);
  let streak = 0;
  while (days.has(cursor)) {
    streak++;
    cursor = addDays(cursor, -1);
  }
  return streak;
}

const sumWhere = (days, predicate) => {
  let total = 0;
  for (const [key, value] of days) if (predicate(key)) total += value;
  return total;
};

export function totalThisWeek(days, today = todayKey()) {
  const monday = mondayOf(today);
  return sumWhere(days, (key) => inRange(key, monday, today));
}

export function totalThisMonth(days, today = todayKey()) {
  const month = monthKeyOf(today);
  return sumWhere(days, (key) => monthKeyOf(key) === month);
}

/** Longest single day, as `[dateKey, value]`, or null when there is no data. */
export function personalRecord(days) {
  let best = null;
  for (const entry of days) {
    // Strictly greater, so ties keep the *first* day seen -- same as maxByOrNull.
    if (best === null || entry[1] > best[1]) best = entry;
  }
  return best;
}

/** One column of a history chart: `key` is stable and sortable, `label` goes on the axis. */
const bucket = (key, label, value) => ({ key, label, value });

function dayWindow(dayCount, today) {
  if (dayCount <= 0) return [];
  const window = [];
  for (let back = dayCount - 1; back >= 0; back--) window.push(addDays(today, -back));
  return window;
}

/** The last `dayCount` days, oldest first, zero-filled. */
export function dailyBuckets(days, dayCount = 7, today = todayKey()) {
  return dayWindow(dayCount, today).map((key) => bucket(key, dayLabel(key), days.get(key) ?? 0));
}

/** The last `weekCount` ISO weeks (Monday-Sunday), oldest first, zero-filled. */
export function weeklyBuckets(days, weekCount = 8, today = todayKey()) {
  if (weekCount <= 0) return [];
  const thisMonday = mondayOf(today);
  const out = [];
  for (let back = weekCount - 1; back >= 0; back--) {
    const monday = addDays(thisMonday, -7 * back);
    const sunday = addDays(monday, 6);
    out.push(bucket(
      weekKeyOf(monday),
      dayLabel(monday),
      sumWhere(days, (key) => inRange(key, monday, sunday)),
    ));
  }
  return out;
}

/** The last `monthCount` calendar months, oldest first, zero-filled. */
export function monthlyBuckets(days, monthCount = 6, today = todayKey()) {
  if (monthCount <= 0) return [];
  const [year, month] = monthKeyOf(today).split('-').map(Number);
  const out = [];
  for (let back = monthCount - 1; back >= 0; back--) {
    // Date.UTC normalises an out-of-range month, so December-minus-two is November of the
    // previous year without a branch.
    const at = new Date(Date.UTC(year, month - 1 - back, 1));
    const key = `${at.getUTCFullYear()}-${String(at.getUTCMonth() + 1).padStart(2, '0')}`;
    out.push(bucket(key, monthLabel(key), sumWhere(days, (day) => monthKeyOf(day) === key)));
  }
  return out;
}

/**
 * One zero-filled daily series per site over the last `dayCount` days, busiest first.
 * `rows` are `{ date, domain, um }`, the browser's shape of a `DailyScroll`.
 */
export function siteTrends(rows, dayCount = 14, today = todayKey()) {
  const window = dayWindow(dayCount, today);
  if (window.length === 0) return [];
  const slots = new Map(window.map((key, index) => [key, index]));
  const bySite = new Map();
  for (const row of rows) {
    const slot = slots.get(row.date);
    if (slot === undefined) continue;
    if (!bySite.has(row.domain)) bySite.set(row.domain, new Array(window.length).fill(0));
    bySite.get(row.domain)[slot] += row.um;
  }
  return [...bySite.entries()]
    .map(([domain, values]) => ({ domain, values, total: values.reduce((a, b) => a + b, 0) }))
    // Busiest first, then alphabetical -- the Kotlin's compareByDescending{}.thenBy{}, and
    // the tie-break is what keeps a screen of all-zero rows from reordering on every render.
    .sort((a, b) => b.total - a.total || (a.domain < b.domain ? -1 : a.domain > b.domain ? 1 : 0))
    .map(({ domain, values }) => ({
      domain,
      points: window.map((key, index) => bucket(key, dayLabel(key), values[index])),
    }));
}

/** Percent change against `previous`; null when there is no baseline to compare against. */
export function percentDelta(current, previous) {
  if (previous <= 0) return null;
  return Math.round(((current - previous) * 100) / previous);
}

/** "12% more than yesterday". */
export function dayOverDayDelta(days, today = todayKey()) {
  return percentDelta(days.get(today) ?? 0, days.get(addDays(today, -1)) ?? 0);
}

/** This week so far against the same stretch of last week, so partial weeks are fair. */
export function weekOverWeekDelta(days, today = todayKey()) {
  const monday = mondayOf(today);
  const current = sumWhere(days, (key) => inRange(key, monday, today));
  const lastMonday = addDays(monday, -7);
  const lastToday = addDays(today, -7);
  const previous = sumWhere(days, (key) => inRange(key, lastMonday, lastToday));
  return percentDelta(current, previous);
}

/**
 * Ascending. Kept dense at the low end on purpose -- the old list jumped straight from a
 * banana (0.18 m) to a basketball court (28 m), a 155x gap that left every short trek
 * reading as some large multiple of banana instead of moving on to a new object.
 *
 * Transcribed from Stats.kt. Do not add a landmark here without adding it there.
 */
export const LANDMARKS = [
  { meters: 0.18, label: 'a banana' },
  { meters: 0.3, label: 'a sneaker' },
  { meters: 1.0, label: 'a doorway' },
  { meters: 1.8, label: 'a queen-size bed' },
  { meters: 4.5, label: 'a parked car' },
  { meters: 12.0, label: 'a school bus' },
  { meters: 19.0, label: 'a bowling lane' },
  { meters: 28.0, label: 'a basketball court' },
  { meters: 93.0, label: 'the Statue of Liberty' },
  { meters: 105.0, label: 'a football pitch' },
  { meters: 269.0, label: 'the Titanic' },
  { meters: 330.0, label: 'the Eiffel Tower' },
  { meters: 830.0, label: 'the Burj Khalifa' },
  { meters: 1_609.0, label: 'a mile' },
  { meters: 2_737.0, label: 'the Golden Gate Bridge' },
  { meters: 5_000.0, label: 'a 5K run' },
  { meters: 8_849.0, label: 'Mount Everest' },
  { meters: 21_097.0, label: 'a half marathon' },
  { meters: 42_195.0, label: 'a marathon' },
  { meters: 100_000.0, label: 'a 100K ultramarathon' },
  { meters: 384_400_000.0, label: 'the Moon' },
];

export function comparison(meters) {
  if (meters <= 0) return 'No trek yet — go scroll something.';
  let landmark = LANDMARKS[0];
  for (const candidate of LANDMARKS) if (meters >= candidate.meters) landmark = candidate;
  return `That's ${formatFixed(meters / landmark.meters, 1)}× ${landmark.label}.`;
}

function badge(id, emoji, label, value, target, detail) {
  const progress = target <= 0 ? 1 : Math.min(1, Math.max(0, value / target));
  return { id, emoji, label, detail, earned: progress >= 1, progress };
}

/**
 * Local achievements computed from history alone -- no server, no extra storage.
 * `totalMeters` is the all-time distance, `bestDayMeters` the longest single day,
 * `cleanStreak` the current under-limit run. Same ids, order and copy as Stats.kt.
 */
export function badges(totalMeters, bestDayMeters, streak, cleanStreak = 0) {
  return [
    badge('first_trek', '👣', 'First steps', totalMeters, 1.0, 'Complete your first trek'),
    badge('banana', '🍌', 'Banana', totalMeters, 100.0, 'Trek 100 m in total'),
    badge('kilometer', '🎯', 'Kilometer club', totalMeters, 1_000.0, 'Trek 1 km in total'),
    badge('bridge', '🌉', 'Golden Gate', totalMeters, 2_737.0, 'Trek 2.7 km in total'),
    badge('fivek', '🏅', '5K', totalMeters, 5_000.0, 'Trek 5 km in total'),
    badge('burj_day', '🏙️', 'Burj day', bestDayMeters, 830.0,
      'Trek 830 m — the Burj Khalifa — in a single day'),
    badge('tenk', '🥉', '10K', totalMeters, 10_000.0, 'Trek 10 km in total'),
    badge('half_marathon', '🥈', 'Half marathon', totalMeters, 21_097.0, 'Trek 21.1 km in total'),
    badge('everest', '🏔️', 'Everest', bestDayMeters, 8_849.0, 'Trek 8.8 km in a single day'),
    badge('marathon', '🏃', 'Marathon thumb', totalMeters, 42_195.0, 'Trek 42.2 km in total'),
    badge('ultra', '🥇', 'Ultra', totalMeters, 50_000.0, 'Trek 50 km in total'),
    badge('century', '💯', 'Century', totalMeters, 100_000.0, 'Trek 100 km in total'),
    badge('quarter_million', '🚀', 'Quarter-million', totalMeters, 250_000.0,
      'Trek 250 km in total'),
    badge('half_million', '⭐', 'Half-million', totalMeters, 500_000.0, 'Trek 500 km in total'),
    badge('million', '🌟', 'Thousand-K club', totalMeters, 1_000_000.0, 'Trek 1,000 km in total'),
    badge('streak_3', '🔥', 'Warm-up', streak, 3.0, 'Keep a 3-day streak'),
    badge('streak_7', '📅', 'Week trekker', streak, 7.0, 'Keep a 7-day streak'),
    badge('streak_14', '🌱', 'Two-week habit', streak, 14.0, 'Keep a 14-day streak'),
    badge('streak_30', '🗓️', 'Monthly mover', streak, 30.0, 'Keep a 30-day streak'),
    badge('streak_100', '💎', 'Centurion streak', streak, 100.0, 'Keep a 100-day streak'),
    badge('streak_365', '👑', 'Year-round trekker', streak, 365.0, 'Keep a 365-day streak'),
    badge('clean_3', '🧼', 'Clean slate', cleanStreak, 3.0,
      'Stay under your limit 3 days in a row'),
    badge('clean_7', '🛡️', 'Under control', cleanStreak, 7.0,
      'Stay under your limit 7 days in a row'),
    badge('clean_14', '🧘', 'Steady mind', cleanStreak, 14.0,
      'Stay under your limit 14 days in a row'),
    badge('clean_30', '🏵️', 'Month of restraint', cleanStreak, 30.0,
      'Stay under your limit 30 days in a row'),
  ];
}

/** Unused here but kept for parity with Surfaces.kt's caption helper. */
export function percentCaption(progress) {
  return `${Math.round(progress * 100)}%`;
}

export { epochDay };
