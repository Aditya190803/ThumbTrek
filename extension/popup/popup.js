/**
 * The popup, rendered from IndexedDB alone.
 *
 * Not one line of this touches the network or the account. Contract §5: "Tracking, history,
 * streaks, records and badges all work with no account. Local storage is the source of
 * truth; the network is a mirror." A popup that shows a spinner or an empty state because a
 * sync has not happened would make the mirror look like the truth.
 *
 * It reads the database directly rather than asking the service worker for a summary. An
 * MV3 worker is asleep most of the time, and waking one to answer a question the page can
 * answer itself is a visible delay on the surface people open twenty times a day.
 */

import { api } from '../lib/browser.js';
import { dayTotals, rowsForDate } from '../lib/db.js';
import { micrometresToMetres } from '../lib/units.js';
import {
  badges, comparison, dailyBuckets, dayOverDayDelta, distanceParts, formatDistance,
  personalRecord, totalThisWeek, trekStreak,
} from '../lib/stats.js';
import { dayLabel, todayKey } from '../lib/dates.js';
import { siteName } from '../lib/domain.js';
import { customLabels, readSettings } from '../lib/settings.js';

const $ = (id) => document.getElementById(id);
const metres = (um) => formatDistance(micrometresToMetres(um));

function renderHero(todayUm, totals, today) {
  const [figure, unit] = distanceParts(micrometresToMetres(todayUm));
  const hero = $('hero-figure');
  hero.textContent = figure;
  // The figure steps down a size once it gets long, so a five-figure kilometre count still
  // fits on one line. Same rule as HeroDistance's `large` branch on Android.
  hero.classList.toggle('long', figure.length > 4);
  $('hero-unit').textContent = unit;
  $('comparison').textContent = comparison(micrometresToMetres(todayUm));

  const streak = trekStreak(totals.keys(), today);
  const chip = $('streak');
  chip.classList.toggle('streak', streak > 0);
  chip.classList.toggle('idle', streak === 0);
  // At zero this becomes a quiet invitation rather than vanishing: an element that
  // disappears teaches a new user nothing.
  $('streak-text').textContent = streak > 0 ? `${streak} day streak` : 'No streak yet';

  const delta = dayOverDayDelta(totals, today);
  const deltaEl = $('delta');
  if (delta === null) {
    deltaEl.textContent = '';
    deltaEl.className = 'delta';
  } else {
    // Coloured only by direction, never by judgement about the number.
    deltaEl.textContent = `${delta >= 0 ? '+' : '−'}${Math.abs(delta)}% vs yesterday`;
    deltaEl.className = `delta ${delta >= 0 ? 'up' : 'down'}`;
  }
}

function renderSites(rows, labels) {
  const host = $('sites');
  host.replaceChildren();
  if (rows.length === 0) {
    // Always names what will appear here and what the single next move is.
    const empty = document.createElement('div');
    empty.className = 'empty';
    const title = document.createElement('h3');
    title.textContent = 'Nothing measured yet today';
    const body = document.createElement('p');
    body.textContent = 'Open a feed and scroll. Instagram, YouTube, X and Reddit are on by '
      + 'default; add any other site in Settings.';
    empty.append(title, body);
    host.append(empty);
    $('site-count').textContent = '';
    return;
  }

  const biggest = rows[0].um;
  for (const row of rows) {
    const item = document.createElement('div');
    item.className = 'measure-row';
    const line = document.createElement('div');
    line.className = 'line';
    const label = document.createElement('span');
    label.className = 'label';
    label.textContent = siteName(row.domain, labels);
    const value = document.createElement('span');
    value.className = 'value num';
    value.textContent = metres(row.um);
    line.append(label, value);
    const rail = document.createElement('div');
    rail.className = 'rail';
    const fill = document.createElement('i');
    // Proportional to the biggest row, not to the day's total: the question a split answers
    // is "which of these is the big one", and shares of a total flatten that to slivers.
    fill.style.width = `${Math.max(2, (row.um / biggest) * 100)}%`;
    rail.append(fill);
    item.append(line, rail);
    host.append(item);
  }
  $('site-count').textContent = `${rows.length} site${rows.length === 1 ? '' : 's'}`;
}

/**
 * The week as a route across contours rather than seven bars.
 *
 * Same survey language as the landing page's hero, and a line reads a trend at a glance
 * where seven columns have to be compared pairwise. Drawn by hand in SVG: a chart library
 * would be the extension's only dependency, for one 300x74 polyline.
 */
function renderSparkline(totals, today) {
  const buckets = dailyBuckets(totals, 7, today);
  const svg = $('spark');
  svg.replaceChildren();

  const W = 300;
  const H = 74;
  const PAD_X = 6;
  const PAD_TOP = 8;
  const BASE = H - 12;
  const peak = Math.max(...buckets.map((b) => b.value), 1);
  const step = (W - PAD_X * 2) / (buckets.length - 1);
  const points = buckets.map((b, i) => [
    PAD_X + i * step,
    // A day with any distance at all sits visibly above the ground line, so "a little" and
    // "nothing" are never the same mark.
    b.value === 0 ? BASE : BASE - Math.max(4, (b.value / peak) * (BASE - PAD_TOP)),
  ]);

  const ns = 'http://www.w3.org/2000/svg';
  const line = (attrs, tag = 'path') => {
    const node = document.createElementNS(ns, tag);
    for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
    return node;
  };

  const d = points.map(([x, y], i) => `${i === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
  svg.append(line({ class: 'under', d: `${d} L${W - PAD_X} ${BASE} L${PAD_X} ${BASE} Z` }));
  svg.append(line({ class: 'ground', d: `M${PAD_X} ${BASE} L${W - PAD_X} ${BASE}` }));
  const route = line({ class: 'route', d });
  svg.append(route);
  points.forEach(([x, y], i) => {
    svg.append(line({
      class: i === points.length - 1 ? 'stop today' : 'stop',
      cx: x.toFixed(1), cy: y.toFixed(1), r: i === points.length - 1 ? 3.6 : 2.4,
    }, 'circle'));
  });

  // The dash length comes from the path itself, so a flat week and a jagged one both trace
  // fully. getTotalLength is exact where a hard-coded guess clips or lags.
  const length = route.getTotalLength?.() ?? 0;
  if (length > 0) {
    route.style.setProperty('--len', length);
    route.style.strokeDasharray = length;
    route.style.strokeDashoffset = length;
  }

  $('spark-axis').replaceChildren(
    Object.assign(document.createElement('span'), { textContent: dayLabel(buckets[0].key) }),
    Object.assign(document.createElement('span'), { textContent: 'Today' }),
  );
  $('week-total').textContent = metres(totalThisWeek(totals, today));
  $('spark-caption').textContent = buckets
    .map((b) => `${b.label}: ${metres(b.value)}`)
    .join(', ');
}

function renderFooter(totals) {
  const record = personalRecord(totals);
  $('best-day').textContent = record ? metres(record[1]) : '—';
  $('best-day-when').textContent = record ? dayLabel(record[0]) : 'no days yet';

  let allTime = 0;
  for (const value of totals.values()) allTime += value;
  $('all-time').textContent = metres(allTime);

  const today = todayKey();
  const earned = badges(
    micrometresToMetres(allTime),
    micrometresToMetres(personalRecord(totals)?.[1] ?? 0),
    trekStreak(totals.keys(), today),
  ).filter((badge) => badge.earned).length;
  $('badge-count').textContent = `${earned} badge${earned === 1 ? '' : 's'}`;
}

async function render() {
  const today = todayKey();
  $('today-label').textContent = dayLabel(today);

  const [totals, rows, settings] = await Promise.all([
    dayTotals(), rowsForDate(today), readSettings(),
  ]);

  renderHero(totals.get(today) ?? 0, totals, today);
  renderSites(rows, customLabels(settings));
  renderSparkline(totals, today);
  renderFooter(totals);
}

$('open-options').addEventListener('click', () => {
  api.runtime.openOptionsPage();
  window.close();
});

render().catch((error) => {
  // A popup that fails silently looks like an extension that stopped working. Say what
  // happened, in the one place the user is already looking.
  $('comparison').textContent = `Could not read local history: ${error.message}`;
});
