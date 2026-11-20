// Deleting an account: everything this uid owns in Firestore, then the sign-in itself
// (docs/sync-protocol.md §10). Google Play requires it in the app and on the web, and the
// Android client runs the same steps in the same order.
//
// Firestore is reached through a four-function adapter rather than the SDK directly, so
// the order and the week arithmetic can be tested in node against a fake. Every path is a
// plain 'a/b/c' string for the same reason.
//
// The order is not cosmetic. Groups come first because the rules decide progress deletes
// and group-code deletes by reading the group, so the group has to outlive both. The board
// row goes last of the data because it is what the friend-code index hangs off, and the
// auth user goes after everything, since once it is gone no rule says isSelf() again and
// whatever is left over is orphaned for good.

import { addDays, mondayOf, weekKey } from './stats.js';

/** A write batch holds at most 500 operations. */
export const BATCH_LIMIT = 500;

/**
 * Every ISO week key from the week holding `fromIso` to the week holding `toIso`, both
 * inclusive. Walking Mondays rather than counting week numbers is what keeps it right
 * across a year boundary and through a 53-week year.
 */
export function weekKeysBetween(fromIso, toIso) {
  const out = [];
  const last = mondayOf(toIso);
  for (let monday = mondayOf(fromIso); monday <= last; monday = addDays(monday, 7)) {
    out.push(weekKey(monday));
  }
  return out;
}

/**
 * The plan for one group: the owner takes the whole group down, everyone else takes only
 * their own numbers out and leaves. Pure, so the two shapes can be tested on their own.
 *
 * @param group `{ id, owner, code }`
 * @param progress the progress paths to delete. All of them for the owner, the member's
 *   own for a member: the rules read `resource.data.uid` on delete, so only documents that
 *   were queried and exist can go in here.
 * @param codeExists whether `groupCodes/{code}` exists and points at this group. Its delete
 *   rule reads the stored groupId, so a missing index entry would fail the whole batch.
 */
export function groupOps(uid, group, progress, codeExists) {
  const ops = progress.map((path) => ({ type: 'delete', path }));
  if (group.owner === uid) {
    if (codeExists) ops.push({ type: 'delete', path: `groupCodes/${group.code}` });
    ops.push({ type: 'delete', path: `groups/${group.id}` });
  } else {
    ops.push({ type: 'removeMember', path: `groups/${group.id}`, uid });
  }
  return ops;
}

async function commitAll(store, ops) {
  for (let i = 0; i < ops.length; i += BATCH_LIMIT) {
    await store.commit(ops.slice(i, i + BATCH_LIMIT));
  }
}

/**
 * Deletes every document this uid owns, in the protocol's order.
 *
 * @param store `{ list(path, field?, value?), arrayContains(path, field, value), get(path),
 *   commit(ops) }`: `list` returns `[{ path, data }]`, filtered on `field == value` when
 *   given; `get` returns the data or null; `commit` writes one batch of
 *   `{ type: 'delete', path }` and `{ type: 'removeMember', path, uid }`.
 * @param options.friendCode this uid's derived friend code (identity.js), which is where
 *   the dashboard keeps it; the board row's copy is checked as well.
 * @param options.createdOn / options.today `yyyy-MM-dd`, the span of archive weeks to clear.
 */
export async function deleteAccountData(store, uid, { friendCode, createdOn, today }) {
  // 1. Groups, one batch each, so a failure part-way leaves whole groups rather than a
  //    group whose code is gone but whose board is still there.
  const groups = await store.arrayContains('groups', 'members', uid);
  for (const { path, data } of groups) {
    const group = { id: path.split('/')[1], owner: data?.owner, code: data?.code ?? '' };
    const owner = group.owner === uid;
    const progress = owner
      ? await store.list(`${path}/progress`)
      : await store.list(`${path}/progress`, 'uid', uid);
    const index = owner && group.code ? await store.get(`groupCodes/${group.code}`) : null;
    await commitAll(
      store,
      groupOps(uid, group, progress.map((d) => d.path), index?.groupId === group.id),
    );
  }

  // 2. Friend edges, both sides. The rules let either endpoint delete an edge, and the
  //    other side's edge is what keeps me on their list.
  const friends = await store.list(`users/${uid}/friends`);
  await commitAll(
    store,
    friends.flatMap(({ path }) => {
      const other = path.split('/').pop();
      return [
        { type: 'delete', path },
        { type: 'delete', path: `users/${other}/friends/${uid}` },
      ];
    }),
  );

  // 3. The invite index. Its delete rule reads the stored uid, so a code that was never
  //    published, or that another account holds, is skipped rather than failing.
  const row = await store.get(`users/${uid}`);
  const codes = new Set([friendCode, row?.friendCode].filter((c) => typeof c === 'string' && c));
  const mine = [];
  for (const code of codes) {
    const entry = await store.get(`friendCodes/${code}`);
    if (entry?.uid === uid) mine.push({ type: 'delete', path: `friendCodes/${code}` });
  }
  await commitAll(store, mine);

  // 4. The private ledger from every source, and the shared limit. Deleting users/{uid}
  //    would not take its subcollections with it.
  const days = await store.list(`users/${uid}/days`);
  await commitAll(store, [
    ...days.map(({ path }) => ({ type: 'delete', path })),
    { type: 'delete', path: `users/${uid}/settings/limits` },
  ]);

  // 5. The weekly archive. Rows cannot be listed by uid without an index nobody else
  //    needs, and deleting a row that is not there is allowed, so every week the account
  //    could have been on a board is cleared blind.
  await commitAll(
    store,
    weekKeysBetween(createdOn, today).map((week) => ({
      type: 'delete',
      path: `archive/${week}/scores/${uid}`,
    })),
  );

  // 6. The board row.
  await commitAll(store, [{ type: 'delete', path: `users/${uid}` }]);
}

/**
 * Deletes the sign-in. Firebase refuses with auth/requires-recent-login when the last
 * sign-in is more than a few minutes old; one re-authentication and one retry is the whole
 * recovery, and anything else is reported as it is.
 */
export async function deleteAuthUser({ remove, reauthenticate }) {
  try {
    await remove();
  } catch (error) {
    if (error?.code !== 'auth/requires-recent-login') throw error;
    await reauthenticate();
    await remove();
  }
}

/** The adapter over the Firebase JS SDK. Takes the SDK in, so this file imports none of it. */
export function firestoreStore(db, s) {
  const rows = (snap) => snap.docs.map((d) => ({ path: d.ref.path, data: d.data() }));
  return {
    async list(path, field, value) {
      const ref = s.collection(db, path);
      return rows(await s.getDocs(field ? s.query(ref, s.where(field, '==', value)) : ref));
    },
    async arrayContains(path, field, value) {
      return rows(await s.getDocs(s.query(s.collection(db, path), s.where(field, 'array-contains', value))));
    },
    async get(path) {
      const snap = await s.getDoc(s.doc(db, path));
      return snap.exists() ? snap.data() : null;
    },
    async commit(ops) {
      const batch = s.writeBatch(db);
      for (const op of ops) {
        const ref = s.doc(db, op.path);
        if (op.type === 'removeMember') batch.update(ref, { members: s.arrayRemove(op.uid) });
        else batch.delete(ref);
      }
      await batch.commit();
    },
  };
}
