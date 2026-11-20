// What the dashboard knows right now, and the figures every section derives from it.

import { byId, show } from './dom.js';
import {
  addDays,
  addMonths,
  dailyBuckets,
  monthlyBuckets,
  mondayOf,
  monthKey,
  todayIso,
  weeklyBuckets,
} from './stats.js';

// ---------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------

/** The three history ranges, matching the phone's segmented control exactly. */
export const RANGES = [
  { label: '7 days', buckets: (days, today) => dailyBuckets(days, 7, today), start: (t) => addDays(t, -6) },
  { label: '8 weeks', buckets: (days, today) => weeklyBuckets(days, 8, today), start: (t) => addDays(mondayOf(t), -49) },
  { label: '6 months', buckets: (days, today) => monthlyBuckets(days, 6, today), start: (t) => `${addMonths(monthKey(t), -5)}-01` },
];

export const state = {
  today: todayIso(),
  user: null,
  code: '',
  row: { exists: false, data: null, fromCache: false, loaded: false },
  days: { docs: [], fromCache: false, loaded: false },
  friends: { accepted: [], pending: [], fromCache: false },
  profiles: new Map(),
  range: 0,
  view: 'today',
  boardKind: 'friends',
  periodId: 'week',
  board: { entries: [], cursor: null, exhausted: true, pages: 0, loading: false, error: null },
  myRank: null,
  podium: [],
  /** The account's shared limit, `{ limitUm, setAt }`, or null while sharing is off. */
  limit: null,
  /** Which report is showing: an index into REPORTS in report-view.js. */
  report: 0,
  groups: { list: [], loaded: false, open: null, progress: [] },
  offline: !navigator.onLine,
  /** False when IndexedDB was refused, so nothing survives this tab closing. */
  persistent: true,
  busy: false,
};
export function reportSection(id, message) {
  const node = byId(id);
  if (!node) return;
  node.textContent = message;
  show(node, Boolean(message));
}

// ---------------------------------------------------------------------------------------
// Derived data
// ---------------------------------------------------------------------------------------

/**
 * Day documents collapse two ways: totalled per date for every chart, and kept per source
 * for the phone-vs-browser split. One pass, because a user with three years of history has
 * a couple of thousand documents and this runs on every snapshot.
 */
export function derive() {
  const byDate = new Map();
  const bySource = new Map();
  for (const doc of state.days.docs) {
    byDate.set(doc.date, (byDate.get(doc.date) ?? 0) + doc.um);
    if (!bySource.has(doc.source)) bySource.set(doc.source, new Map());
    const perDay = bySource.get(doc.source);
    perDay.set(doc.date, (perDay.get(doc.date) ?? 0) + doc.um);
  }
  const active = [...byDate].filter(([, um]) => um > 0).map(([date]) => date);
  return { byDate, bySource, active };
}

export function rangeStart() {
  return RANGES[state.range].start(state.today);
}

/** True while the account has published totals but no day-by-day history to chart. */
export function historyMissing(derived) {
  return derived.byDate.size === 0 && state.row.exists;
}

/** The oval is drawn differently on a phone-width layout (DESIGN.md). */
export const PHONE_LAYOUT = window.matchMedia('(max-width: 640px)');
