// The Today section: the lap oval, its lane rows, and the figures beside them.

import { lapOval, progressRail } from './charts.js';
import { byId, el, fill, loadingSkeleton, show } from './dom.js';
import { ovalDistance, ovalModel } from './oval.js';
import { sharedLimitMetres } from './report-view.js';
import { PHONE_LAYOUT, state } from './state.js';
import {
  appBreakdown,
  comparison,
  dayOverDayDelta,
  distanceParts,
  formatDistance,
  landmarkFloor,
  metersFromUm,
  mondayOf,
  monthKey,
  nextLandmark,
  weekOverWeekDelta,
} from './stats.js';

/** Which series colour a source keeps, whatever the range or its rank (DESIGN.md). */
export const SOURCE_SLOTS = { android: 1, web: 2 };

export function sourceSlot(source, fallbackIndex = 0) {
  return SOURCE_SLOTS[source] ?? Math.min(3 + fallbackIndex, 6);
}

export function renderToday({ byDate, active }) {
  const today = state.today;
  const oval = byId('oval');
  const grid = byId('totals-grid');
  const phone = PHONE_LAYOUT.matches;
  // Until the first day snapshot lands, "0 m" would be a claim about today rather than an
  // admission that the page has not heard yet. The track is drawn empty and says so.
  if (!state.days.loaded) {
    oval.setAttribute('aria-busy', String(!state.days.failed));
    grid.setAttribute('aria-busy', String(!state.days.failed));
    fill(
      byId('oval-art'),
      lapOval(ovalModel(), { phone, label: 'An empty track. Today has not loaded yet.' }),
    );
    byId('today-figure').textContent = '';
    byId('today-unit').textContent = '';
    byId('today-caption').textContent = state.days.failed
      ? 'Today could not be read'
      : 'Reading today';
    fill(byId('today-lanes'), null);
    byId('today-compare').textContent = state.days.failed
      ? 'Your history could not be read. The reason is at the top of this page.'
      : '';
    show(byId('today-goal'), false);
    show(byId('today-delta'), false);
    show(byId('today-scale'), false);
    fill(grid, state.days.failed ? null : loadingSkeleton('Reading your totals', [60, 60, 60, 60]));
    return;
  }
  oval.removeAttribute('aria-busy');
  grid.removeAttribute('aria-busy');

  const todayUm = byDate.get(today) ?? 0;
  let weekUm = 0;
  let monthUm = 0;
  let allUm = 0;
  const monday = mondayOf(today);
  const month = monthKey(today);
  for (const [date, um] of byDate) {
    allUm += um;
    if (date >= monday && date <= today) weekUm += um;
    if (monthKey(date) === month) monthUm += um;
  }

  // A limit lives on each device unless the account shares one (§7). Only a shared limit
  // reaches this page; without one the track uses the no-limit scale and counts laps.
  const meters = metersFromUm(todayUm);
  const limitMetres = sharedLimitMetres();
  const apps = appBreakdown(state.days.docs, today, today).map((row) => ({
    name: row.key,
    metres: metersFromUm(row.um),
  }));
  const model = ovalModel({ totalMetres: meters, apps, limitMetres });
  renderLimitNote(limitMetres);
  const laneWords = model.lanes
    .map((lane) => `${lane.name} ${ovalDistance(lane.metres)}`)
    .join(', ');
  fill(
    byId('oval-art'),
    lapOval(model, {
      phone,
      label: `Today drawn as a running track. ${laneWords}. ${model.caption}.`,
    }),
  );

  const [figure, unit] = distanceParts(meters);
  byId('today-figure').textContent = figure;
  byId('today-unit').textContent = unit;
  byId('today-caption').textContent = model.caption;

  fill(
    byId('today-lanes'),
    model.lanes.map((lane) =>
      el(
        'li',
        {},
        el('span', { class: 'lane-no', text: `${lane.lane}` }),
        el('span', {
          class: 'lane-name',
          style: lane.lane === 1 ? null : `--swatch: var(--lane-${lane.lane})`,
          text: lane.name,
        }),
        el('span', { class: 'lane-dist', text: ovalDistance(lane.metres) }),
      ),
    ),
  );

  const scale = byId('today-scale');
  scale.textContent = `One full lap of this track is ${ovalDistance(model.scaleMetres)}.`;
  show(scale, true);

  byId('today-compare').textContent = comparison(meters);

  // Progress to the next landmark: a reason to look again tomorrow.
  const next = nextLandmark(meters);
  const goal = byId('today-goal');
  if (next && meters > 0) {
    const floor = landmarkFloor(meters);
    const span = Math.max(next.meters - floor, 0.0001);
    fill(
      goal,
      progressRail((meters - floor) / span),
      el('p', {
        class: 'fine',
        text: `${formatDistance(next.meters - meters)} to go before ${next.label}.`,
      }),
    );
    show(goal, true);
  } else {
    show(goal, false);
  }

  const delta = dayOverDayDelta(byDate, today);
  const deltaNode = byId('today-delta');
  if (delta === null || todayUm === 0) {
    show(deltaNode, false);
  } else {
    deltaNode.textContent = deltaText(delta, 'than yesterday');
    show(deltaNode, true);
  }

  // All-time comes from the day ledger, not from the board row: the ledger is the thing
  // this page can actually verify, and a row total that disagrees means a client is behind.
  fill(
    grid,
    figureCell('This week', weekUm, weekOverWeekDelta(byDate, today), 'than last week'),
    figureCell('This month', monthUm),
    figureCell('All time', allUm),
    figureCell('Days tracked', null, null, '', `${active.length}`),
  );
}

/** What this page knows about the limit, under the lanes. */
function renderLimitNote(limitMetres) {
  const shared = limitMetres !== null;
  show(byId('limit-local'), !shared && !state.limit);
  show(byId('limit-shared'), shared);
  show(byId('limit-none'), !shared && Boolean(state.limit));
  if (shared) byId('limit-shared-value').textContent = ovalDistance(limitMetres);
}

/** A change in words. More scrolling is not good or bad here, so it carries no colour. */
function deltaText(delta, suffix) {
  if (delta === 0) return `The same as ${suffix.replace(/^than /, '')}`;
  return `${Math.abs(delta)}% ${delta > 0 ? 'more' : 'less'} ${suffix}`;
}

function figureCell(label, um, delta = null, deltaSuffix = '', override = null) {
  const [figure, unit] = override ? [override, ''] : distanceParts(metersFromUm(um ?? 0));
  return el(
    'div',
    { class: 'figure' },
    el('p', { class: 'figure-label', text: label }),
    el(
      'p',
      { class: 'figure-value num' },
      figure,
      unit ? el('span', { class: 'unit', text: unit }) : null,
    ),
    delta === null || delta === undefined
      ? null
      : el('p', { class: 'delta', text: deltaText(delta, deltaSuffix) }),
  );
}

/**
 * A labelled figure with a rail under it: one row of a list divided by lane lines. The
 * rail is a share of the list's largest value and is always beside the figure it draws.
 */
export function measureRow({ tag = 'li', label, value, note = null, fraction = null, slot = null, current = false }) {
  return el(
    tag,
    { class: current ? 'row row-current' : 'row' },
    el(
      'div',
      { class: 'row-head' },
      el('span', { class: 'row-label', text: label }),
      note ? el('span', { class: 'row-note num', text: note }) : null,
      el('span', { class: 'row-value num', text: value }),
    ),
    fraction === null ? null : progressRail(fraction, slot),
  );
}
