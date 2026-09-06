// The signed-in dashboard.
//
// Same product as the phone's Trek / History / Social tabs, laid out as one scrolling
// survey sheet because a browser has the width to show at once what four tabs show in
// sequence. The information architecture and the wording are deliberately recognisable:
// a person who uses both should never have to learn this screen.
//
// What it is NOT: a second place that measures. The dashboard never writes a distance, a
// day document or a board row — it reads what the phone and the extension wrote. The only
// writes on this page are friendship edges (friends.js), and the friend-code index, and
// only once some client has already opted the account in.

import { barChart, measureRow, progressRail } from './charts.js';
import {
  PERIODS,
  archiveScore,
  rankEntries,
  rankOf,
  scoreOf,
  scoreText,
  sourceLabel,
  sourceSplit,
  syncedAgo,
} from './board.js';
import {
  MAX_PAGES,
  currentKeys,
  globalRank,
  loadGlobalPage,
  loadPodium,
  loadProfiles,
  watchBoardRow,
  watchDays,
  watchFriends,
} from './data.js';
import { announce, byId, el, fill, show } from './dom.js';
import {
  NotConfiguredError,
  completeRedirectSignIn,
  explainError,
  firebase,
  signIn,
  signOutOfThumbTrek,
  watchAuth,
} from './firebase.js';
import {
  acceptRequest,
  registerFriendCode,
  removeFriend,
  sendRequest,
} from './friends.js';
import {
  anonymousHandle,
  formatFriendCode,
  friendCode,
  inviteLink,
  inviteMessage,
} from './identity.js';
import {
  addDays,
  addMonths,
  appBreakdown,
  badges,
  comparison,
  dailyBuckets,
  dayOverDayDelta,
  distanceParts,
  formatDistance,
  landmarkFloor,
  longDayLabel,
  metersFromUm,
  monthTitle,
  monthlyBuckets,
  mondayOf,
  monthKey,
  nextLandmark,
  percentCaption,
  personalRecord,
  todayIso,
  trekStreak,
  weekKey,
  weekOverWeekDelta,
  weeklyBuckets,
} from './stats.js';

// ---------------------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------------------

/** The three history ranges, matching the phone's segmented control exactly. */
const RANGES = [
  { label: '7 days', buckets: (days, today) => dailyBuckets(days, 7, today), start: (t) => addDays(t, -6) },
  { label: '8 weeks', buckets: (days, today) => weeklyBuckets(days, 8, today), start: (t) => addDays(mondayOf(t), -49) },
  { label: '6 months', buckets: (days, today) => monthlyBuckets(days, 6, today), start: (t) => `${addMonths(monthKey(t), -5)}-01` },
];

const state = {
  today: todayIso(),
  user: null,
  code: '',
  row: { exists: false, data: null, fromCache: false },
  days: { docs: [], fromCache: false, loaded: false },
  friends: { accepted: [], pending: [], fromCache: false },
  profiles: new Map(),
  range: 0,
  boardKind: 'friends',
  periodId: 'week',
  board: { entries: [], cursor: null, exhausted: true, pages: 0, loading: false, error: null },
  myRank: null,
  podium: [],
  offline: !navigator.onLine,
  /** False when IndexedDB was refused, so nothing survives this tab closing. */
  persistent: true,
  busy: false,
};

/** Live-query teardown, so switching accounts does not leave listeners on the old uid. */
let unsubscribes = [];

/**
 * Whose friend code has already been published this session. Board-row snapshots arrive
 * every time any client syncs, and re-writing an unchanged index entry on each of them
 * would turn a once-per-session write into a background chatter of them.
 */
let codeRegisteredFor = null;

function stopWatching() {
  for (const stop of unsubscribes) {
    try {
      stop();
    } catch {
      /* a listener that never attached is not worth a stack trace */
    }
  }
  unsubscribes = [];
}

// ---------------------------------------------------------------------------------------
// Boot
// ---------------------------------------------------------------------------------------

async function boot() {
  wireStaticControls();

  try {
    state.persistent = (await firebase()).persistent;
  } catch (error) {
    if (error instanceof NotConfiguredError) return showSetup(error.message);
    return showSetup(explainError(error));
  }

  await completeRedirectSignIn();
  await watchAuth(onAuthChanged);
}

function showSetup(message) {
  byId('setup-detail').textContent = message;
  show(byId('setup'), true);
  show(byId('welcome'), true);
  for (const button of document.querySelectorAll('[data-signin]')) button.disabled = true;
}

function wireStaticControls() {
  for (const button of document.querySelectorAll('[data-signin]')) {
    button.addEventListener('click', async () => {
      button.disabled = true;
      try {
        await signIn();
      } catch (error) {
        byId('welcome-error').textContent = explainError(error);
        show(byId('welcome-error'), true);
      } finally {
        button.disabled = false;
      }
    });
  }
  byId('signout').addEventListener('click', () => signOutOfThumbTrek());

  window.addEventListener('online', () => setOffline(false));
  window.addEventListener('offline', () => setOffline(true));
}

function setOffline(offline) {
  state.offline = offline;
  renderConnection();
  announce(offline ? 'Offline. Showing the last saved copy.' : 'Back online.');
}

async function onAuthChanged(user) {
  stopWatching();
  state.user = user;
  // The CSS layer hides the wrong header half for this value even before the show()
  // calls below run, so Sign out can never leak into a signed-out render.
  document.body.dataset.auth = user ? 'in' : 'out';
  state.profiles = new Map();
  state.podium = [];
  state.board = { entries: [], cursor: null, exhausted: true, pages: 0, loading: false, error: null };

  show(byId('welcome'), !user);
  show(byId('dashboard'), Boolean(user));
  show(byId('account'), Boolean(user));
  if (!user) {
    state.row = { exists: false, data: null, fromCache: false };
    state.days = { docs: [], fromCache: false, loaded: false };
    codeRegisteredFor = null;
    return;
  }

  byId('account-name').textContent = user.displayName || 'Trekker';
  const avatar = byId('account-avatar');
  if (user.photoURL) {
    avatar.src = user.photoURL;
    show(avatar, true);
  } else {
    show(avatar, false);
  }

  state.code = await friendCode(user.uid);
  renderFriendCode();

  unsubscribes.push(
    await watchBoardRow(
      user.uid,
      (row) => {
        state.row = row;
        // The friend-code index is only published once some client has opted this account
        // in — a board row existing IS that opt-in. Registering it earlier would put a
        // uid in a public collection for someone who never agreed to be findable.
        if (row.exists && !row.fromCache && codeRegisteredFor !== user.uid) {
          codeRegisteredFor = user.uid;
          registerFriendCode(user.uid);
        }
        renderAll();
      },
      (error) => reportSection('history-error', explainError(error)),
    ),
    await watchDays(
      user.uid,
      (days) => {
        state.days = { ...days, loaded: true };
        renderAll();
      },
      (error) => reportSection('history-error', explainError(error)),
    ),
    await watchFriends(
      user.uid,
      (friends) => {
        state.friends = friends;
        refreshFriendProfiles();
        if (state.boardKind === 'friends') refreshBoard();
        renderFriends();
      },
      (error) => reportSection('friends-error', explainError(error)),
    ),
  );

  refreshBoard();
  loadLastWeekPodium();
  renderAll();
}

function reportSection(id, message) {
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
function derive() {
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

function rangeStart() {
  return RANGES[state.range].start(state.today);
}

/** True while the account has published totals but no day-by-day history to chart. */
function historyMissing(derived) {
  return derived.byDate.size === 0 && state.row.exists;
}

// ---------------------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------------------

function renderAll() {
  if (!state.user) return;
  // A dashboard left open overnight would otherwise keep calling yesterday "today", and
  // every streak, bucket and period gate below is anchored on this one string.
  state.today = todayIso();
  const derived = derive();
  renderConnection();
  renderTotals(derived);
  renderHistory(derived);
  renderRecords(derived);
  renderBoard();
  renderFriends();
}

function renderConnection() {
  const stale = state.offline || state.row.fromCache || state.days.fromCache;
  const strip = byId('connection');
  show(strip, stale);
  if (!stale) return;
  // Three different sentences because they are three different situations, and "something
  // went wrong" for all of them would leave the reader unable to tell which.
  if (state.offline && !state.persistent) {
    strip.textContent =
      'Offline, and this browser is not letting the page keep a local copy — private ' +
      'browsing usually does that. Reconnect to see anything.';
  } else if (state.offline) {
    strip.textContent =
      'Offline. These are the figures saved on this device the last time it reached the ' +
      'network — the phone may have logged more since.';
  } else {
    strip.textContent = 'Showing a saved copy while the network catches up.';
  }
}

function renderTotals({ byDate, active }) {
  const today = state.today;
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

  const meters = metersFromUm(todayUm);
  const [figure, unit] = distanceParts(meters);
  byId('today-figure').textContent = figure;
  byId('today-unit').textContent = unit;
  byId('today-compare').textContent = comparison(meters);

  // Progress to the next landmark: the multiple is the joke, the rail is the reason to
  // look again tomorrow.
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
    deltaNode.textContent = `${delta >= 0 ? '+' : '−'}${Math.abs(delta)}% on yesterday`;
    deltaNode.className = `delta ${delta >= 0 ? 'delta-up' : 'delta-down'}`;
    show(deltaNode, true);
  }

  // All-time comes from the day ledger, not from the board row: the ledger is the thing
  // this page can actually verify, and a row total that disagrees means a client is behind.
  fill(
    byId('totals-grid'),
    readout('This week', weekUm, weekOverWeekDelta(byDate, today), 'on last week'),
    readout('This month', monthUm),
    readout('All time', allUm),
    readout('Days tracked', null, null, null, `${active.length}`),
  );
}

function readout(label, um, delta = null, deltaSuffix = '', override = null) {
  const [figure, unit] = override ? [override, ''] : distanceParts(metersFromUm(um ?? 0));
  return el(
    'div',
    { class: 'readout-cell' },
    el('p', { class: 'overline', text: label }),
    el(
      'p',
      { class: 'readout-figure num' },
      figure,
      unit ? el('span', { class: 'unit', text: unit }) : null,
    ),
    delta === null || delta === undefined
      ? null
      : el('p', {
          class: `delta ${delta >= 0 ? 'delta-up' : 'delta-down'}`,
          text: `${delta >= 0 ? '+' : '−'}${Math.abs(delta)}% ${deltaSuffix}`,
        }),
  );
}

function renderHistory(derived) {
  const { byDate, bySource } = derived;
  const range = RANGES[state.range];

  const empty = byId('history-empty');
  const body = byId('history-body');
  // "No history" and "the history has not arrived yet" look identical and mean opposite
  // things, so the first snapshot has to land before either sentence is allowed on screen.
  if (!state.days.loaded) {
    show(body, false);
    show(empty, true);
    fill(empty, loadingSkeleton('Reading your history'));
    return;
  }
  if (byDate.size === 0) {
    show(body, false);
    show(empty, true);
    empty.textContent = historyMissing(derived)
      ? 'This account has published totals but no day-by-day history yet. The per-day ' +
        'ledger arrives with the next sync from a client that writes it; until then the ' +
        'figures above are all there is to chart.'
      : 'Nothing logged yet. Once tracking has run for a day, this fills with daily ' +
        'totals, the split between your devices, and where the scrolling happened.';
    return;
  }
  show(empty, false);
  show(body, true);

  const buckets = range.buckets(byDate, state.today);
  fill(
    byId('history-chart'),
    barChart(buckets, {
      format: (um) => formatDistance(metersFromUm(um)),
      caption: `Trek distance over the last ${range.label.toLowerCase()}`,
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

  if (!rows.length || total === 0) {
    fill(
      target,
      el('p', {
        class: 'fine',
        text: 'No scrolling recorded from any device in this range.',
      }),
    );
  } else {
    fill(
      target,
      rows.map((row, index) =>
        measureRow({
          label: sourceLabel(row.source),
          value: formatDistance(metersFromUm(row.um)),
          note: total > 0 ? `${Math.round((row.um / total) * 100)}%` : null,
          fraction: peak > 0 ? row.um / peak : 0,
          tone: index === 0 ? 'moss' : 'slate',
          leading: true,
        }),
      ),
    );
  }

  // A single-source account is the normal case, not an error — say which one is missing
  // rather than drawing an empty second bar.
  const known = new Set(rows.filter((r) => r.um > 0).map((r) => r.source));
  const missing = ['android', 'web'].filter((s) => !known.has(s));
  const note = byId('sources-note');
  if (missing.length === 1 && known.size) {
    note.textContent =
      missing[0] === 'web'
        ? 'Nothing from a browser yet — every metre in this range came from the phone.'
        : 'Nothing from the phone in this range — this is all browser scrolling.';
    show(note, true);
  } else {
    show(note, false);
  }

  // What each client last PUBLISHED, as opposed to what it recorded. The two answer
  // different questions — the rows above come from the day ledger, this comes from the
  // board row — and a client that stopped syncing is only visible here.
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
        const when = ago ? `synced ${ago}` : 'never synced';
        return el('li', {
          text: entry.stale
            ? `${sourceLabel(entry.source)} has published nothing for this week — ${when}.`
            : `${sourceLabel(entry.source)} published ${formatDistance(entry.meters)} for ` +
              `this week — ${when}.`,
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
      el('p', {
        class: 'fine',
        text: 'No per-app breakdown in this range. Older day records carry a daily total ' +
          'without the split.',
      }),
    );
    byId('breakdown-count').textContent = '';
    return;
  }
  const peak = rows[0].um;
  const total = rows.reduce((sum, row) => sum + row.um, 0);
  byId('breakdown-count').textContent = `${rows.length} ${rows.length === 1 ? 'source' : 'sources'}`;
  fill(
    target,
    rows.map((row) =>
      measureRow({
        label: row.key,
        value: formatDistance(metersFromUm(row.um)),
        note: total > 0 ? `${Math.round((row.um / total) * 100)}%` : null,
        fraction: peak > 0 ? row.um / peak : 0,
        tone: 'slate',
        leading: true,
      }),
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
      nodes.push(el('p', { class: 'overline daylog-month', text: monthTitle(thisMonth) }));
    }
    const um = byDate.get(date);
    nodes.push(
      measureRow({
        label: longDayLabel(date),
        value: formatDistance(metersFromUm(um)),
        fraction: peak > 0 ? um / peak : 0,
        tone: date === state.today ? 'moss' : 'slate',
      }),
    );
  }
  fill(target, nodes);
}

function renderRecords({ byDate, active }) {
  const streak = trekStreak(active, state.today);
  const record = personalRecord(byDate);
  const bestWeek = weeklyBuckets(byDate, 52, state.today).reduce(
    (best, bucket) => (best && best.um >= bucket.um ? best : bucket),
    null,
  );
  const allUm = [...byDate.values()].reduce((sum, um) => sum + um, 0);

  const streakNode = byId('streak');
  streakNode.textContent = streak > 0 ? `${streak} day streak` : 'No streak yet';
  streakNode.className = streak > 0 ? 'streak streak-live' : 'streak';

  fill(
    byId('records-rows'),
    record && record.um > 0
      ? ledgerLine('Longest single day', formatDistance(metersFromUm(record.um)), longDayLabel(record.date))
      : null,
    bestWeek && bestWeek.um > 0
      ? ledgerLine('Best week', formatDistance(metersFromUm(bestWeek.um)), `week of ${bestWeek.label}`)
      : null,
    ledgerLine(
      'Current streak',
      streak > 0 ? `${streak} days` : 'None',
      streak > 0 ? 'keep it going' : 'scroll today to start one',
    ),
  );

  const earned = badges(
    metersFromUm(allUm),
    metersFromUm(record?.um ?? 0),
    streak,
  ).sort((a, b) => Number(b.earned) - Number(a.earned) || b.progress - a.progress);

  byId('badge-count').textContent = `${earned.filter((b) => b.earned).length} / ${earned.length}`;
  fill(
    byId('badges'),
    earned.map((badge) =>
      el(
        'li',
        { class: badge.earned ? 'badge badge-earned' : 'badge' },
        el('span', { class: 'badge-emoji', 'aria-hidden': 'true', text: badge.emoji }),
        el('span', { class: 'badge-label', text: badge.label }),
        el('span', {
          class: 'visually-hidden',
          text: badge.earned
            ? 'earned'
            : `${percentCaption(badge.progress)} complete. ${badge.detail}`,
        }),
        badge.earned ? null : progressRail(badge.progress, 'slate'),
      ),
    ),
  );

  const next = earned.find((b) => !b.earned);
  const nextNode = byId('badge-next');
  if (next) {
    nextNode.textContent = `Next up: ${next.detail.toLowerCase()} (${percentCaption(next.progress)}).`;
    show(nextNode, true);
  } else {
    show(nextNode, false);
  }
}

function ledgerLine(label, value, caption) {
  return el(
    'div',
    { class: 'ledger' },
    el(
      'div',
      { class: 'ledger-head' },
      el('span', { class: 'ledger-label', text: label }),
      el('span', { class: 'ledger-value num', text: value }),
    ),
    el('p', { class: 'fine', text: caption }),
  );
}

// ---------------------------------------------------------------------------------------
// Leaderboard
// ---------------------------------------------------------------------------------------

/**
 * Guards against an older board answer landing after a newer one. Two controls and a live
 * friends snapshot can all ask for a board, and a slow "global / all time" reply arriving
 * after a fast "friends / this week" would otherwise repaint the wrong list under the
 * right pressed button.
 */
let boardRequest = 0;

async function refreshBoard() {
  if (!state.user) return;
  const token = ++boardRequest;
  state.board = { entries: [], cursor: null, exhausted: true, pages: 0, loading: true, error: null };
  state.myRank = null;
  renderBoard();
  try {
    if (state.boardKind === 'global') {
      const page = await loadGlobalPage(state.periodId, null);
      if (token !== boardRequest) return;
      state.board = {
        entries: page.entries,
        cursor: page.cursor,
        exhausted: page.exhausted,
        pages: 1,
        loading: false,
        error: null,
      };
      renderBoard();
      const rank = await globalRank(
        state.periodId,
        scoreOf(state.row.data ?? {}, state.periodId, currentKeys(state.today)).value,
      );
      if (token !== boardRequest) return;
      state.myRank = rank;
    } else {
      const entries = await loadProfiles([state.user.uid, ...state.friends.accepted]);
      if (token !== boardRequest) return;
      state.board = { entries, cursor: null, exhausted: true, pages: 1, loading: false, error: null };
    }
  } catch (error) {
    if (token !== boardRequest) return;
    state.board = { ...state.board, loading: false, error: explainError(error) };
  }
  renderBoard();
}

async function loadMore() {
  if (state.board.loading || state.board.exhausted || state.board.pages >= MAX_PAGES) return;
  // Same token as refreshBoard: switching board or period mid-page must discard this page
  // rather than append fifty global rows to a friends list.
  const token = boardRequest;
  state.board = { ...state.board, loading: true };
  renderBoard();
  try {
    const page = await loadGlobalPage(state.periodId, state.board.cursor);
    if (token !== boardRequest) return;
    state.board = {
      entries: [...state.board.entries, ...page.entries],
      cursor: page.cursor ?? state.board.cursor,
      exhausted: page.exhausted,
      pages: state.board.pages + 1,
      loading: false,
      error: null,
    };
    announce(`Loaded ${page.entries.length} more trekkers.`);
  } catch (error) {
    if (token !== boardRequest) return;
    state.board = { ...state.board, loading: false, error: explainError(error) };
  }
  renderBoard();
}

function renderBoard() {
  const keys = currentKeys(state.today);
  const ranked = rankEntries(state.board.entries, state.periodId, keys);
  const target = byId('board-rows');
  reportSection('board-error', state.board.error);

  const mine = rankOf(ranked, state.user?.uid);
  const standing = byId('board-standing');
  const rank = state.boardKind === 'global' ? state.myRank ?? mine : mine;
  if (rank) {
    standing.textContent =
      state.boardKind === 'global' ? `You're #${rank} globally.` : `You're #${rank} of this group.`;
    show(standing, true);
  } else if (state.row.exists) {
    standing.textContent = "You're not on this board for this period yet.";
    show(standing, true);
  } else {
    standing.textContent =
      'Your score is not published. Turn leaderboards on in the phone app — this page ' +
      'deliberately cannot publish it for you.';
    show(standing, true);
  }

  if (state.board.loading && !ranked.length) {
    fill(target, loadingSkeleton('Reading the board'));
  } else if (!ranked.length) {
    fill(
      target,
      state.boardKind === 'friends'
        ? emptyState(
            'Nobody here but you',
            'Friend codes are the only way onto this board. Send yours to one person and ' +
              'it stops being a solo sport.',
          )
        : emptyState(
            'The global board is empty',
            'Nobody has published a score for this period yet. Try again in a bit, or ' +
              'check the friends board.',
          ),
    );
  } else {
    const leader = ranked[0]?.score.value ?? 0;
    fill(
      target,
      ranked.map((entry) => boardRow(entry, leader)),
    );
  }

  const more = byId('board-more');
  const canLoadMore =
    state.boardKind === 'global' && !state.board.exhausted && state.board.pages < MAX_PAGES;
  show(more, canLoadMore);
  more.disabled = state.board.loading;
  more.textContent = state.board.loading ? 'Loading…' : 'Load more';

  const hidden = byId('board-hidden');
  show(
    hidden,
    state.boardKind === 'friends' &&
      state.friends.accepted.length > 0 &&
      ranked.length < state.friends.accepted.length + 1,
  );
}

function boardRow(entry, leader) {
  const isMe = entry.uid === state.user?.uid;
  const value = scoreText(entry.score, formatDistance);
  const fraction = leader > 0 ? entry.score.value / leader : 0;
  const name = isMe ? `${entry.displayName} (you)` : entry.displayName;
  const legacy = entry.score.unit === 'pixels';

  return el(
    'li',
    { class: isMe ? 'board-row board-row-me' : 'board-row' },
    el(
      'div',
      { class: 'board-head' },
      el('span', { class: `rank num rank-${Math.min(entry.rank, 4)}`, text: `${entry.rank}` }),
      avatar(entry.photoUrl, entry.displayName),
      el('span', { class: 'board-name', text: name }),
      el('span', { class: 'board-value num', text: value }),
      state.boardKind === 'friends' && !isMe
        ? el('button', {
            class: 'icon-button',
            type: 'button',
            title: `Remove ${entry.displayName}`,
            'aria-label': `Remove ${entry.displayName}`,
            onclick: () => confirmRemove(entry),
            text: '×',
          })
        : null,
    ),
    el(
      'div',
      { class: 'rail', 'aria-hidden': 'true' },
      el('div', {
        class: `rail-fill ${isMe ? 'rail-moss' : 'rail-slate'}`,
        style: `width:${Math.min(Math.max(fraction, 0), 1) * 100}%`,
      }),
    ),
    legacy
      ? el('p', {
          class: 'fine',
          text: 'On an older build that reports screen pixels. Ranked, but there is no ' +
            'comparable distance to show.',
        })
      : null,
  );
}

function avatar(photoUrl, name) {
  if (photoUrl) {
    return el('img', { class: 'avatar', src: photoUrl, alt: '', loading: 'lazy', width: 32, height: 32 });
  }
  return el('span', {
    class: 'avatar avatar-blank',
    'aria-hidden': 'true',
    text: (name || '?').charAt(0).toUpperCase(),
  });
}

function emptyState(title, body) {
  return el('div', { class: 'empty' }, el('h3', { text: title }), el('p', { text: body }));
}

/**
 * Loading reads as the shape of what is coming. The spoken label carries the sentence
 * for screen readers; the shimmer is hidden from them so it is not announced as content.
 */
function loadingSkeleton(label, widths = [88, 72, 80, 58]) {
  return el(
    'div',
    { class: 'skel', role: 'status', 'aria-label': label },
    el('span', { class: 'visually-hidden', text: label }),
    ...widths.map((w) => el('i', { style: `width:${w}%`, 'aria-hidden': 'true' })),
  );
}

async function loadLastWeekPodium() {
  try {
    const rows = await loadPodium(weekKey(addDays(state.today, -7)));
    state.podium = rows;
  } catch (error) {
    console.warn('ThumbTrek: no archive for last week.', error);
    state.podium = [];
  }
  renderPodium();
}

/**
 * Last week's top three as an actual podium: second, first, third, at three heights. A
 * ranked list of three names reads as data; three blocks of different heights reads as a
 * result. It is the one piece of pure delight on this page and it earns its place.
 */
function renderPodium() {
  const section = byId('podium');
  show(section, state.podium.length > 0);
  if (!state.podium.length) return;
  const order = [1, 0, 2].filter((i) => i < state.podium.length);
  fill(
    byId('podium-row'),
    order.map((index) => {
      const row = state.podium[index];
      const score = archiveScore(row);
      return el(
        'li',
        { class: `podium-slot podium-${index + 1}` },
        avatar(row.photoUrl ?? '', row.displayName ?? 'Trekker'),
        el('span', { class: 'podium-name', text: row.displayName || 'Trekker' }),
        el('span', { class: 'podium-value num', text: scoreText(score, formatDistance) }),
        el('span', { class: 'podium-block', 'aria-hidden': 'true', text: `${index + 1}` }),
      );
    }),
  );
}

// ---------------------------------------------------------------------------------------
// Friends
// ---------------------------------------------------------------------------------------

function renderFriendCode() {
  byId('my-code').textContent = formatFriendCode(state.code);
  byId('invite-link').textContent = inviteLink(state.code);
  byId('invite-link').href = `/i/${state.code}`;
}

/** Names for everyone on either side of the graph, so no row says a raw uid. */
async function refreshFriendProfiles() {
  const ids = [...state.friends.accepted, ...state.friends.pending].filter(
    (uid) => !state.profiles.has(uid),
  );
  if (!ids.length) return renderFriends();
  try {
    for (const entry of await loadProfiles(ids)) state.profiles.set(entry.uid, entry);
  } catch (error) {
    console.warn('ThumbTrek: could not read friend profiles.', error);
  }
  // Anyone with no board row has not opted in, so there is no name to read. The stable
  // pseudonym derived from their uid is better than a 28-character Firebase id.
  for (const uid of ids) {
    if (state.profiles.has(uid)) continue;
    state.profiles.set(uid, {
      uid,
      displayName: await anonymousHandle(uid),
      photoUrl: '',
      unpublished: true,
    });
  }
  renderFriends();
}

function renderFriends() {
  const requests = state.friends.pending;
  show(byId('requests'), requests.length > 0);
  byId('requests-count').textContent = `${requests.length}`;
  fill(
    byId('requests-rows'),
    requests.map((uid) => {
      const profile = state.profiles.get(uid);
      return el(
        'li',
        { class: 'request' },
        avatar(profile?.photoUrl ?? '', profile?.displayName ?? 'Trekker'),
        el('span', { class: 'request-name', text: profile?.displayName ?? 'A trekker' }),
        el('button', {
          class: 'btn btn-primary btn-small',
          type: 'button',
          text: 'Accept',
          onclick: () => act(() => acceptRequest(state.user.uid, uid), 'Request accepted.'),
        }),
        el('button', {
          class: 'btn btn-ghost btn-small',
          type: 'button',
          text: 'Decline',
          onclick: () => act(() => removeFriend(state.user.uid, uid), 'Request declined.'),
        }),
      );
    }),
  );

  const list = byId('friend-list');
  if (!state.friends.accepted.length) {
    fill(
      list,
      el('p', {
        class: 'fine',
        text: 'No trek partners yet. Share your code, or add someone else’s below.',
      }),
    );
  } else {
    fill(
      list,
      state.friends.accepted.map((uid) => {
        const profile = state.profiles.get(uid);
        return el(
          'li',
          { class: 'friend' },
          avatar(profile?.photoUrl ?? '', profile?.displayName ?? 'Trekker'),
          el('span', { class: 'friend-name', text: profile?.displayName ?? 'A trekker' }),
          profile?.unpublished
            ? el('span', { class: 'tag', text: 'not on the board' })
            : null,
          el('button', {
            class: 'btn btn-ghost btn-small',
            type: 'button',
            text: 'Remove',
            onclick: () => confirmRemove({ uid, displayName: profile?.displayName ?? 'this trekker' }),
          }),
        );
      }),
    );
  }
}

function confirmRemove(entry) {
  const ok = window.confirm(
    `Remove ${entry.displayName}?\n\nYou will drop off each other's boards. Friend codes ` +
      'still work, so you can add each other back later.',
  );
  if (ok) act(() => removeFriend(state.user.uid, entry.uid), `Removed ${entry.displayName}.`);
}

/** Runs a write, reports it in one place, and never leaves a button stuck in "busy". */
async function act(work, successMessage) {
  if (state.busy) return;
  state.busy = true;
  reportSection('friends-error', '');
  try {
    await work();
    announce(successMessage);
  } catch (error) {
    reportSection('friends-error', explainError(error));
    announce(explainError(error));
  } finally {
    state.busy = false;
  }
}

// ---------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------

function wireSegmented(containerId, options, onSelect, initial = 0) {
  const container = byId(containerId);
  const buttons = options.map((label, index) =>
    el('button', {
      type: 'button',
      class: 'segment',
      'aria-pressed': String(index === initial),
      text: label,
      onclick: () => {
        for (const [i, button] of buttons.entries()) {
          button.setAttribute('aria-pressed', String(i === index));
        }
        onSelect(index);
      },
    }),
  );
  fill(container, buttons);
}

function wireDashboardControls() {
  wireSegmented('range-control', RANGES.map((r) => r.label), (index) => {
    state.range = index;
    if (state.user) renderHistory(derive());
  });

  wireSegmented('board-kind', ['Friends', 'Global'], (index) => {
    state.boardKind = index === 0 ? 'friends' : 'global';
    refreshBoard();
  });

  wireSegmented('board-period', PERIODS.map((p) => p.label), (index) => {
    state.periodId = PERIODS[index].id;
    refreshBoard();
  });

  byId('board-more').addEventListener('click', loadMore);
  byId('board-refresh').addEventListener('click', () => {
    refreshBoard();
    loadLastWeekPodium();
  });

  byId('copy-code').addEventListener('click', async () => {
    const button = byId('copy-code');
    try {
      await navigator.clipboard.writeText(state.code);
      button.textContent = 'Copied';
      announce('Friend code copied.');
      setTimeout(() => {
        button.textContent = 'Copy code';
      }, 1800);
    } catch {
      // Clipboard access can be refused outright; selecting the text is the fallback that
      // always works, and it is what a person would do anyway.
      const range = document.createRange();
      range.selectNodeContents(byId('my-code'));
      const selection = window.getSelection();
      selection.removeAllRanges();
      selection.addRange(range);
      announce('Copy blocked by the browser — the code is selected, press Ctrl+C.');
    }
  });

  byId('share-invite').addEventListener('click', async () => {
    const text = inviteMessage(state.code);
    if (navigator.share) {
      try {
        await navigator.share({ text, url: inviteLink(state.code) });
        return;
      } catch {
        /* the share sheet was dismissed; fall through to the clipboard */
      }
    }
    try {
      await navigator.clipboard.writeText(text);
      announce('Invite copied to the clipboard.');
    } catch {
      announce('Could not copy the invite. The link is on screen above.');
    }
  });

  byId('add-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const input = byId('add-code');
    const value = input.value.trim();
    if (!value) return;
    await act(async () => {
      await sendRequest(state.user.uid, value);
      input.value = '';
    }, 'Request sent. They have to accept before you land on each other’s boards.');
  });
}

// ---------------------------------------------------------------------------------------

wireDashboardControls();
boot();
