// Port of app/src/main/java/com/thumbtrek/app/stats/Stats.kt.
//
// Same inputs, identical outputs and wording — the phone and the dashboard are one product,
// and a streak that counts differently in two places is worse than no streak at all. The
// Kotlin unit tests in app/src/test/.../StatsTest.kt are mirrored in test/stats.test.js.
//
// Two deliberate differences from the Kotlin, both forced by the contract rather than by
// JavaScript:
//
//  1. The unit is micrometres. Stats.kt sums raw device pixels because the phone knows its
//     own densityDpi; the dashboard reads `users/{uid}/days/*` which, per
//     docs/sync-protocol.md §1, only ever carries integer µm. So every bucket here holds
//     `um` where the Kotlin holds `pixels`, and metres come from [metersFromUm] rather than
//     from pixelsToMeters. There is no dpi on the web, and inventing one would invent
//     distance.
//  2. A date is the ISO string "yyyy-MM-dd", not a LocalDate. That is exactly what the day
//     documents store, so no parsing round-trip can shift a day across a timezone. All
//     arithmetic goes through UTC-midnight Dates so a DST jump cannot make a day 23 hours
//     long and silently break a streak.

/** Crockford-free plain arithmetic: µm is an integer count; a metre is 1e6 of them. */
export const UM_PER_METRE = 1_000_000;

export function metersFromUm(um) {
  return (Number(um) || 0) / UM_PER_METRE;
}

// Month names are hardcoded rather than taken from Intl: DateTimeFormatter.ofPattern("MMM",
// Locale.US) is fixed on the phone, and a visitor with a French locale must still read the
// same axis labels as the app that produced the data.
const MONTHS_SHORT = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

const MONTHS_LONG = [
  'January', 'February', 'March', 'April', 'May', 'June',
  'July', 'August', 'September', 'October', 'November', 'December',
];

const DAY_MS = 86_400_000;

function pad2(n) {
  return String(n).padStart(2, '0');
}

function toDate(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d));
}

function toIso(date) {
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

/** The *local* calendar day, which is what every client writes into a day document. */
export function todayIso(now = new Date()) {
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
}

export function addDays(iso, days) {
  const date = toDate(iso);
  date.setUTCDate(date.getUTCDate() + days);
  return toIso(date);
}

export function addMonths(monthKeyStr, months) {
  const [y, m] = monthKeyStr.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1 + months, 1));
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}`;
}

/** Monday of the ISO week containing [iso]. Weeks run Monday–Sunday everywhere in ThumbTrek. */
export function mondayOf(iso) {
  const date = toDate(iso);
  const weekday = (date.getUTCDay() + 6) % 7; // Monday = 0
  return addDays(iso, -weekday);
}

/** ISO week id like "2026-W31" — the string whose change *is* the weekly leaderboard reset. */
export function weekKey(iso) {
  const thursday = toDate(addDays(iso, 3 - ((toDate(iso).getUTCDay() + 6) % 7)));
  const year = thursday.getUTCFullYear();
  // Week 1 is the week containing 4 January, by definition.
  const jan4 = new Date(Date.UTC(year, 0, 4));
  const firstThursday = new Date(jan4);
  firstThursday.setUTCDate(jan4.getUTCDate() + 3 - ((jan4.getUTCDay() + 6) % 7));
  const week = 1 + Math.round((thursday.getTime() - firstThursday.getTime()) / (7 * DAY_MS));
  return `${year}-W${pad2(week)}`;
}

/** Month id like "2026-08". The monthly board resets the same way the weekly one does. */
export function monthKey(iso) {
  return iso.slice(0, 7);
}

/** "Jul 31" — DateTimeFormatter.ofPattern("MMM d", Locale.US). */
export function dayLabel(iso) {
  const [, m, d] = iso.split('-').map(Number);
  return `${MONTHS_SHORT[m - 1]} ${d}`;
}

/** "Aug" — ofPattern("MMM"). */
export function monthLabel(monthKeyStr) {
  return MONTHS_SHORT[Number(monthKeyStr.slice(5, 7)) - 1];
}

/** "August 2026" — ofPattern("MMMM yyyy"), the day log's month rule. */
export function monthTitle(monthKeyStr) {
  return `${MONTHS_LONG[Number(monthKeyStr.slice(5, 7)) - 1]} ${monthKeyStr.slice(0, 4)}`;
}

/** "Sun 2 Aug" — ofPattern("EEE d MMM"), used on rows in the day log. */
const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
export function longDayLabel(iso) {
  const date = toDate(iso);
  const [, m, d] = iso.split('-').map(Number);
  return `${WEEKDAYS[(date.getUTCDay() + 6) % 7]} ${d} ${MONTHS_SHORT[m - 1]}`;
}

/** Accepts a Map or a plain object of iso → µm and always hands back a Map. */
export function asDayMap(days) {
  return days instanceof Map ? days : new Map(Object.entries(days ?? {}));
}

// ---------------------------------------------------------------------------------------
// Distance
// ---------------------------------------------------------------------------------------

/**
 * Kotlin: `"%.2f".format(km).trimEnd('0').trimEnd('.')`. The trim is why 1000 m prints as
 * "1 km" and not "1.00 km", and it has to be reproduced exactly — this string ends up on
 * share cards next to the phone's.
 */
export function formatDistance(meters) {
  const value = Number(meters) || 0;
  if (value < 1000) return `${Math.round(value)} m`;
  const km = (value / 1000).toFixed(2).replace(/0+$/, '').replace(/\.$/, '');
  return `${km} km`;
}

/** Splits "1.24 km" into figure and unit so a page can set them at different sizes. */
export function distanceParts(meters) {
  const text = formatDistance(meters);
  const cut = text.lastIndexOf(' ');
  return cut < 0 ? [text, ''] : [text.slice(0, cut), text.slice(cut + 1)];
}

// ---------------------------------------------------------------------------------------
// Streaks, totals, records
// ---------------------------------------------------------------------------------------

/** Consecutive days with activity, ending today (or yesterday if today is still empty). */
export function trekStreak(activeDates, today = todayIso()) {
  const days = new Set(activeDates);
  let cursor = days.has(today) ? today : addDays(today, -1);
  let streak = 0;
  while (days.has(cursor)) {
    streak++;
    cursor = addDays(cursor, -1);
  }
  return streak;
}

export function totalThisWeek(days, today = todayIso()) {
  const monday = mondayOf(today);
  let sum = 0;
  for (const [date, um] of asDayMap(days)) {
    if (date >= monday && date <= today) sum += um;
  }
  return sum;
}

export function totalThisMonth(days, today = todayIso()) {
  const month = monthKey(today);
  let sum = 0;
  for (const [date, um] of asDayMap(days)) {
    if (monthKey(date) === month) sum += um;
  }
  return sum;
}

export function totalToday(days, today = todayIso()) {
  return asDayMap(days).get(today) ?? 0;
}

/**
 * Longest single day, or null when there is no data. Ties keep the first entry seen, which
 * is what Kotlin's `maxByOrNull` does over an ordered map.
 */
export function personalRecord(days) {
  let best = null;
  for (const [date, um] of asDayMap(days)) {
    if (best === null || um > best.um) best = { date, um };
  }
  return best;
}

// ---------------------------------------------------------------------------------------
// History buckets
// ---------------------------------------------------------------------------------------

function dayWindow(dayCount, today) {
  if (dayCount <= 0) return [];
  const window = [];
  for (let back = dayCount - 1; back >= 0; back--) window.push(addDays(today, -back));
  return window;
}

/** The last [dayCount] days, oldest first, zero-filled. */
export function dailyBuckets(days, dayCount = 7, today = todayIso()) {
  const map = asDayMap(days);
  return dayWindow(dayCount, today).map((date) => ({
    key: date,
    label: dayLabel(date),
    um: map.get(date) ?? 0,
  }));
}

/** The last [weekCount] ISO weeks (Monday–Sunday), oldest first, zero-filled. */
export function weeklyBuckets(days, weekCount = 8, today = todayIso()) {
  if (weekCount <= 0) return [];
  const map = asDayMap(days);
  const thisMonday = mondayOf(today);
  const out = [];
  for (let back = weekCount - 1; back >= 0; back--) {
    const monday = addDays(thisMonday, -7 * back);
    const sunday = addDays(monday, 6);
    let sum = 0;
    for (const [date, um] of map) {
      if (date >= monday && date <= sunday) sum += um;
    }
    out.push({ key: weekKey(monday), label: dayLabel(monday), um: sum });
  }
  return out;
}

/** The last [monthCount] calendar months, oldest first, zero-filled. */
export function monthlyBuckets(days, monthCount = 6, today = todayIso()) {
  if (monthCount <= 0) return [];
  const map = asDayMap(days);
  const thisMonth = monthKey(today);
  const out = [];
  for (let back = monthCount - 1; back >= 0; back--) {
    const month = addMonths(thisMonth, -back);
    let sum = 0;
    for (const [date, um] of map) {
      if (monthKey(date) === month) sum += um;
    }
    out.push({ key: month, label: monthLabel(month), um: sum });
  }
  return out;
}

/**
 * The per-app / per-site split, summed over a date window and busiest first.
 *
 * The phone's `appTrends` builds one zero-filled series per app for a line chart; the
 * dashboard's equivalent question is "which sites and apps did this distance come from",
 * so this collapses the same rows to one total each. Ties break on the key so the order is
 * stable between renders — the same rule `appTrends` uses.
 *
 * [dayDocs] are day documents as stored: { date, source, um, apps: { key: um } }.
 */
export function appBreakdown(dayDocs, fromIso, toIso) {
  const totals = new Map();
  for (const doc of dayDocs) {
    if (!doc?.date || doc.date < fromIso || doc.date > toIso) continue;
    for (const [key, um] of Object.entries(doc.apps ?? {})) {
      const value = Number(um);
      if (!Number.isFinite(value)) continue;
      totals.set(key, (totals.get(key) ?? 0) + value);
    }
  }
  return [...totals]
    .map(([key, um]) => ({ key, um }))
    .sort((a, b) => b.um - a.um || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}

// ---------------------------------------------------------------------------------------
// Deltas
// ---------------------------------------------------------------------------------------

/** Percent change against [previous]; null when there is no baseline to compare against. */
export function percentDelta(current, previous) {
  if (previous <= 0) return null;
  return Math.round(((current - previous) * 100) / previous);
}

/** "12% more than yesterday". */
export function dayOverDayDelta(days, today = todayIso()) {
  const map = asDayMap(days);
  return percentDelta(map.get(today) ?? 0, map.get(addDays(today, -1)) ?? 0);
}

/** This week so far against the same stretch of last week, so partial weeks are fair. */
export function weekOverWeekDelta(days, today = todayIso()) {
  const map = asDayMap(days);
  const monday = mondayOf(today);
  const lastMonday = addDays(monday, -7);
  const lastToday = addDays(today, -7);
  let current = 0;
  let previous = 0;
  for (const [date, um] of map) {
    if (date >= monday && date <= today) current += um;
    if (date >= lastMonday && date <= lastToday) previous += um;
  }
  return percentDelta(current, previous);
}

// ---------------------------------------------------------------------------------------
// Landmarks
// ---------------------------------------------------------------------------------------

/**
 * Ascending. Kept dense at the low end on purpose — the old list jumped straight from a
 * banana (0.18 m) to a basketball court (28 m), a 155× gap that left every short trek
 * reading as some large multiple of banana instead of moving on to a new object.
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
  for (const candidate of LANDMARKS) {
    if (meters >= candidate.meters) landmark = candidate;
  }
  return `That's ${(meters / landmark.meters).toFixed(1)}× ${landmark.label}.`;
}

/** The rung above [meters], for the "x to go before y" line. Null once past the Moon. */
export function nextLandmark(meters) {
  return LANDMARKS.find((l) => l.meters > meters) ?? null;
}

/** The rung already passed, so progress toward the next one can be drawn as a fraction. */
export function landmarkFloor(meters) {
  let floor = 0;
  for (const candidate of LANDMARKS) {
    if (meters >= candidate.meters) floor = candidate.meters;
  }
  return floor;
}

// ---------------------------------------------------------------------------------------
// Badges
// ---------------------------------------------------------------------------------------

function badge(id, emoji, label, value, target, detail) {
  const progress = target <= 0 ? 1 : Math.min(Math.max(value / target, 0), 1);
  return { id, emoji, label, detail, earned: progress >= 1, progress };
}

/** Local achievements computed from history alone — no server, no extra storage. Same ids, order and copy as the phone and the extension. */
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
    badge('half_marathon', '🥈', 'Half marathon', totalMeters, 21_097.0,
      'Trek 21.1 km in total'),
    badge('everest', '🏔️', 'Everest', bestDayMeters, 8_849.0, 'Trek 8.8 km in a single day'),
    badge('marathon', '🏃', 'Marathon thumb', totalMeters, 42_195.0, 'Trek 42.2 km in total'),
    badge('ultra', '🥇', 'Ultra', totalMeters, 50_000.0, 'Trek 50 km in total'),
    badge('century', '💯', 'Century', totalMeters, 100_000.0, 'Trek 100 km in total'),
    badge('quarter_million', '🚀', 'Quarter-million', totalMeters, 250_000.0,
      'Trek 250 km in total'),
    badge('half_million', '⭐', 'Half-million', totalMeters, 500_000.0, 'Trek 500 km in total'),
    badge('million', '🌟', 'Thousand-K club', totalMeters, 1_000_000.0,
      'Trek 1,000 km in total'),
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

/** Progress toward a target, phrased the way a person would say it. */
export function percentCaption(progress) {
  return `${Math.round(progress * 100)}%`;
}
