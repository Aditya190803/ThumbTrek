/**
 * The side panel, rendered from IndexedDB alone.
 *
 * It used to be a popup, which closed the moment you clicked back into the page. As a side
 * panel it stays beside the feed you are scrolling, so it redraws itself: when the service
 * worker banks new distance, when settings change, when you switch tabs, and at midnight.
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
  badges, comparison, dayOverDayDelta, formatDistance,
  BASELINE_DAYS, isCleanDay, limitStreak, personalRecord, totalThisWeek, trackedDaysSince,
  trekStreak,
} from '../lib/stats.js';
import { ovalGeometry, ovalModel, trackDistance } from '../lib/oval.js';
import { dayLabel, todayKey } from '../lib/dates.js';
import { DEFAULT_SITES, registrableDomain, siteName } from '../lib/domain.js';
import {
  addCustomSite, customLabels, dailyLimit, dismissAutoLimitNotice, otherTodayMeters, readSettings,
  setDailyLimit,
  setSiteTracked, shouldTrack, startBaseline,
} from '../lib/settings.js';
import { applyBaselineIfReady } from '../lib/baseline.js';

const $ = (id) => document.getElementById(id);
const metres = (um) => formatDistance(micrometresToMetres(um));

const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

const SVG_NS = 'http://www.w3.org/2000/svg';

function svgNode(tag, attrs) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  return node;
}

const rectOf = (shape) => ({
  x: shape.x, y: shape.y, width: shape.width, height: shape.height, rx: shape.rx,
});

/** The swatch colour for a lane's text row, matching the stroke the oval drew. */
function swatchOf(lane) {
  if (lane.tone === 'volt') return 'var(--volt)';
  if (lane.tone === 'warn') return 'var(--lane-warn)';
  if (lane.tone === 'danger') return 'var(--lane-danger)';
  return `var(--${lane.tone})`;
}

/**
 * The lap oval (DESIGN.md) and its text rows. lib/oval.js decides every number; this only
 * draws them. A lane with no distance draws no stroke: a zero-length dash with round caps
 * would still paint a dot on the finish line.
 */
function renderOval(model, limitsByName = new Map()) {
  const shape = ovalGeometry(model.lanes.length);
  const svg = $('oval-svg');
  svg.setAttribute('viewBox', `0 0 ${shape.width} ${shape.height}`);
  svg.setAttribute('aria-label', model.label);
  $('oval').style.setProperty('--lanes', String(model.lanes.length));

  const nodes = [
    svgNode('rect', { class: 'oval-ground', ...rectOf(shape.ground) }),
    svgNode('rect', { class: 'oval-infield', ...rectOf(shape.infield) }),
    ...shape.lines.map((line) => svgNode('rect', { class: 'oval-line', ...rectOf(line) })),
  ];
  model.lanes.forEach((lane, index) => {
    if (lane.pct <= 0) return;
    nodes.push(svgNode('rect', {
      class: `lane tone-${lane.tone}`,
      pathLength: '100',
      'stroke-width': String(shape.laneStroke),
      'stroke-dasharray': `${Math.round(lane.pct * 10) / 10} 100`,
      ...rectOf(shape.lanes[index]),
    }));
  });
  nodes.push(svgNode('line', {
    class: 'oval-finish',
    x1: shape.finish.x, y1: shape.finish.y1, x2: shape.finish.x, y2: shape.finish.y2,
  }));
  svg.replaceChildren(...nodes);

  const [figure, unit] = model.figure;
  const hero = $('hero-figure');
  hero.textContent = figure;
  // The figure steps down a size once it gets long, so it still fits inside the infield.
  hero.parentElement.classList.toggle('long', figure.length > 4);
  $('hero-unit').textContent = unit;
  $('oval-caption').textContent = model.caption;

  $('lanes').replaceChildren(...model.lanes.map((lane) => {
    const item = document.createElement('li');
    const number = document.createElement('span');
    number.className = 'lane-no';
    number.textContent = String(lane.lane);
    const name = document.createElement('span');
    name.className = 'lane-name';
    name.style.setProperty('--swatch', swatchOf(lane));
    const label = document.createElement('span');
    label.textContent = lane.name;
    name.append(label);
    const distance = document.createElement('span');
    distance.className = 'lane-dist';
    // A site with its own limit reads "640 m of 500 m", and says in words when it is over.
    const limit = lane.total || lane.other ? undefined : limitsByName.get(lane.name);
    const over = limit !== undefined && lane.meters > limit;
    distance.textContent = limit === undefined
      ? lane.distance
      : `${lane.distance} of ${trackDistance(limit)}`;
    distance.classList.toggle('over', over);
    if (over) {
      const spoken = document.createElement('span');
      spoken.className = 'visually-hidden';
      spoken.textContent = ', over its limit';
      distance.append(spoken);
    }
    item.append(number, name, distance);
    return item;
  }));

  // What the "Other" lane is made of, so no site's distance is hidden inside a sum.
  $('others').hidden = model.others.length === 0;
  $('others-title').textContent = `In lane ${model.lanes.length}, Other`;
  $('others-list').replaceChildren(...model.others.map((site) => {
    const item = document.createElement('li');
    const name = document.createElement('span');
    name.textContent = site.name;
    const distance = document.createElement('span');
    distance.className = 'num';
    // Folded into Other or not, a site with its own limit still shows it.
    const limit = limitsByName.get(site.name);
    distance.textContent = limit === undefined ? site.distance : `${site.distance} of ${trackDistance(limit)}`;
    if (limit !== undefined && site.meters > limit) {
      distance.classList.add('over');
      const spoken = document.createElement('span');
      spoken.className = 'visually-hidden';
      spoken.textContent = ', over its limit';
      distance.append(spoken);
    }
    item.append(name, distance);
    return item;
  }));
}

/**
 * Nothing measured today: say why, and what the single next move is. Three different
 * reasons for an empty track, three different next moves.
 */
function renderEmpty(rows, settings, hasHistory) {
  const box = $('empty');
  box.hidden = rows.length > 0;
  if (rows.length > 0) return;
  const nothingTracked = !settings.trackEverything && settings.trackedSites.length === 0;
  const [title, text] = nothingTracked
    ? ['Tracking is off for every site',
      'Turn a site back on in Settings, or count the page you are on with the button below.']
    : hasHistory
      ? ['Nothing measured yet today',
        'Scroll a tracked site and its lane appears here within a few seconds.']
      : ['Your first lap starts here',
        'Open a feed and scroll. Instagram, YouTube, X and Reddit are on by default. '
        + 'Add any other site in Settings.'];
  $('empty-title').textContent = title;
  $('empty-text').textContent = text;
}

/** The limit, the baseline week, or no limit, as one fact row. */
function renderLimit(model, todayMeters, limitM, settings, dayMeters, today) {
  const hasLimit = limitM !== null;
  const learning = !hasLimit && Boolean(settings.baselineSince);
  $('baseline').hidden = hasLimit || learning;
  $('limit-title').textContent = hasLimit
    ? 'Daily limit'
    : learning ? 'Learning your baseline' : 'Daily limit';

  // The announcement, until it has been seen.
  const notice = hasLimit && typeof settings.autoLimitNotice === 'number';
  $('auto-limit').hidden = !notice;
  if (notice) {
    $('auto-limit-text').textContent = `Your usual day is about ${formatDistance(settings.autoLimitNotice)}, `
      + `so your limit is now ${formatDistance(limitM)}. Days within the limit count from today.`;
  }

  const status = $('limit-status');
  const text = $('limit-text');
  status.hidden = !hasLimit;
  text.hidden = !(hasLimit || learning);

  if (learning) {
    const days = Math.min(BASELINE_DAYS, trackedDaysSince(dayMeters, settings.baselineSince, today).length);
    $('limit-value').textContent = `Day ${days} of ${BASELINE_DAYS}`;
    text.textContent = days === 0
      ? 'Scroll as you normally would. Nothing is judged this week.'
      : `After ${BASELINE_DAYS} tracked days, your limit is set about 10% under your usual day. `
        + 'Nothing is judged until then.';
    return;
  }
  $('limit-value').textContent = hasLimit ? formatDistance(limitM) : 'Not set';
  if (!hasLimit) return;

  // Words as well as colour, and no shaming.
  status.textContent = model.status;
  status.className = `chip ${{ ok: 'chip-good', warn: 'chip-warn', over: 'chip-danger' }[model.state]}`;
  text.textContent = isCleanDay(todayMeters, limitM)
    ? `${formatDistance(Math.max(0, limitM - todayMeters))} left today.`
    : `${formatDistance(todayMeters - limitM)} over. A good moment to pause.`;
}

function renderFacts(todayUm, totals, today, cleanStreak) {
  const streak = trekStreak(totals.keys(), today);
  // At zero this says so rather than vanishing: a missing row teaches a new user nothing.
  $('streak-text').textContent = streak > 0 ? plural(streak, 'day') : 'None yet';

  // Only exists with a limit. Baseline mode makes no clean-day judgments (DESIGN.md).
  $('clean-streak').hidden = cleanStreak === null || cleanStreak === 0;
  if (cleanStreak) $('clean-streak-text').textContent = plural(cleanStreak, 'day');

  // Only once there is something today to compare, so a fresh morning does not open on
  // "100% less than yesterday". More or less is not good or bad here, so it has no colour.
  const delta = todayUm > 0 ? dayOverDayDelta(totals, today) : null;
  $('delta-row').hidden = delta === null;
  if (delta !== null) {
    $('delta').textContent = delta === 0
      ? 'The same'
      : `${Math.abs(delta)}% ${delta > 0 ? 'more' : 'less'}`;
  }

  $('week-total').textContent = metres(totalThisWeek(totals, today));

  const record = personalRecord(totals);
  $('best-day').textContent = record ? metres(record[1]) : 'None yet';
  $('best-day-when').textContent = record ? dayLabel(record[0]) : 'No days tracked yet';

  let allTime = 0;
  for (const value of totals.values()) allTime += value;
  $('all-time').textContent = metres(allTime);

  const earned = badges(
    micrometresToMetres(allTime),
    micrometresToMetres(record?.[1] ?? 0),
    streak,
    cleanStreak ?? 0,
  ).filter((badge) => badge.earned).length;
  $('badge-count').textContent = `${plural(earned, 'badge')} earned`;
}

/**
 * The active tab's hostname, asked of the content script running in it. The panel outlives
 * any one tab, and `activeTab` would only reveal the URL of the tab it was opened on; the
 * page knowing its own hostname needs no permission at all. Pages without the content
 * script (chrome://, the Web Store, a tab still loading) simply don't answer.
 */
async function activeHostname() {
  try {
    const [tab] = await api.tabs.query({ active: true, currentWindow: true });
    if (tab?.id === undefined) return '';
    const reply = await api.tabs.sendMessage(tab.id, { type: 'trek/where' });
    return typeof reply?.hostname === 'string' ? reply.hostname : '';
  } catch {
    return '';
  }
}

/**
 * The active tab, and one click to start (or stop) counting it. Adding a site used to mean
 * opening Settings and typing the domain you were already looking at.
 */
async function renderHere(settings) {
  const box = $('here');
  const hostname = await activeHostname();
  const domain = registrableDomain(hostname);
  box.hidden = !domain;
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
    $('here-text').textContent = `${name} is not being counted.`;
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

let drawnOnce = false;

async function render() {
  const today = todayKey();
  renderedDay = today;
  $('today-label').textContent = dayLabel(today);

  // Day eight may have arrived since the worker's last alarm; settle it before reading.
  await applyBaselineIfReady(today).catch(() => null);
  const [totals, rows, settings] = await Promise.all([
    dayTotals(), rowsForDate(today), readSettings(),
  ]);

  const limitM = dailyLimit(settings);
  const todayUm = totals.get(today) ?? 0;
  // With a shared limit the phone's scrolling today counts too, as of its last sync
  // (contract §7). It is zero whenever sharing is off.
  const phoneMeters = otherTodayMeters(settings, today);
  const todayMeters = micrometresToMetres(todayUm) + phoneMeters;
  // limitStreak works in metres, like the Kotlin; the stored totals are µm.
  const dayMeters = new Map([...totals].map(([key, um]) => [key, micrometresToMetres(um)]));
  // Today is judged on the combined figure; earlier days on this browser's own history.
  if (phoneMeters > 0) dayMeters.set(today, todayMeters);
  // Never reaches back past the day the limit began: a baseline week is not scored.
  const earliest = [...dayMeters.keys()].sort()[0];
  const firstDay = settings.limitSince
    ? (earliest !== undefined && earliest > settings.limitSince ? earliest : settings.limitSince)
    : undefined;
  const cleanStreak = limitM === null ? null : limitStreak(dayMeters, limitM, today, firstDay);

  const labels = customLabels(settings);
  const sites = rows.map((row) => ({
    name: siteName(row.domain, labels),
    meters: micrometresToMetres(row.um),
  }));
  if (phoneMeters > 0) sites.push({ name: 'Phone', meters: phoneMeters });
  sites.sort((a, b) => b.meters - a.meters);
  const model = ovalModel({ totalM: todayMeters, sites, limitM });
  // Lane rows are named, not keyed, so each site's own limit is looked up by its label.
  const limitsByName = new Map(
    Object.entries(settings.siteLimits ?? {}).map(([domain, limit]) => [siteName(domain, labels), limit]),
  );

  renderOval(model, limitsByName);
  renderEmpty(rows, settings, totals.size > 0);
  const line = $('comparison');
  line.hidden = todayMeters <= 0;
  line.textContent = comparison(todayMeters);
  renderLimit(model, todayMeters, limitM, settings, dayMeters, today);
  renderFacts(todayUm, totals, today, cleanStreak);
  $('sheet').setAttribute('aria-busy', 'false');
  // The lanes run in once; live redraws while the panel is open ease to their new length.
  if (drawnOnce) $('sheet').classList.add('settled');
  drawnOnce = true;
  await renderHere(settings);
}

// The panel stays open, so neither handler closes it the way the popup used to.
$('open-options').addEventListener('click', () => api.runtime.openOptionsPage());
$('open-premium').addEventListener('click', () =>
  api.tabs.create({ url: api.runtime.getURL('options/options.html#premium') }));

$('set-limit').addEventListener('click', async () => {
  // Baseline is the default way to a limit, so this starts one right here.
  await startBaseline();
  refresh();
});

const openLimitSettings = () =>
  api.tabs.create({ url: api.runtime.getURL('options/options.html#limit') });

$('auto-keep').addEventListener('click', async () => {
  await dismissAutoLimitNotice();
  refresh();
});
$('auto-adjust').addEventListener('click', async () => {
  await dismissAutoLimitNotice();
  openLimitSettings();
});
$('auto-decline').addEventListener('click', async () => {
  await setDailyLimit(null);
  refresh();
});

// ———————————————————————————— Live updates ————————————————————————————

let renderedDay = todayKey();
let pending = null;

/** Coalesces bursts (a flush every few seconds while scrolling) into one redraw. */
function refresh() {
  if (pending !== null) return;
  pending = setTimeout(() => {
    pending = null;
    render().then(() => { $('error').hidden = true; }).catch(showError);
  }, 400);
}

api.runtime.onMessage.addListener((message) => {
  if (message?.type === 'trek/updated') refresh();
  return false;
});
api.storage?.onChanged?.addListener(refresh);
api.tabs?.onActivated?.addListener(refresh);
api.tabs?.onUpdated?.addListener((_id, change) => {
  if (change.status === 'complete') refresh();
});
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refresh();
});
// Midnight: a panel left open overnight would otherwise keep calling yesterday "today".
setInterval(() => {
  if (todayKey() !== renderedDay) refresh();
}, 60_000);

function showError(error) {
  // A panel that fails silently looks like an extension that stopped working. Say what
  // happened, in the one place the user is already looking.
  const alert = $('error');
  alert.textContent = `Could not read local history: ${error.message}`;
  alert.hidden = false;
  $('sheet').setAttribute('aria-busy', 'false');
}

render().then(() => { $('error').hidden = true; }).catch(showError);
