// The History section: the range chart, sources, per-app split, day log and records.

import { sourceLabel, sourceSplit, syncedAgo } from './board.js';
import { progressRail, stackedBarChart } from './charts.js';
import { currentKeys } from './data.js';
import { byId, el, fill, loadingSkeleton, show } from './dom.js';
import { RANGES, historyMissing, rangeStart, state } from './state.js';
import {
  appBreakdown,
  badges,
  formatDistance,
  longDayLabel,
  metersFromUm,
  monthTitle,
  monthKey,
  percentCaption,
  personalRecord,
  trekStreak,
  weekKey,
  weeklyBuckets,
} from './stats.js';
import { SOURCE_SLOTS, measureRow, sourceSlot } from './today-view.js';

export function renderHistory(derived) {
  const { byDate, bySource } = derived;
  const range = RANGES[state.range];

  const empty = byId('history-empty');
  const body = byId('history-body');
  // "No history" and "the history has not arrived yet" look identical and mean opposite
  // things, so the first snapshot has to land before either sentence is allowed on screen.
  if (!state.days.loaded) {
    show(body, false);
    // After a failure the error above says it all; a skeleton under it would promise data.
    show(empty, !state.days.failed);
    fill(empty, loadingSkeleton('Reading your history'));
    return;
  }
  if (byDate.size === 0) {
    show(body, false);
    show(empty, true);
    empty.textContent = historyMissing(derived)
      ? 'This account has published totals but no day-by-day history yet. The daily ' +
        'record arrives with the next sync from your phone or browser. Until then the ' +
        'totals are all there is to chart.'
      : 'Nothing logged yet. Once tracking has run for a day, this fills with daily ' +
        'totals, the split between your devices, and where the scrolling happened.';
    return;
  }
  show(empty, false);
  show(body, true);

  // One column per period, one segment per source. Each source is bucketed on its own so
  // the segments add up to the column exactly.
  const buckets = range.buckets(byDate, state.today);
  const sources = [...bySource.keys()].sort((a, b) => sourceSlot(a) - sourceSlot(b) || (a < b ? -1 : 1));
  const extra = sources.filter((source) => !(source in SOURCE_SLOTS));
  const series = sources.map((source) => ({
    label: sourceLabel(source),
    slot: sourceSlot(source, extra.indexOf(source)),
  }));
  const perSource = sources.map((source) => range.buckets(bySource.get(source), state.today));
  const stacked = buckets.map((bucket, index) => ({
    ...bucket,
    parts: perSource.map((list) => list[index]?.um ?? 0),
  }));
  fill(
    byId('history-chart'),
    stackedBarChart(stacked, series, {
      format: (um) => formatDistance(metersFromUm(um)),
      caption: `Distance over the last ${range.label}`,
    }),
  );

  renderSources(bySource);
  renderBreakdown();
  renderDayLog(byDate);
}

/**
 * Phone vs browser. This is the reason to open the dashboard at all rather than the app:
 * the day ledger is keyed by source, so it is the only place the split exists.
 */
function renderSources(bySource) {
  const from = rangeStart();
  const to = state.today;
  const rows = [];
  for (const [source, perDay] of bySource) {
    let um = 0;
    for (const [date, value] of perDay) {
      if (date >= from && date <= to) um += value;
    }
    rows.push({ source, um });
  }
  rows.sort((a, b) => b.um - a.um || (a.source < b.source ? -1 : 1));

  const total = rows.reduce((sum, row) => sum + row.um, 0);
  const peak = Math.max(...rows.map((r) => r.um), 0);
  const target = byId('sources-rows');
  const extra = rows.map((r) => r.source).filter((source) => !(source in SOURCE_SLOTS));

  if (!rows.length || total === 0) {
    fill(
      target,
      el('li', { class: 'row fine', text: 'No scrolling recorded from any device in this range.' }),
    );
  } else {
    fill(
      target,
      rows.map((row) =>
        measureRow({
          label: sourceLabel(row.source),
          value: formatDistance(metersFromUm(row.um)),
          note: total > 0 ? `${Math.round((row.um / total) * 100)}%` : null,
          fraction: peak > 0 ? row.um / peak : 0,
          slot: sourceSlot(row.source, extra.indexOf(row.source)),
        }),
      ),
    );
  }

  // A single-source account is the normal case, not an error. Say which one is missing
  // rather than drawing an empty second bar.
  const known = new Set(rows.filter((r) => r.um > 0).map((r) => r.source));
  const missing = ['android', 'web'].filter((s) => !known.has(s));
  const note = byId('sources-note');
  if (missing.length === 1 && known.size) {
    note.textContent =
      missing[0] === 'web'
        ? 'Nothing from a browser yet. Every metre in this range came from the phone.'
        : 'Nothing from the phone in this range. This is all browser scrolling.';
    show(note, true);
  } else {
    show(note, false);
  }

  // What each client last PUBLISHED, as opposed to what it recorded. The two answer
  // different questions: the rows above come from the day ledger, this comes from the
  // board row, and a client that stopped syncing is only visible here.
  //
  // Read through the same per-source gate the rollup uses: a source whose own weekKey is
  // not this week contributes nothing to this week, so it says so rather than showing last
  // week's figure under this week's heading. An absent `sources` block (an older phone) or
  // a single entry is normal, not a fault.
  const published = sourceSplit(state.row.data, 'week', currentKeys(state.today));
  const sync = byId('sources-sync');
  if (published.length) {
    fill(
      sync,
      published.map((entry) => {
        const ago = syncedAgo(entry.updatedAt);
        const when = ago ? `Synced ${ago}` : 'Never synced';
        return el('li', {
          text: entry.stale
            ? `${sourceLabel(entry.source)} has published nothing for this week. ${when}.`
            : `${sourceLabel(entry.source)} published ${formatDistance(entry.meters)} for ` +
              `this week. ${when}.`,
        });
      }),
    );
    show(sync, true);
  } else {
    show(sync, false);
  }
}

/** Per-site and per-app: the `apps` map on each day document, summed over the range. */
function renderBreakdown() {
  const rows = appBreakdown(state.days.docs, rangeStart(), state.today);
  const target = byId('breakdown-rows');
  if (!rows.length) {
    fill(
      target,
      el('li', {
        class: 'lanes-note fine',
        text: 'No per-app breakdown in this range. Older day records carry a daily total ' +
          'without the split.',
      }),
    );
    byId('breakdown-count').textContent = '';
    return;
  }
  const total = rows.reduce((sum, row) => sum + row.um, 0);
  byId('breakdown-count').textContent = `${rows.length}`;
  // A ranked list, so it carries lane numbers (DESIGN.md).
  fill(
    target,
    rows.map((row, index) =>
      el(
        'li',
        {},
        el('span', { class: 'lane-no', text: `${index + 1}` }),
        el(
          'span',
          { class: 'lane-who' },
          row.key,
          total > 0
            ? el('span', { class: 'row-note num', text: `${Math.round((row.um / total) * 100)}%` })
            : null,
        ),
        el('span', { class: 'lane-dist', text: formatDistance(metersFromUm(row.um)) }),
      ),
    ),
  );
}

function renderDayLog(byDate) {
  const dates = [...byDate.keys()].sort().reverse();
  const peak = Math.max(...byDate.values(), 0);
  const target = byId('daylog-rows');
  byId('daylog-count').textContent = `${dates.length} ${dates.length === 1 ? 'day' : 'days'}`;
  let month = null;
  const nodes = [];
  for (const date of dates) {
    const thisMonth = monthKey(date);
    if (thisMonth !== month) {
      month = thisMonth;
      nodes.push(el('h3', { class: 'daylog-month', text: monthTitle(thisMonth) }));
    }
    const um = byDate.get(date);
    nodes.push(
      measureRow({
        tag: 'div',
        label: date === state.today ? `${longDayLabel(date)}, today` : longDayLabel(date),
        value: formatDistance(metersFromUm(um)),
        fraction: peak > 0 ? um / peak : 0,
        current: date === state.today,
      }),
    );
  }
  fill(target, nodes);
}

export function renderRecords({ byDate, active }) {
  if (!state.days.loaded) {
    show(byId('streak'), false);
    fill(
      byId('records-rows'),
      state.days.failed
        ? null
        : el('li', { class: 'row' }, loadingSkeleton('Reading your records', [70, 55, 62])),
    );
    return;
  }
  show(byId('streak'), true);
  const streak = trekStreak(active, state.today);
  const record = personalRecord(byDate);
  const bestWeek = weeklyBuckets(byDate, 52, state.today).reduce(
    (best, bucket) => (best && best.um >= bucket.um ? best : bucket),
    null,
  );
  const allUm = [...byDate.values()].reduce((sum, um) => sum + um, 0);

  const streakNode = byId('streak');
  streakNode.textContent =
    streak > 0
      ? `${streak} ${streak === 1 ? 'day' : 'days'} in a row with scrolling tracked`
      : 'No streak yet. A day with any tracked scrolling starts one.';
  streakNode.className = streak > 0 ? 'streak streak-live' : 'streak';

  fill(
    byId('records-rows'),
    record && record.um > 0
      ? recordRow('Longest single day', formatDistance(metersFromUm(record.um)), longDayLabel(record.date))
      : null,
    bestWeek && bestWeek.um > 0
      ? recordRow('Longest week', formatDistance(metersFromUm(bestWeek.um)), `Week of ${bestWeek.label}`)
      : null,
    recordRow(
      'Current streak',
      streak > 0 ? `${streak} ${streak === 1 ? 'day' : 'days'}` : 'None',
      streak > 0 ? 'Days in a row with tracked scrolling' : 'Starts on the next tracked day',
    ),
  );

  // The clean-day badges are left out rather than shown at 0%. A daily limit is per-device
  // and never synced (PRD §11.7), so this page cannot know whether a day was clean, and a
  // row of "stay under your limit" tiles stuck at zero would pass that judgement on every
  // visitor, including people in baseline mode who never set a limit at all.
  const earned = badges(
    metersFromUm(allUm),
    metersFromUm(record?.um ?? 0),
    streak,
  )
    .filter((badge) => !badge.id.startsWith('clean_'))
    .sort((a, b) => Number(b.earned) - Number(a.earned) || b.progress - a.progress);

  byId('badge-count').textContent = `${earned.filter((b) => b.earned).length} of ${earned.length}`;
  fill(
    byId('badges'),
    earned.map((badge) =>
      el(
        'li',
        { class: badge.earned ? 'row badge badge-earned' : 'row badge' },
        el(
          'div',
          { class: 'row-head' },
          el('span', { class: 'row-label', text: badge.label }),
          el('span', {
            class: 'row-value',
            text: badge.earned ? 'Reached' : percentCaption(badge.progress),
          }),
        ),
        el('p', { class: 'fine', text: badge.detail }),
        badge.earned ? null : progressRail(badge.progress),
      ),
    ),
  );

  const next = earned.find((b) => !b.earned);
  const nextNode = byId('badge-next');
  if (next) {
    nextNode.textContent = `Next: ${next.label}, ${percentCaption(next.progress)} of the way there.`;
    show(nextNode, true);
  } else {
    show(nextNode, false);
  }
}

function recordRow(label, value, caption) {
  return el(
    'li',
    { class: 'row' },
    el(
      'div',
      { class: 'row-head' },
      el('span', { class: 'row-label', text: label }),
      el('span', { class: 'row-value num', text: value }),
    ),
    el('p', { class: 'fine', text: caption }),
  );
}
