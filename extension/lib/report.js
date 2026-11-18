// Weekly and monthly reports. A port of app/src/main/java/com/thumbtrek/app/stats/Report.kt:
// the phone, the extension and the dashboard must print the same figures for the same days.
//
// This file is shared. extension/lib/report.js and web/app/report.js are byte-identical,
// and a test in each suite fails if they drift. It imports nothing for that reason.

export const REPORT_APPS = 6;

const MS_PER_DAY = 86_400_000;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTH_NAMES = [
  'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September',
  'October', 'November', 'December',
];
const WEEKDAYS = ['Thu', 'Fri', 'Sat', 'Sun', 'Mon', 'Tue', 'Wed'];

const pad2 = (n) => String(n).padStart(2, '0');

function epochDay(key) {
  const [y, m, d] = key.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / MS_PER_DAY;
}

function keyOf(day) {
  const date = new Date(day * MS_PER_DAY);
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

/** Epoch day 0 was a Thursday, so (day + 3) % 7 is 0 on a Monday. */
function mondayOf(key) {
  const day = epochDay(key);
  return keyOf(day - (((day + 3) % 7) + 7) % 7);
}

function monthStart(key, monthsBack) {
  const [y, m] = key.split('-').map(Number);
  const date = new Date(Date.UTC(y, m - 1 - monthsBack, 1));
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-01`;
}

function monthEnd(startKey) {
  const [y, m] = startKey.split('-').map(Number);
  return keyOf(Date.UTC(y, m, 1) / MS_PER_DAY - 1);
}

/** The first day of the period `offset` periods back from the one containing `today`. */
export function reportStart(span, today, offset = 0) {
  return span === 'week'
    ? keyOf(epochDay(mondayOf(today)) - 7 * offset)
    : monthStart(today, offset);
}

function reportEnd(span, start) {
  return span === 'week' ? keyOf(epochDay(start) + 6) : monthEnd(start);
}

function percentDelta(current, previous) {
  return previous <= 0 ? null : Math.round(((current - previous) * 100) / previous);
}

/** At or under the limit. A limit of zero or less is misconfiguration, never clean. */
function isCleanDay(meters, limitMeters) {
  return limitMeters > 0 && meters <= limitMeters;
}

/**
 * Builds the report for one period.
 *
 * @param span        'week' or 'month'
 * @param days        Map<'yyyy-MM-dd', Map<appOrSite, metres>>; a missing day measured nothing
 * @param options     today ('yyyy-MM-dd'), offset (0 current, 1 the one before),
 *                    limitMeters (number or null), limitSince ('yyyy-MM-dd' or null)
 */
export function report(span, days, { today, offset = 0, limitMeters = null, limitSince = null }) {
  const from = reportStart(span, today, offset);
  const end = reportEnd(span, from);
  const to = end < today ? end : today;
  const previousFrom = reportStart(span, today, offset + 1);
  const previousTo = reportEnd(span, previousFrom);

  const dayTotals = new Map();
  const appTotals = new Map();
  const previousApps = new Map();
  let total = 0;
  let previousTotal = 0;
  // Sorted, so ties for the highest and lowest day resolve to the earlier date everywhere.
  for (const date of [...days.keys()].sort()) {
    const apps = days.get(date);
    if (date >= from && date <= to) {
      let dayTotal = 0;
      for (const [key, meters] of apps) {
        dayTotal += meters;
        appTotals.set(key, (appTotals.get(key) ?? 0) + meters);
      }
      if (dayTotal > 0) dayTotals.set(date, dayTotal);
      total += dayTotal;
    } else if (date >= previousFrom && date <= previousTo) {
      for (const [key, meters] of apps) {
        previousTotal += meters;
        previousApps.set(key, (previousApps.get(key) ?? 0) + meters);
      }
    }
  }

  const elapsed = Math.max(1, epochDay(to) - epochDay(from) + 1);
  let highest = null;
  let lowest = null;
  let judgedDays = 0;
  let cleanDays = 0;
  for (const [date, meters] of dayTotals) {
    if (highest === null || meters > highest.meters) highest = { date, meters };
    if (lowest === null || meters < lowest.meters) lowest = { date, meters };
    // A day with nothing measured is not in dayTotals at all: it proves nothing either way.
    if (limitMeters !== null && (limitSince === null || date >= limitSince)) {
      judgedDays += 1;
      if (isCleanDay(meters, limitMeters)) cleanDays += 1;
    }
  }

  const apps = [...appTotals]
    .filter(([, meters]) => meters > 0)
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0))
    .slice(0, REPORT_APPS)
    .map(([key, meters]) => ({ key, meters, previousMeters: previousApps.get(key) ?? 0 }));

  return {
    span,
    from,
    to,
    running: today >= from && today < end,
    totalMeters: total,
    previousMeters: previousTotal,
    deltaPercent: percentDelta(Math.round(total), Math.round(previousTotal)),
    dailyAverageMeters: total / elapsed,
    activeDays: dayTotals.size,
    elapsedDays: elapsed,
    cleanDays: limitMeters === null ? null : cleanDays,
    judgedDays: limitMeters === null ? 0 : judgedDays,
    highest,
    lowest,
    apps,
  };
}

function shortDay(key) {
  const [, m, d] = key.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]}`;
}

/** "Sat 14 Nov", for the highest and lowest day lines. */
export function reportDayLabel(key) {
  return `${WEEKDAYS[((epochDay(key) % 7) + 7) % 7]} ${shortDay(key)}`;
}

/** "9 to 15 Nov" for a week, "November 2026" for a month. */
export function reportTitle(r) {
  if (r.span === 'month') {
    const [y, m] = r.from.split('-').map(Number);
    return `${MONTH_NAMES[m - 1]} ${y}`;
  }
  const end = keyOf(epochDay(r.from) + 6);
  return r.from.slice(5, 7) === end.slice(5, 7)
    ? `${Number(r.from.slice(8))} to ${shortDay(end)}`
    : `${shortDay(r.from)} to ${shortDay(end)}`;
}

/** "12% more than the week before", or null when there is nothing to compare against. */
export function reportDeltaText(r) {
  const noun = r.span === 'week' ? 'week' : 'month';
  if (r.deltaPercent === null) return null;
  if (r.deltaPercent > 0) return `${r.deltaPercent}% more than the ${noun} before`;
  if (r.deltaPercent < 0) return `${-r.deltaPercent}% less than the ${noun} before`;
  return `The same as the ${noun} before`;
}
