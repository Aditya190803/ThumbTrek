// The Friends section: the leaderboard, last week's podium, and the friends list.

import { archiveScore, rankEntries, rankOf, scoreOf, scoreText } from './board.js';
import { progressRail } from './charts.js';
import {
  MAX_PAGES,
  currentKeys,
  globalRank,
  loadEdgeIdentities,
  loadGlobalPage,
  loadPodium,
  loadProfiles,
} from './data.js';
import { announce, byId, el, fill, loadingSkeleton, show } from './dom.js';
import { explainError } from './firebase.js';
import { acceptRequest, removeFriend } from './friends.js';
import { anonymousHandle, formatFriendCode, inviteLink } from './identity.js';
import { reportSection, state } from './state.js';
import { addDays, formatDistance, weekKey } from './stats.js';

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

export async function refreshBoard() {
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
      // Private audience: edge handwriting over public rows, self included, so the
      // friends board reads the way friends read each other. Old edges fall through.
      try {
        const edges = await loadEdgeIdentities(state.user.uid);
        const me = state.user.displayName || 'You';
        for (const entry of entries) {
          const edge = entry.uid === state.user.uid
            ? { name: me, photo: state.user.photoURL ?? '' }
            : edges.get(entry.uid);
          if (edge?.name) {
            entry.displayName = edge.name;
            entry.photoUrl = edge.photo ?? '';
          }
        }
      } catch (error) {
        console.warn('ThumbTrek: could not read edge identities.', error);
      }
      state.board = { entries, cursor: null, exhausted: true, pages: 1, loading: false, error: null };
    }
  } catch (error) {
    if (token !== boardRequest) return;
    state.board = { ...state.board, loading: false, error: explainError(error) };
  }
  renderBoard();
}

export async function loadMore() {
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
    announce(`Loaded ${page.entries.length} more people.`);
  } catch (error) {
    if (token !== boardRequest) return;
    state.board = { ...state.board, loading: false, error: explainError(error) };
  }
  renderBoard();
}

export function renderBoard() {
  const keys = currentKeys(state.today);
  const ranked = rankEntries(state.board.entries, state.periodId, keys);
  const target = byId('board-rows');
  target.setAttribute('aria-busy', String(state.board.loading));
  byId('board-refresh').disabled = state.board.loading;
  reportSection('board-error', state.board.error);

  const mine = rankOf(ranked, state.user?.uid);
  const standing = byId('board-standing');
  const rank = state.boardKind === 'global' ? state.myRank ?? mine : mine;
  if (rank) {
    standing.textContent =
      state.boardKind === 'global' ? `You are number ${rank} on the global board.` : `You are number ${rank} among your friends.`;
    show(standing, true);
  } else if (!state.row.loaded) {
    // Neither "not published" nor "not ranked" is true yet — the row has not arrived.
    show(standing, false);
  } else if (state.row.exists) {
    standing.textContent = 'You are not on this board for this period yet.';
    show(standing, true);
  } else {
    standing.textContent =
      'Your score is not published. Turn leaderboards on in the phone app. This page ' +
      'cannot publish it for you.';
    show(standing, true);
  }

  if (state.board.loading && !ranked.length) {
    fill(target, el('li', { class: 'empty' }, loadingSkeleton('Reading the board')));
  } else if (!ranked.length) {
    fill(
      target,
      state.boardKind === 'friends'
        ? emptyState(
            'Nobody here but you',
            'Friend codes are the only way onto this board. Send yours to someone to ' +
              'start comparing.',
          )
        : emptyState(
            'The global board is empty',
            'Nobody has published a score for this period yet. Try again later, or ' +
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
  more.textContent = state.board.loading ? 'Loading' : 'Load more';

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
    el('span', { class: `lane-no rank-${Math.min(entry.rank, 4)}`, text: `${entry.rank}` }),
    el(
      'span',
      { class: 'lane-who' },
      avatar(entry.photoUrl, entry.displayName),
      el('span', { class: 'who-name', text: name }),
    ),
    el('span', { class: 'lane-dist', text: value }),
    state.boardKind === 'friends' && !isMe
      ? el('button', {
          class: 'btn btn-ghost btn-small',
          type: 'button',
          'aria-label': `Remove ${entry.displayName}`,
          onclick: () => confirmRemove(entry),
          text: 'Remove',
        })
      : null,
    progressRail(fraction),
    legacy
      ? el('p', {
          class: 'fine',
          text: 'On an older version of the app that reports screen pixels. Ranked, but ' +
            'there is no comparable distance to show.',
        })
      : null,
  );
}

export function avatar(photoUrl, name) {
  const initial = () =>
    el('span', {
      class: 'avatar avatar-blank',
      'aria-hidden': 'true',
      text: (name || '?').charAt(0).toUpperCase(),
    });
  if (!photoUrl) return initial();
  // Google profile photos refuse hotlinked requests that carry a referrer, and a dead
  // photo URL should fall back to the initial rather than a broken-image glyph.
  const img = el('img', {
    class: 'avatar',
    src: photoUrl,
    alt: '',
    loading: 'lazy',
    width: 32,
    height: 32,
    referrerpolicy: 'no-referrer',
  });
  img.addEventListener('error', () => img.replaceWith(initial()), { once: true });
  return img;
}

function emptyState(title, body) {
  return el('li', { class: 'empty' }, el('h3', { text: title }), el('p', { text: body }));
}

/**
 * Loading reads as the shape of what is coming. The spoken label carries the sentence
 * for screen readers; the shimmer is hidden from them so it is not announced as content.
 */

export async function loadLastWeekPodium() {
  try {
    const rows = await loadPodium(weekKey(addDays(state.today, -7)));
    state.podium = rows;
  } catch (error) {
    console.warn('ThumbTrek: no archive for last week.', error);
    state.podium = [];
  }
  renderPodium();
}

/** Last week's top three, in finishing order, with the medal colour on the lane number. */
function renderPodium() {
  const section = byId('podium');
  show(section, state.podium.length > 0);
  if (!state.podium.length) return;
  fill(
    byId('podium-row'),
    state.podium.slice(0, 3).map((row, index) => {
      const score = archiveScore(row);
      const name = row.displayName || 'Someone';
      return el(
        'li',
        { class: 'board-row' },
        el('span', { class: `lane-no rank-${index + 1}`, text: `${index + 1}` }),
        el(
          'span',
          { class: 'lane-who' },
          avatar(row.photoUrl ?? '', name),
          el('span', { class: 'who-name', text: name }),
        ),
        el('span', { class: 'lane-dist', text: scoreText(score, formatDistance) }),
      );
    }),
  );
}

// ---------------------------------------------------------------------------------------
// Friends
// ---------------------------------------------------------------------------------------

export function renderFriendCode() {
  byId('my-code').textContent = formatFriendCode(state.code);
  byId('invite-link').textContent = inviteLink(state.code);
  byId('invite-link').href = `/i/${state.code}`;
}

/** Names for everyone on either side of the graph, so no row says a raw uid. */
export async function refreshFriendProfiles() {
  const ids = [...state.friends.accepted, ...state.friends.pending].filter(
    (uid) => !state.profiles.has(uid),
  );
  if (!ids.length) return renderFriends();
  try {
    for (const entry of await loadProfiles(ids)) state.profiles.set(entry.uid, entry);
  } catch (error) {
    console.warn('ThumbTrek: could not read friend profiles.', error);
  }
  // Edge handwriting beats the public row: friends see each other's real names while
  // the global board keeps the handle. Same override the phone's friendBoard applies.
  // Anyone with no board row AND no edge identity falls back to the stable pseudonym.
  let edges = new Map();
  try {
    edges = await loadEdgeIdentities(state.user.uid);
  } catch (error) {
    console.warn('ThumbTrek: could not read edge identities.', error);
  }
  for (const uid of ids) {
    const edge = edges.get(uid);
    const current = state.profiles.get(uid);
    if (current && !current.unpublished) {
      if (edge) state.profiles.set(uid, { ...current, displayName: edge.name, photoUrl: edge.photo });
      continue;
    }
    if (state.profiles.has(uid)) continue;
    state.profiles.set(uid, edge
      ? { uid, displayName: edge.name, photoUrl: edge.photo, unpublished: true }
      : {
        uid,
        displayName: await anonymousHandle(uid),
        photoUrl: '',
        unpublished: true,
      });
  }
  renderFriends();
}

export function renderFriends() {
  // The code only resolves once some client has opted in and published the index entry,
  // so say so rather than letting someone send out a code that finds nobody.
  show(
    byId('code-unpublished'),
    Boolean(state.user) && state.row.loaded && !state.row.exists && !state.row.fromCache,
  );
  const requests = state.friends.pending;
  show(byId('requests'), requests.length > 0);
  byId('requests-count').textContent = `${requests.length}`;
  fill(
    byId('requests-rows'),
    requests.map((uid) => {
      const profile = state.profiles.get(uid);
      return el(
        'li',
        { class: 'row person' },
        avatar(profile?.photoUrl ?? '', profile?.displayName ?? 'Someone'),
        el('span', { class: 'who-name', text: profile?.displayName ?? 'Someone' }),
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
      el('li', {
        class: 'row fine',
        text: 'No friends yet. Share your code, or add someone else’s code.',
      }),
    );
  } else {
    fill(
      list,
      state.friends.accepted.map((uid) => {
        const profile = state.profiles.get(uid);
        return el(
          'li',
          { class: 'row person' },
          avatar(profile?.photoUrl ?? '', profile?.displayName ?? 'Someone'),
          el('span', { class: 'who-name', text: profile?.displayName ?? 'Someone' }),
          profile?.unpublished
            ? el('span', { class: 'chip chip-muted', text: 'Not on the board' })
            : null,
          el('button', {
            class: 'btn btn-ghost btn-small',
            type: 'button',
            text: 'Remove',
            onclick: () => confirmRemove({ uid, displayName: profile?.displayName ?? 'this friend' }),
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
export async function act(work, successMessage) {
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
