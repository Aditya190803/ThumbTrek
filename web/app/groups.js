// Private groups and challenges, the pure half (docs/sync-protocol.md §8). A port of
// app/src/main/java/com/thumbtrek/app/social/Groups.kt.
//
// This file is shared. extension/lib/groups.js and web/app/groups.js are byte-identical,
// and a test in each suite fails if they drift. It imports nothing for that reason.

export const MAX_GROUP_MEMBERS = 50;
export const MAX_GROUP_NAME = 40;
export const MAX_CHALLENGE_TITLE = 60;
export const MAX_CHALLENGE_DAYS = 92;
export const GROUP_CODE_LENGTH = 8;

/** Lowest distance wins in 'least': the point of a limit, played against friends. */
export const CHALLENGE_KINDS = [
  { id: 'least', label: 'Scroll the least' },
  { id: 'most', label: 'Scroll the most' },
];

const MS_PER_DAY = 86_400_000;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const DATE = /^\d{4}-\d{2}-\d{2}$/;

const pad2 = (n) => String(n).padStart(2, '0');

function epochDay(key) {
  const [y, m, d] = key.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / MS_PER_DAY;
}

function keyOf(day) {
  const date = new Date(day * MS_PER_DAY);
  return `${date.getUTCFullYear()}-${pad2(date.getUTCMonth() + 1)}-${pad2(date.getUTCDate())}`;
}

function mondayOf(key) {
  const day = epochDay(key);
  return keyOf(day - (((day + 3) % 7) + 7) % 7);
}

/** ISO-8601 week id, e.g. "2026-W46". The Thursday of a week decides its year. */
function weekKey(key) {
  const thursday = epochDay(mondayOf(key)) + 3;
  const year = new Date(thursday * MS_PER_DAY).getUTCFullYear();
  const firstThursday = epochDay(mondayOf(`${year}-01-04`)) + 3;
  return `${year}-W${pad2(Math.round((thursday - firstThursday) / 7) + 1)}`;
}

function shortDay(key) {
  const [, m, d] = key.split('-').map(Number);
  return `${d} ${MONTHS[m - 1]}`;
}

/** A group document as the clients use it, tolerant of a half-written one. */
export function parseGroup(id, data) {
  if (!data) return null;
  const raw = data.challenge;
  const challenge = raw && DATE.test(raw.startsOn ?? '') && DATE.test(raw.endsOn ?? '')
    ? {
      title: typeof raw.title === 'string' ? raw.title : '',
      kind: raw.kind === 'most' ? 'most' : 'least',
      startsOn: raw.startsOn,
      endsOn: raw.endsOn,
    }
    : null;
  return {
    id,
    name: typeof data.name === 'string' ? data.name : '',
    owner: typeof data.owner === 'string' ? data.owner : '',
    code: typeof data.code === 'string' ? data.code : '',
    members: Array.isArray(data.members) ? data.members.filter((m) => typeof m === 'string') : [],
    challenge,
  };
}

/**
 * The window a group counts on `today`. A challenge that has not started or has already
 * ended still owns the window; the group returns to plain weeks only when it is cleared.
 */
export function groupWindow(group, today) {
  const { challenge } = group;
  if (!challenge) {
    const monday = mondayOf(today);
    return {
      key: weekKey(today),
      from: monday,
      to: keyOf(epochDay(monday) + 6),
      kind: 'most',
      title: 'This week',
      status: 'This week',
    };
  }
  let status;
  if (today < challenge.startsOn) status = `Starts ${shortDay(challenge.startsOn)}`;
  else if (today > challenge.endsOn) status = `Ended ${shortDay(challenge.endsOn)}`;
  else {
    const left = epochDay(challenge.endsOn) - epochDay(today);
    status = left === 0 ? 'Last day' : left === 1 ? '1 day left' : `${left} days left`;
  }
  return {
    key: `${challenge.startsOn}..${challenge.endsOn}`,
    from: challenge.startsOn,
    to: challenge.endsOn,
    kind: challenge.kind,
    title: challenge.title,
    status,
  };
}

/** One source's distance inside `window`, from its own Map<'yyyy-MM-dd', µm>. */
export function windowUm(dayUm, window) {
  let sum = 0;
  for (const [date, um] of dayUm) {
    if (DATE.test(date) && date >= window.from && date <= window.to) sum += um;
  }
  return sum;
}

/**
 * Ranks a group. A member's sources are summed, but only documents stamped with the
 * current window count. Members with nothing published go last whichever way the challenge
 * runs: an empty row must not win a 'least' challenge by never having synced.
 *
 * @param progress `{ uid, source, name, photo, windowKey, um }[]`
 */
export function groupStandings(group, progress, window, myUid) {
  const members = new Set(group.members);
  const current = new Map();
  const named = new Map();
  for (const doc of progress) {
    if (!members.has(doc.uid)) continue;
    // Any document names the member, even a stale one: a name beats "Member".
    if (doc.name && !named.has(doc.uid)) named.set(doc.uid, doc);
    if (doc.windowKey !== window.key) continue;
    current.set(doc.uid, (current.get(doc.uid) ?? 0) + (doc.um ?? 0));
    if (doc.name) named.set(doc.uid, doc);
  }
  const rows = group.members.map((uid) => ({
    uid,
    name: named.get(uid)?.name ?? 'Member',
    photo: named.get(uid)?.photo ?? '',
    um: current.has(uid) ? current.get(uid) : null,
    isMe: uid === myUid,
  }));
  const byName = (a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
  const ranked = rows.filter((row) => row.um !== null);
  const waiting = rows.filter((row) => row.um === null).sort(byName);
  ranked.sort((a, b) => (window.kind === 'least' ? a.um - b.um : b.um - a.um) || byName(a, b));
  return [...ranked, ...waiting];
}

/** Checks a challenge before it is written; returns the problem in words, or null. */
export function challengeProblem(title, startsOn, endsOn) {
  if (!title.trim()) return 'Give the challenge a name.';
  if (title.length > MAX_CHALLENGE_TITLE) return `Keep the name under ${MAX_CHALLENGE_TITLE} characters.`;
  if (endsOn < startsOn) return 'The last day has to be on or after the first.';
  if (epochDay(endsOn) - epochDay(startsOn) >= MAX_CHALLENGE_DAYS) {
    return `A challenge can run for up to ${MAX_CHALLENGE_DAYS} days.`;
  }
  return null;
}

/** The last day of a challenge that starts on `startsOn` and runs `days` days. */
export function challengeEnd(startsOn, days) {
  return keyOf(epochDay(startsOn) + days - 1);
}

/** A fresh group code: eight Crockford characters, the same shape as a friend code. */
export function newGroupCode(bytes = crypto.getRandomValues(new Uint8Array(GROUP_CODE_LENGTH))) {
  let out = '';
  for (let i = 0; i < GROUP_CODE_LENGTH; i += 1) out += CODE_ALPHABET[bytes[i] & 0x1f];
  return out;
}

/** What people paste: lower case, spaces, a dash. Same substitutions as a friend code. */
export function normalizeGroupCode(input) {
  let out = '';
  for (const ch of String(input ?? '').trim().split('/').pop().toUpperCase()) {
    if (!/[0-9A-Z]/.test(ch)) continue;
    out += ch === 'I' || ch === 'L' ? '1' : ch === 'O' ? '0' : ch === 'U' ? 'V' : ch;
    if (out.length === GROUP_CODE_LENGTH) break;
  }
  return out;
}

export function formatGroupCode(code) {
  return code.length === GROUP_CODE_LENGTH ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

export function memberCount(group) {
  return group.members.length === 1
    ? '1 member'
    : `${group.members.length} of ${MAX_GROUP_MEMBERS} members`;
}
