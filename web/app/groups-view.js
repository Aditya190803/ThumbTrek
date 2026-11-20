// The Groups part of the Friends section: private boards and their challenges (§8).
//
// Same model as the phone's GroupsSection: a list of groups divided by lane lines, one of
// them open at a time with its board under it. The owner sets or clears the challenge
// here; every member can leave. Distances come from the phone and the extension.

import { progressRail } from './charts.js';
import { loadGroupProgress, loadMyGroups } from './data.js';
import { announce, byId, el, fill, loadingSkeleton, show } from './dom.js';
import { explainError } from './firebase.js';
import { avatar } from './friends-view.js';
import {
  createGroup, deleteGroup, joinGroup, leaveGroup, setChallenge,
} from './group-store.js';
import {
  CHALLENGE_KINDS, MAX_CHALLENGE_TITLE, MAX_GROUP_NAME, challengeEnd, challengeProblem,
  formatGroupCode, groupStandings, groupWindow, memberCount,
} from './groups.js';
import { state } from './state.js';
import { formatDistance, metersFromUm } from './stats.js';

const CHALLENGE_LENGTHS = [7, 14, 30];

/** Bumped per load, so a slow answer for one group never paints over another. */
let request = 0;

function setError(message) {
  const node = byId('groups-error');
  node.textContent = message;
  show(node, Boolean(message));
}

/** Groups need the account to be publishing: progress only exists once a client syncs. */
function canUseGroups() {
  return Boolean(state.user) && state.row.exists;
}

export async function refreshGroups() {
  if (!canUseGroups()) {
    state.groups = { list: [], loaded: true, open: null, progress: [] };
    renderGroups();
    return;
  }
  const mine = ++request;
  try {
    const list = await loadMyGroups(state.user.uid);
    if (mine !== request) return;
    const open = list.find((g) => g.id === state.groups.open?.id) ?? null;
    state.groups = { ...state.groups, list, loaded: true, open };
    setError('');
    if (open) await openGroup(open, false);
    else renderGroups();
  } catch (error) {
    if (mine !== request) return;
    state.groups = { ...state.groups, loaded: true };
    setError(explainError(error));
    renderGroups();
  }
}

async function openGroup(group, scroll = true) {
  const mine = ++request;
  state.groups = { ...state.groups, open: group, progress: null };
  renderGroups();
  try {
    const progress = await loadGroupProgress(group.id);
    if (mine !== request) return;
    state.groups = { ...state.groups, progress };
  } catch (error) {
    if (mine !== request) return;
    state.groups = { ...state.groups, progress: [] };
    setError(explainError(error));
  }
  renderGroups();
  if (scroll) byId('group-board').scrollIntoView({ block: 'nearest' });
}

/** Runs one group write, says how it went, and reloads the list either way. */
async function groupAct(work, success) {
  if (state.busy) return;
  state.busy = true;
  setError('');
  try {
    await work();
    announce(success);
  } catch (error) {
    setError(explainError(error));
    announce(explainError(error));
  } finally {
    state.busy = false;
  }
  await refreshGroups();
}

// ---------------------------------------------------------------------------------------
// Render
// ---------------------------------------------------------------------------------------

export function renderGroups() {
  const allowed = canUseGroups();
  show(byId('groups-forms'), allowed);
  show(byId('groups-off'), !allowed);
  const list = byId('group-list');
  const { groups } = state;

  if (!allowed) {
    fill(list, null);
    show(byId('group-board'), false);
    return;
  }
  if (!groups.loaded) {
    fill(list, loadingSkeleton('Reading your groups', [70, 55]));
    show(byId('group-board'), false);
    return;
  }
  if (!groups.list.length) {
    fill(
      list,
      el('li', {
        class: 'row fine',
        text: 'No groups yet. Start one and share its code, or join one with a code you were sent.',
      }),
    );
  } else {
    fill(
      list,
      groups.list.map((group) => {
        const open = groups.open?.id === group.id;
        const window = groupWindow(group, state.today);
        return el(
          'li',
          { class: open ? 'row person row-current' : 'row person' },
          el('span', { class: 'who-name', text: group.name }),
          el('span', {
            class: 'row-note',
            text: `${memberCount(group)}. ${group.challenge ? `${window.title}, ${window.status.toLowerCase()}` : 'Counting this week'}`,
          }),
          el('button', {
            class: 'btn btn-ghost btn-small',
            type: 'button',
            'aria-expanded': String(open),
            'aria-controls': 'group-board',
            text: open ? 'Close' : 'Open',
            onclick: () => {
              if (open) {
                state.groups = { ...state.groups, open: null, progress: [] };
                renderGroups();
              } else {
                openGroup(group);
              }
            },
          }),
        );
      }),
    );
  }
  renderBoard();
}

function renderBoard() {
  const board = byId('group-board');
  const group = state.groups.open;
  show(board, Boolean(group));
  if (!group) return;

  const me = state.user.uid;
  const owner = group.owner === me;
  const window = groupWindow(group, state.today);
  byId('group-board-h').textContent = group.name;
  byId('group-board-status').textContent = group.challenge
    ? `${window.title}. ${window.kind === 'least' ? 'Least distance wins' : 'Most distance wins'}. ${window.status}.`
    : 'This week, most distance first. The owner can set a challenge.';
  byId('group-code').textContent = formatGroupCode(group.code);

  const rows = byId('group-rows');
  if (state.groups.progress === null) {
    fill(rows, loadingSkeleton('Reading the board', [80, 64, 72]));
  } else {
    const standings = groupStandings(group, state.groups.progress, window, me);
    const ranked = standings.filter((row) => row.um !== null);
    const top = Math.max(...ranked.map((row) => row.um), 1);
    fill(
      rows,
      standings.map((row, index) => el(
        'li',
        { class: row.isMe ? 'board-row board-row-me' : 'board-row' },
        el('span', {
          class: `lane-no rank-${row.um === null ? 4 : Math.min(index + 1, 4)}`,
          text: row.um === null ? '' : `${index + 1}`,
        }),
        el(
          'span',
          { class: 'lane-who' },
          avatar(row.photo, row.name),
          el('span', { class: 'who-name', text: row.isMe ? `${row.name} (you)` : row.name }),
        ),
        el('span', {
          class: 'lane-dist',
          text: row.um === null ? 'Nothing synced yet' : formatDistance(metersFromUm(row.um)),
        }),
        row.um === null ? null : progressRail(row.um / top),
      )),
    );
  }

  show(byId('group-owner'), owner);
  show(byId('group-leave'), !owner);
  show(byId('challenge-clear'), owner && Boolean(group.challenge));
  if (owner) {
    const form = byId('challenge-form');
    // Prefilled once per group, so a half-typed title survives the board refreshing.
    if (form.dataset.group !== group.id) {
      form.dataset.group = group.id;
      byId('challenge-title').value = group.challenge?.title ?? '';
      byId('challenge-kind').value = group.challenge?.kind ?? 'least';
      byId('challenge-start').value = group.challenge?.startsOn ?? state.today;
      byId('challenge-length').value = '7';
    }
  }
}

// ---------------------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------------------

export function wireGroups() {
  byId('group-name').maxLength = MAX_GROUP_NAME;
  byId('challenge-title').maxLength = MAX_CHALLENGE_TITLE;
  fill(
    byId('challenge-kind'),
    CHALLENGE_KINDS.map((kind) => el('option', { value: kind.id, text: kind.label })),
  );
  fill(
    byId('challenge-length'),
    CHALLENGE_LENGTHS.map((days) => el('option', { value: String(days), text: `${days} days` })),
  );

  byId('group-create').addEventListener('submit', (event) => {
    event.preventDefault();
    const input = byId('group-name');
    groupAct(async () => {
      const group = await createGroup(state.user.uid, input.value);
      input.value = '';
      state.groups = { ...state.groups, open: group };
    }, 'Group started. Share its code to bring people in.');
  });

  byId('group-join').addEventListener('submit', (event) => {
    event.preventDefault();
    const input = byId('group-code-input');
    groupAct(async () => {
      const group = await joinGroup(state.user.uid, input.value);
      input.value = '';
      state.groups = { ...state.groups, open: group };
    }, 'Joined. Your distance appears on its board after your phone or browser next syncs.');
  });

  byId('challenge-form').addEventListener('submit', (event) => {
    event.preventDefault();
    const group = state.groups.open;
    const title = byId('challenge-title').value;
    const startsOn = byId('challenge-start').value;
    const endsOn = challengeEnd(startsOn, Number(byId('challenge-length').value));
    const problem = !startsOn ? 'Pick the first day.' : challengeProblem(title, startsOn, endsOn);
    if (problem) {
      setError(problem);
      return;
    }
    groupAct(
      () => setChallenge(group, { title, kind: byId('challenge-kind').value, startsOn, endsOn }),
      'Challenge set. Everyone in the group sees it on their next refresh.',
    );
  });

  byId('challenge-clear').addEventListener('click', () => {
    const group = state.groups.open;
    groupAct(() => setChallenge(group, null), 'Challenge cleared. The group counts weeks again.');
  });

  byId('group-delete').addEventListener('click', () => {
    const group = state.groups.open;
    const ok = window.confirm(
      `Delete ${group.name}?\n\nThe board and everyone's numbers in it go for good, and its code stops working.`,
    );
    if (!ok) return;
    groupAct(async () => {
      await deleteGroup(group);
      state.groups = { ...state.groups, open: null };
    }, `Deleted ${group.name}.`);
  });

  byId('group-leave').addEventListener('click', () => {
    const group = state.groups.open;
    const ok = window.confirm(
      `Leave ${group.name}?\n\nYour numbers come off its board. You can join again with the code.`,
    );
    if (!ok) return;
    groupAct(async () => {
      await leaveGroup(state.user.uid, group);
      state.groups = { ...state.groups, open: null };
    }, `Left ${group.name}.`);
  });

  byId('group-copy').addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(formatGroupCode(state.groups.open.code));
      announce('Group code copied.');
    } catch {
      announce('The browser blocked copying. The code is on screen.');
    }
  });
}
