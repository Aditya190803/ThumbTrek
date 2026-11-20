// The signed-in dashboard.
//
// Same product as the phone's Today / History / Friends tabs, with the same sections as
// stadium tabs in the top bar (DESIGN.md, Navigation). The lap oval leads, and every lane it
// draws has a text row beside it. The wording is deliberately recognisable: a person who
// uses both should never have to learn this screen.
//
// What it is NOT: a second place that measures. The dashboard never writes a distance, a
// day document or a board row — it reads what the phone and the extension wrote. The only
// writes on this page are friendship edges (friends.js), and the friend-code index, and
// only once some client has already opted the account in.

import { PERIODS } from './board.js';
import { watchBoardRow, watchDays, watchFriends, watchSharedLimit } from './data.js';
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
  act,
  avatar,
  loadLastWeekPodium,
  loadMore,
  refreshBoard,
  refreshFriendProfiles,
  renderBoard,
  renderFriendCode,
  renderFriends,
} from './friends-view.js';
import { registerFriendCode, sendRequest } from './friends.js';
import { refreshGroups, renderGroups, wireGroups } from './groups-view.js';
import { renderHistory, renderRecords } from './history-view.js';
import { friendCode, inviteLink, inviteMessage } from './identity.js';
import { REPORTS, renderReport } from './report-view.js';
import { PHONE_LAYOUT, RANGES, derive, reportSection, state } from './state.js';
import { todayIso } from './stats.js';
import { renderToday } from './today-view.js';

/** Live-query teardown, so switching accounts does not leave listeners on the old uid. */
let unsubscribes = [];

/**
 * Bumped on every auth change. Attaching each listener is awaited, so a second change can
 * land while the first account is still wiring up; anything that finishes under an old
 * generation is torn down on arrival instead of writing the old account into the new view.
 */
let authGeneration = 0;

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
      button.setAttribute('aria-busy', 'true');
      // A retry should not keep showing the previous attempt's failure underneath it.
      show(byId('welcome-error'), false);
      try {
        await signIn();
      } catch (error) {
        byId('welcome-error').textContent = explainError(error);
        show(byId('welcome-error'), true);
      } finally {
        button.disabled = false;
        button.removeAttribute('aria-busy');
      }
    });
  }
  byId('signout').addEventListener('click', () => signOutOfThumbTrek());

  window.addEventListener('hashchange', () => showView(viewFromHash()));
  showView(viewFromHash() ?? 'today');

  // The oval is wider on a desktop than on a phone (DESIGN.md), and that is a different
  // drawing, not a different size, so it is redrawn when the layout crosses over.
  PHONE_LAYOUT.addEventListener('change', () => {
    if (state.user) renderToday(derive());
  });

  window.addEventListener('online', () => setOffline(false));
  window.addEventListener('offline', () => setOffline(true));
}

// ---------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------

/** The dashboard's sections, in tab order. Each is a `[data-view]` block in index.html. */
const VIEWS = ['today', 'history', 'friends', 'premium'];


/** The section named by the address bar, or null for any other hash (the skip link's). */
function viewFromHash() {
  const name = location.hash.slice(1);
  return VIEWS.includes(name) ? name : null;
}

/**
 * Shows one section and marks its tab. Tabs are plain links to a hash, so this runs from
 * `hashchange` and the back button walks through the sections a person visited.
 */
function showView(name) {
  if (!name) return;
  const changed = state.view !== name;
  state.view = name;
  for (const view of document.querySelectorAll('[data-view]')) {
    show(view, view.dataset.view === name);
  }
  for (const tab of document.querySelectorAll('[data-tab]')) {
    if (tab.dataset.tab === name) tab.setAttribute('aria-current', 'page');
    else tab.removeAttribute('aria-current');
  }
  if (changed) window.scrollTo(0, 0);
}

function setOffline(offline) {
  state.offline = offline;
  renderConnection();
  announce(offline ? 'Offline. Showing the last saved copy.' : 'Back online.');
}

async function onAuthChanged(user) {
  stopWatching();
  const generation = ++authGeneration;
  const current = () => generation === authGeneration;
  state.user = user;
  // The CSS layer hides the wrong header half for this value even before the show()
  // calls below run, so Sign out can never leak into a signed-out render.
  document.body.dataset.auth = user ? 'in' : 'out';
  state.profiles = new Map();
  state.podium = [];
  state.board = { entries: [], cursor: null, exhausted: true, pages: 0, loading: false, error: null };
  // Cleared for every change, not just sign-out, so the next account on this tab never
  // paints the previous one's figures or friends before its own snapshots arrive.
  state.row = { exists: false, data: null, fromCache: false, loaded: false };
  state.days = { docs: [], fromCache: false, loaded: false };
  state.friends = { accepted: [], pending: [], fromCache: false };
  state.myRank = null;
  state.limit = null;
  state.groups = { list: [], loaded: false, open: null, progress: [] };

  show(byId('welcome'), !user);
  show(byId('dashboard'), Boolean(user));
  show(byId('account'), Boolean(user));
  if (!user) {
    codeRegisteredFor = null;
    return;
  }

  byId('account-name').textContent = user.displayName || 'Signed in';
  const avatar = byId('account-avatar');
  if (user.photoURL) {
    avatar.referrerPolicy = 'no-referrer';
    avatar.src = user.photoURL;
    show(avatar, true);
  } else {
    show(avatar, false);
  }

  const code = await friendCode(user.uid);
  if (!current()) return;
  state.code = code;
  renderFriendCode();

  /** Keeps a freshly attached listener, or stops it at once if the account changed. */
  const keep = (stop) => {
    if (current()) {
      unsubscribes.push(stop);
    } else {
      try {
        stop();
      } catch {
        /* see stopWatching */
      }
    }
  };

  keep(
    await watchBoardRow(
      user.uid,
      (row) => {
        if (!current()) return;
        const joined = row.exists && !state.row.exists;
        state.row = { ...row, loaded: true };
        // Groups need a published account; the first row that says so opens them.
        if (joined || !state.groups.loaded) refreshGroups();
        // The friend-code index is only published once some client has opted this account
        // in — a board row existing IS that opt-in. Registering it earlier would put a
        // uid in a public collection for someone who never agreed to be findable.
        if (row.exists && !row.fromCache && codeRegisteredFor !== user.uid) {
          codeRegisteredFor = user.uid;
          registerFriendCode(user.uid);
        }
        renderAll();
      },
      (error) => current() && reportSection('history-error', explainError(error)),
    ),
  );
  keep(
    await watchDays(
      user.uid,
      (days) => {
        if (!current()) return;
        state.days = { ...days, loaded: true };
        renderAll();
      },
      (error) => {
        if (!current()) return;
        // A failed listener never delivers a snapshot, so without this flag every section
        // waiting on the first one would shimmer forever above the error that explains why.
        state.days = { ...state.days, failed: true };
        reportSection('history-error', explainError(error));
        renderAll();
      },
    ),
  );
  keep(
    await watchFriends(
      user.uid,
      (friends) => {
        if (!current()) return;
        state.friends = friends;
        refreshFriendProfiles();
        if (state.boardKind === 'friends') refreshBoard();
        renderFriends();
      },
      (error) => current() && reportSection('friends-error', explainError(error)),
    ),
  );
  keep(
    await watchSharedLimit(
      user.uid,
      (limit) => {
        if (!current()) return;
        state.limit = limit;
        renderAll();
      },
      // With older rules deployed this read is refused; the page then knows no limit,
      // which is what it showed before sharing existed.
      () => {},
    ),
  );
  if (!current()) return;

  refreshBoard();
  loadLastWeekPodium();
  renderAll();
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
  renderToday(derived);
  renderHistory(derived);
  renderRecords(derived);
  renderReport();
  renderBoard();
  renderFriends();
  renderGroups();
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
      'Offline, and this browser is not letting the page keep a local copy. Private ' +
      'browsing usually does that. Reconnect to see your figures.';
  } else if (state.offline) {
    strip.textContent =
      'Offline. These are the figures saved on this device the last time it was online. ' +
      'Your phone may have logged more since.';
  } else {
    strip.textContent = 'Showing a saved copy while the network catches up.';
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
      class: 'chip-btn',
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

  wireSegmented('report-choice', REPORTS.map((r) => r.label), (index) => {
    state.report = index;
    if (state.user) renderReport();
  });

  wireGroups();

  wireSegmented('board-kind', ['Friends', 'Global'], (index) => {
    state.boardKind = index === 0 ? 'friends' : 'global';
    refreshBoard();
  });

  wireSegmented('board-period', PERIODS.map((p) => p.label), (index) => {
    state.periodId = PERIODS[index].id;
    refreshBoard();
  });

  byId('board-more').addEventListener('click', loadMore);
  byId('board-refresh').addEventListener('click', async () => {
    loadLastWeekPodium();
    await refreshBoard();
    if (!state.board.error) announce('Leaderboard updated.');
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
      announce('The browser blocked copying. The code is selected, so press Ctrl+C.');
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
    }, 'Request sent. They have to accept before you appear on each other’s boards.');
  });
}

// ---------------------------------------------------------------------------------------

wireDashboardControls();
boot();
