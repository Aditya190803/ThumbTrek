/**
 * Day keys, the way `java.time.LocalDate` behaves on the phone.
 *
 * Every date in ThumbTrek is a `yyyy-MM-dd` string in the *user's local timezone* -- that is
 * what the Room table stores, what the Firestore day docs carry, and what the contract
 * specifies. Strings, not `Date` objects, are the currency here for two reasons:
 *
 *  - `yyyy-MM-dd` sorts and compares correctly as a plain string, so "is this day in the
 *    window" is a `<=` and never a timezone question;
 *  - a `Date` in local time crosses DST boundaries, so `date.setDate(d - 1)` can land on
 *    the same calendar day twice a year and silently break a streak.
 *
 * Arithmetic therefore runs on epoch *days* computed with `Date.UTC`, where every day is
 * exactly 86400s. Only `todayKey()` looks at the local clock, and only once.
 */

const MS_PER_DAY = 86_400_000;

/** `MMM` under Locale.US, which is what the Android labels are formatted with. */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const pad2 = (n) => String(n).padStart(2, '0');

/** The local calendar day, as a key. The only place the machine's timezone is consulted. */
export function todayKey(now = new Date()) {
  return `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}`;
}

/** Epoch day number for a key. Stable, DST-free, and cheap to compare. */
export function epochDay(key) {
  const [y, m, d] = key.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / MS_PER_DAY;
}

export function keyFromEpochDay(day) {
  const date = new Date(day * MS_PER_DAY);
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

export function addDays(key, delta) {
  return keyFromEpochDay(epochDay(key) + delta);
}

/** `DateTimeFormatter.ofPattern("MMM d", Locale.US)` -- e.g. "Aug 2", no leading zero. */
export function dayLabel(key) {
  const [, m, d] = key.split('-').map(Number);
  return `${MONTHS[m - 1]} ${d}`;
}

/** `DateTimeFormatter.ofPattern("MMM", Locale.US)` for a `yyyy-MM` month key. */
export function monthLabel(monthKey) {
  return MONTHS[Number(monthKey.split('-')[1]) - 1];
}

/** The Monday of this key's ISO week. `LocalDate.with(DayOfWeek.MONDAY)`. */
export function mondayOf(key) {
  const day = epochDay(key);
  // Epoch day 0 was a Thursday, so (day + 3) % 7 is 0 on a Monday.
  const sinceMonday = ((day + 3) % 7 + 7) % 7;
  return keyFromEpochDay(day - sinceMonday);
}

/**
 * ISO-8601 week id, e.g. "2026-W31" -- `WeekFields.ISO` on the phone. The leaderboard's
 * "weekly reset" is nothing more than this string changing, so it has to agree exactly,
 * including the week-based *year*: 2026-01-01 belongs to 2025-W53.
 */
export function weekKeyOf(key) {
  // The Thursday of this week decides which year the week belongs to -- that is the whole
  // of the ISO rule, and it avoids every off-by-one the naive "week containing Jan 4" form
  // runs into.
  const thursday = epochDay(mondayOf(key)) + 3;
  const year = new Date(thursday * MS_PER_DAY).getUTCFullYear();
  const firstThursday = epochDay(mondayOf(`${year}-01-04`)) + 3;
  const week = Math.round((thursday - firstThursday) / 7) + 1;
  return `${year}-W${pad2(week)}`;
}

/** `YearMonth.toString()`, e.g. "2026-08". */
export function monthKeyOf(key) {
  return key.slice(0, 7);
}

/** Inclusive on both ends, exactly like Kotlin's `it in monday..today`. */
export function inRange(key, from, to) {
  return key >= from && key <= to;
}
