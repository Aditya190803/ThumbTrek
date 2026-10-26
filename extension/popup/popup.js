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
  BASELINE_DAYS, isCleanDay, limitStreak, personalRecord, totalThisWeek, trackedDaysSince,
  trekStreak,
} from '../lib/stats.js';
import { dayLabel, todayKey } from '../lib/dates.js';
import { DEFAULT_SITES, registrableDomain, siteName } from '../lib/domain.js';
import {
  addCustomSite, customLabels, dailyLimit, dismissAutoLimitNotice, readSettings, setDailyLimit,
  setSiteTracked, shouldTrack, startBaseline,
} from '../lib/settings.js';
import { applyBaselineIfReady } from '../lib/baseline.js';

const $ = (id) => document.getElementById(id);
const metres = (um) => formatDistance(micrometresToMetres(um));

/**
 * Chrome caps a popup at 600px and this one does not scroll, so the breakdown shows the
 * biggest few and summarises the tail. Under "track everything" a day can easily touch
 * twenty domains, and an unbounded list pushed the week and the footer off the bottom.
 */
const MAX_SITE_ROWS = 4;

const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

function renderHero(todayUm, totals, today, cleanStreak) {
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

  // Only exists with a limit. Baseline mode makes no clean-day judgments (DESIGN.md).
  $('clean-streak').hidden = cleanStreak === null || cleanStreak === 0;
  if (cleanStreak) {
    $('clean-streak-text').textContent = `${plural(cleanStreak, 'day')} within limit`;
  }

  // Like the phone's DeltaMark: only once there is something today to compare, so a fresh
  // morning does not open on "−100% vs yesterday".
  const delta = todayUm > 0 ? dayOverDayDelta(totals, today) : null;
  // Neutral colour either way; the arrow carries the direction and the words the meaning.
  $('delta').textContent = delta === null
    ? ''
    : `${delta >= 0 ? '↑' : '↓'} ${Math.abs(delta)}% vs yesterday`;
}

/**
 * The limit rail, the baseline week, or plain no-limit. Mirrors LimitGlance and
 * BaselineGlance on Android.
 */
function renderLimit(todayMeters, limitM, settings, dayMeters, today) {
  const hasLimit = limitM !== null;
  const learning = !hasLimit && Boolean(settings.baselineSince);
  $('limit-gauge').hidden = !hasLimit;
  $('learning').hidden = !learning;
  $('baseline').hidden = hasLimit || learning;
  $('limit-title').textContent = hasLimit
    ? 'Daily limit'
    : learning ? 'Learning your baseline' : 'No daily limit';

  // The announcement card, until it has been seen.
  const notice = hasLimit && typeof settings.autoLimitNotice === 'number';
  $('auto-limit').hidden = !notice;
  if (notice) {
    $('auto-limit-text').textContent = `Your usual day is about ${formatDistance(settings.autoLimitNotice)}, `
      + `so your limit is now ${formatDistance(limitM)}. Clean days count from today.`;
  }

  if (learning) {
    const days = Math.min(BASELINE_DAYS, trackedDaysSince(dayMeters, settings.baselineSince, today).length);
    $('limit-value').textContent = `Day ${days} of ${BASELINE_DAYS}`;
    $('learning-fill').style.width = `${(days / BASELINE_DAYS) * 100}%`;
    $('learning-fill').classList.toggle('zero', days === 0);
    $('learning-rail').setAttribute('aria-valuenow', String(days));
    $('learning-text').textContent = days === 0
      ? 'Scroll as you normally would. Nothing is judged this week.'
      : `After ${BASELINE_DAYS} tracked days, your limit is set about 10% under your usual day. `
        + 'Nothing is judged until then.';
    return;
  }
  $('limit-value').textContent = hasLimit ? formatDistance(limitM) : '';
  if (!hasLimit) return;

  const clean = isCleanDay(todayMeters, limitM);
  const share = Math.min(1, todayMeters / limitM);
  const fill = $('limit-fill');
  fill.style.width = `${share * 100}%`;
  fill.classList.toggle('zero', todayMeters <= 0);
  const rail = $('limit-rail');
  rail.classList.toggle('over', !clean);
  rail.setAttribute('aria-valuenow', String(Math.round(share * 100)));
  rail.setAttribute('aria-valuetext',
    `${formatDistance(todayMeters)} of ${formatDistance(limitM)}`);

  const text = $('limit-text');
  // Words as well as colour, and no shaming: the phone's exact phrasing.
  text.textContent = clean
    ? `${formatDistance(Math.max(0, limitM - todayMeters))} remaining today`
    : `${formatDistance(todayMeters - limitM)} over. A good moment to pause.`;
  text.className = `limit-text ${clean ? 'moss' : 'danger'}`;
}

function emptyState(title, body) {
  const empty = document.createElement('div');
  empty.className = 'empty';
  const heading = document.createElement('h3');
  heading.textContent = title;
  const text = document.createElement('p');
  text.textContent = body;
  empty.append(heading, text);
  return empty;
}

function renderSites(rows, labels, settings, hasHistory) {
  const host = $('sites');
  host.replaceChildren();
  if (rows.length === 0) {
    // Always names what will appear here and what the single next move is. Three
    // different reasons for an empty list, three different next moves.
    const nothingTracked = !settings.trackEverything && settings.trackedSites.length === 0;
    host.append(nothingTracked
      ? emptyState('Tracking is off for every site',
        'Turn a site back on in Settings, or use the button below on a page you want counted.')
      : hasHistory
        ? emptyState('Nothing measured yet today',
          'Scroll a tracked site and it appears here within a few seconds.')
        : emptyState('Your first trek starts here',
          'Open a feed and scroll. Instagram, YouTube, X and Reddit are on by default; '
          + 'add any other site in Settings.'));
    $('site-count').textContent = '';
    return;
  }

  const biggest = rows[0].um;
  const list = document.createElement('ul');
  list.className = 'site-rows';
  for (const row of rows.slice(0, MAX_SITE_ROWS)) {
    const item = document.createElement('li');
    item.className = 'measure-row';
    const line = document.createElement('div');
    line.className = 'line';
    const label = document.createElement('span');
    label.className = 'label';
    label.textContent = siteName(row.domain, labels);
    label.title = row.domain;
    const value = document.createElement('span');
    value.className = 'value num';
    value.textContent = metres(row.um);
    line.append(label, value);
    const rail = document.createElement('div');
    rail.className = 'rail';
    rail.setAttribute('aria-hidden', 'true');
    const fill = document.createElement('i');
    // Proportional to the biggest row, not to the day's total: the question a split answers
    // is "which of these is the big one", and shares of a total flatten that to slivers.
    fill.style.width = `${Math.max(2, (row.um / biggest) * 100)}%`;
    rail.append(fill);
    item.append(line, rail);
    list.append(item);
  }
  host.append(list);

  const rest = rows.slice(MAX_SITE_ROWS);
  if (rest.length > 0) {
    const more = document.createElement('p');
    more.className = 'more faint';
    const restUm = rest.reduce((sum, row) => sum + row.um, 0);
    more.textContent = `${plural(rest.length, 'more site')} · ${metres(restUm)}`;
    host.append(more);
  }
  $('site-count').textContent = plural(rows.length, 'site');
}

/**
 * The active tab, and one click to start (or stop) counting it. Adding a site used to mean
 * opening Settings and typing the domain you were already looking at. `activeTab` makes the
 * URL readable only for the tab the user opened the popup on, only while it is open.
 */
async function renderHere(settings) {
  const box = $('here');
  box.hidden = true;
  let tab;
  try {
    [tab] = await api.tabs.query({ active: true, currentWindow: true });
  } catch {
    return;
  }
  let hostname = '';
  try {
    const url = new URL(tab?.url ?? '');
    if (url.protocol === 'http:' || url.protocol === 'https:') hostname = url.hostname;
  } catch {
    return;
  }
  const domain = registrableDomain(hostname);
  if (!domain) return;

  const labels = customLabels(settings);
  const name = siteName(domain, labels);
  const tracked = shouldTrack(domain, settings);
  const action = $('here-action');

  if (settings.trackEverything) {
    // Under "track everything" a per-site switch here would do nothing; say so and stop.
    $('here-text').textContent = `Counting ${name}, along with every other site.`;
    action.hidden = true;
  } else if (tracked) {
    $('here-text').textContent = `Counting ${name} on this tab.`;
    action.hidden = false;
    action.textContent = 'Stop counting';
    action.onclick = async () => {
      await setSiteTracked(domain, false);
      render();
    };
  } else {
    $('here-text').textContent = `${name} isn't being counted.`;
    action.hidden = false;
    action.textContent = 'Count this site';
    action.onclick = async () => {
      // A site already known (built-in, or added before and switched off) is just switched
      // back on; a new one is remembered as well, exactly like the options page's Add.
      if (DEFAULT_SITES.has(domain) || domain in settings.customSites) {
        await setSiteTracked(domain, true);
      } else {
        await addCustomSite(domain, name);
      }
      render();
    };
  }
  box.hidden = false;
}

/**
 * The week as a route across contours rather than seven bars.
 *
 * Same survey language as the landing page's hero, and a line reads a trend at a glance
 * where seven columns have to be compared pairwise. Drawn by hand in SVG: a chart library
 * would be the extension's only dependency, for one 300x60 polyline.
 */
function renderSparkline(totals, today) {
  const buckets = dailyBuckets(totals, 7, today);
  const svg = $('spark');
  svg.replaceChildren();

  const W = 300;
  const H = 60;
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
    const stop = line({
      class: i === points.length - 1 ? 'stop today' : 'stop',
      cx: x.toFixed(1), cy: y.toFixed(1), r: i === points.length - 1 ? 3.6 : 2.4,
    }, 'circle');
    // A native tooltip per day: hovering a stop answers "how far was Tuesday" without a
    // tooltip layer. Screen readers get the whole week from the caption instead.
    stop.append(Object.assign(document.createElementNS(ns, 'title'), {
      textContent: `${buckets[i].label}: ${metres(buckets[i].value)}`,
    }));
    svg.append(stop);
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
  $('week-total').textContent = `${metres(totalThisWeek(totals, today))} this week`;
  $('spark-caption').textContent = `Distance per day, last 7 days. ${buckets
    .map((b) => `${b.label}: ${metres(b.value)}`)
    .join(', ')}`;
}

function renderFooter(totals, today, cleanStreak) {
  const record = personalRecord(totals);
  $('best-day').textContent = record ? metres(record[1]) : '—';
  $('best-day-when').textContent = record ? dayLabel(record[0]) : 'No days yet';

  let allTime = 0;
  for (const value of totals.values()) allTime += value;
  $('all-time').textContent = metres(allTime);

  const earned = badges(
    micrometresToMetres(allTime),
    micrometresToMetres(record?.[1] ?? 0),
    trekStreak(totals.keys(), today),
    cleanStreak ?? 0,
  ).filter((badge) => badge.earned).length;
  $('badge-count').textContent = `${plural(earned, 'badge')} earned`;
}

async function render() {
  const today = todayKey();
  $('today-label').textContent = dayLabel(today);

  // Day eight may have arrived since the worker's last alarm; settle it before reading.
  await applyBaselineIfReady(today).catch(() => null);
  const [totals, rows, settings] = await Promise.all([
    dayTotals(), rowsForDate(today), readSettings(),
  ]);

  const limitM = dailyLimit(settings);
  const todayUm = totals.get(today) ?? 0;
  const todayMeters = micrometresToMetres(todayUm);
  // limitStreak works in metres, like the Kotlin; the stored totals are µm.
  const dayMeters = new Map([...totals].map(([key, um]) => [key, micrometresToMetres(um)]));
  // Never reaches back past the day the limit began: a baseline week is not scored.
  const earliest = [...dayMeters.keys()].sort()[0];
  const firstDay = settings.limitSince
    ? (earliest !== undefined && earliest > settings.limitSince ? earliest : settings.limitSince)
    : undefined;
  const cleanStreak = limitM === null ? null : limitStreak(dayMeters, limitM, today, firstDay);

  renderHero(todayUm, totals, today, cleanStreak);
  renderLimit(todayMeters, limitM, settings, dayMeters, today);
  renderSites(rows, customLabels(settings), settings, totals.size > 0);
  // A brand-new install has no week to draw: a flat line at zero next to "Your first trek
  // starts here" says the same thing twice.
  $('week').hidden = totals.size === 0;
  if (totals.size > 0) renderSparkline(totals, today);
  renderFooter(totals, today, cleanStreak);
  $('sheet').setAttribute('aria-busy', 'false');
  await renderHere(settings);
}

$('open-options').addEventListener('click', () => {
  api.runtime.openOptionsPage();
  window.close();
});

$('set-limit').addEventListener('click', async () => {
  // Baseline is the default way to a limit, so this starts one right here.
  await startBaseline();
  render().catch(showError);
});

const openLimitSettings = () => {
  api.tabs.create({ url: api.runtime.getURL('options/options.html#limit') });
  window.close();
};

$('auto-keep').addEventListener('click', async () => {
  await dismissAutoLimitNotice();
  render().catch(showError);
});
$('auto-adjust').addEventListener('click', async () => {
  await dismissAutoLimitNotice();
  openLimitSettings();
});
$('auto-decline').addEventListener('click', async () => {
  await setDailyLimit(null);
  render().catch(showError);
});

function showError(error) {
  // A popup that fails silently looks like an extension that stopped working. Say what
  // happened, in the one place the user is already looking.
  const alert = $('error');
  alert.textContent = `Could not read local history: ${error.message}`;
  alert.hidden = false;
  $('sheet').setAttribute('aria-busy', 'false');
}

render().catch(showError);
