// Firestore reads. Every query in the dashboard lives here so the rules in
// firestore.rules can be checked against one file.
//
// The dashboard is read-only over distance: it never writes a score, a day document or a
// board row. That is not shyness, it is the contract — docs/sync-protocol.md §5 says
// nothing reaches Firestore until the user opts in, and §3 says a client may only write its
// own source. This page measures nothing, so it owns no source and has nothing to publish.
// (The one thing it does write is the friendship graph; that lives in friends.js.)

import { firebase } from './firebase.js';
import { boardEntry, periodById } from './board.js';
import { monthKey, todayIso, weekKey } from './stats.js';

/** Firestore caps `in` queries at 30 values, so friend lists are read in chunks. */
const QUERY_CHUNK = 30;

/** Global board page size; MAX_PAGES bounds how far "load more" can walk. */
export const PAGE_SIZE = 50;
export const MAX_PAGES = 8;

/** The period keys the board is gated on, for the browser's own idea of "now". */
export function currentKeys(today = todayIso()) {
  return { weekKey: weekKey(today), monthKey: monthKey(today) };
}

function chunk(list, size) {
  const out = [];
  for (let i = 0; i < list.length; i += size) out.push(list.slice(i, i + size));
  return out;
}

// ---------------------------------------------------------------------------------------
// My own documents
// ---------------------------------------------------------------------------------------

/**
 * The board row: three combined totals, a name, and the per-source split.
 *
 * A missing document is not an error — it is the normal state for someone who has signed in
 * on the web but never turned leaderboards on from the phone. `fromCache` is passed through
 * so the page can say it is showing a saved copy rather than pretending it is live.
 */
export async function watchBoardRow(uid, onChange, onError) {
  const { db, storeMod } = await firebase();
  return storeMod.onSnapshot(
    storeMod.doc(db, 'users', uid),
    { includeMetadataChanges: true },
    (snap) => onChange({
      exists: snap.exists(),
      data: snap.data() ?? null,
      fromCache: snap.metadata.fromCache,
    }),
    onError,
  );
}

/**
 * The private day ledger, `users/{uid}/days/{date}__{source}`.
 *
 * This subcollection is the whole reason a dashboard can show history: the board row holds
 * three running totals and no past, so no amount of arithmetic on it reproduces a chart.
 * The entire collection is read rather than a trailing window — all-time totals and the
 * personal record need every day, the documents are tiny, and after the first load the
 * persistent cache turns a revisit into a delta.
 */
export async function watchDays(uid, onChange, onError) {
  const { db, storeMod } = await firebase();
  return storeMod.onSnapshot(
    storeMod.collection(db, 'users', uid, 'days'),
    { includeMetadataChanges: true },
    (snap) => {
      const docs = snap.docs.map((d) => {
        const data = d.data() ?? {};
        return {
          id: d.id,
          date: typeof data.date === 'string' ? data.date : d.id.split('__')[0],
          source: typeof data.source === 'string' ? data.source : d.id.split('__')[1] ?? '',
          um: Number(data.um) || 0,
          apps: data.apps && typeof data.apps === 'object' ? data.apps : {},
        };
      });
      onChange({ docs, fromCache: snap.metadata.fromCache, empty: snap.empty });
    },
    onError,
  );
}

// ---------------------------------------------------------------------------------------
// Boards
// ---------------------------------------------------------------------------------------

/**
 * One page of the global board, server-ordered on the µm field.
 *
 * Needs the composite indexes in firestore.indexes.json for the week and month boards.
 * Note what the `orderBy` implies: Firestore never returns a document that lacks the
 * ordered field, so a legacy pixels-only row cannot appear on the global board at all. That
 * is the intended reading of the §1 back-compat note — the fallback keeps old phones
 * visible to their friends, not ranked against everyone.
 */
export async function loadGlobalPage(periodId, cursor) {
  const { db, storeMod } = await firebase();
  const period = periodById(periodId);
  const keys = currentKeys();
  const clauses = [];
  if (period.keyField) {
    clauses.push(storeMod.where(period.keyField, '==', keys[period.keyField]));
  }
  clauses.push(storeMod.orderBy(period.um, 'desc'));
  if (cursor) clauses.push(storeMod.startAfter(cursor));
  clauses.push(storeMod.limit(PAGE_SIZE));

  const snap = await storeMod.getDocs(
    storeMod.query(storeMod.collection(db, 'users'), ...clauses),
  );
  return {
    entries: snap.docs.map((d) => boardEntry(d.id, d.data())),
    cursor: snap.docs.at(-1) ?? null,
    exhausted: snap.size < PAGE_SIZE,
    fromCache: snap.metadata.fromCache,
  };
}

/**
 * Me plus [uids], read by document id and sorted on the client.
 *
 * Friend lists are small enough that chunked `in` queries beat a composite index, and it is
 * the only way a legacy pixels row can still be ranked — see rankEntries in board.js.
 */
export async function loadProfiles(uids) {
  if (!uids.length) return [];
  const { db, storeMod } = await firebase();
  const entries = [];
  for (const group of chunk([...new Set(uids)], QUERY_CHUNK)) {
    const snap = await storeMod.getDocs(
      storeMod.query(
        storeMod.collection(db, 'users'),
        storeMod.where(storeMod.documentId(), 'in', group),
      ),
    );
    for (const d of snap.docs) entries.push(boardEntry(d.id, d.data()));
  }
  return entries;
}

/**
 * "You're #N" without walking the whole board: count the rows that beat you.
 *
 * The `orderBy` is not decoration — it makes the query match the composite index that
 * already exists (weekKey ASC, weekUm DESC) instead of demanding a second one in ascending
 * order. Returns null rather than throwing when the count is unavailable (offline, or the
 * index is missing), and the caller falls back to the position in the loaded pages.
 */
export async function globalRank(periodId, myScore) {
  if (!Number.isFinite(myScore) || myScore <= 0) return null;
  try {
    const { db, storeMod } = await firebase();
    const period = periodById(periodId);
    const keys = currentKeys();
    const clauses = [];
    if (period.keyField) {
      clauses.push(storeMod.where(period.keyField, '==', keys[period.keyField]));
    }
    clauses.push(storeMod.where(period.um, '>', myScore));
    clauses.push(storeMod.orderBy(period.um, 'desc'));
    const snap = await storeMod.getCountFromServer(
      storeMod.query(storeMod.collection(db, 'users'), ...clauses),
    );
    return snap.data().count + 1;
  } catch (error) {
    console.warn('ThumbTrek: could not count the global board.', error);
    return null;
  }
}

/**
 * Last week's top three, from the frozen archive.
 *
 * Two queries, not one: rows written before the unit change carry only `pixels`, and an
 * `orderBy('um')` drops them silently — an empty podium where a real one exists. So the µm
 * ordering runs first and the legacy ordering is the fallback.
 */
export async function loadPodium(archivedWeekKey) {
  const { db, storeMod } = await firebase();
  const scores = storeMod.collection(db, 'archive', archivedWeekKey, 'scores');
  const top = async (field) => {
    const snap = await storeMod.getDocs(
      storeMod.query(scores, storeMod.orderBy(field, 'desc'), storeMod.limit(3)),
    );
    return snap.docs.map((d) => ({ uid: d.id, ...(d.data() ?? {}) }));
  };
  const byUm = await top('um');
  return byUm.length ? byUm : top('pixels');
}

// ---------------------------------------------------------------------------------------
// Friendship edges
// ---------------------------------------------------------------------------------------

/**
 * Real identities as friends declared them, keyed by friend uid — read from the edge
 * docs on MY list, which carry the OTHER side's handwriting. Same source the phone's
 * friendIdentities() reads; absent for old edges and anyone who never re-synced since.
 */
export async function loadEdgeIdentities(uid) {
  const { db, storeMod } = await firebase();
  const snap = await storeMod.getDocs(storeMod.collection(db, 'users', uid, 'friends'));
  const out = new Map();
  for (const d of snap.docs) {
    const name = d.data()?.name;
    if (typeof name === 'string' && name.trim()) {
      out.set(d.id, { name, photo: d.data()?.photo ?? '' });
    }
  }
  return out;
}

/**
 * The friend list, live. Each edge carries `{ since, status }`; an edge written before
 * requests existed has no status at all and reads as accepted, so old friendships survive
 * without a migration.
 */
export async function watchFriends(uid, onChange, onError) {
  const { db, storeMod } = await firebase();
  return storeMod.onSnapshot(
    storeMod.collection(db, 'users', uid, 'friends'),
    { includeMetadataChanges: true },
    (snap) => {
      const accepted = [];
      const pending = [];
      for (const d of snap.docs) {
        (d.data()?.status === 'pending' ? pending : accepted).push(d.id);
      }
      onChange({ accepted, pending, fromCache: snap.metadata.fromCache });
    },
    onError,
  );
}
