// Group membership, the dashboard's other write (docs/sync-protocol.md §8).
//
// The same writes GroupsViewModel makes on the phone: create a group with its code index in
// one batch, join with arrayUnion, leave with arrayRemove. What this page never writes is a
// progress document. It measures nothing, so it has no distance to publish into a group.

import { firebase } from './firebase.js';
import {
  GROUP_CODE_LENGTH, MAX_CHALLENGE_TITLE, MAX_GROUP_NAME, newGroupCode, normalizeGroupCode,
  parseGroup,
} from './groups.js';
import { BILLING_ENFORCED } from './limits.js';

/** True while groups are open to this account: always, until Premium goes on sale (§9). */
async function premiumAllowed(uid) {
  if (!BILLING_ENFORCED) return true;
  const { db, storeMod } = await firebase();
  const snap = await storeMod.getDoc(storeMod.doc(db, 'entitlements', uid));
  return snap.exists() && snap.data()?.premium === true;
}

export async function createGroup(uid, name) {
  const clean = String(name ?? '').trim().slice(0, MAX_GROUP_NAME);
  if (!clean) throw new Error('Give the group a name.');
  if (!(await premiumAllowed(uid))) throw new Error('Groups are part of Premium.');
  const { db, storeMod } = await firebase();
  const ref = storeMod.doc(storeMod.collection(db, 'groups'));
  const code = newGroupCode();
  const batch = storeMod.writeBatch(db);
  batch.set(ref, {
    name: clean,
    owner: uid,
    code,
    members: [uid],
    createdAt: storeMod.serverTimestamp(),
  });
  batch.set(storeMod.doc(db, 'groupCodes', code), { groupId: ref.id });
  await batch.commit();
  return parseGroup(ref.id, { name: clean, owner: uid, code, members: [uid] });
}

/** Joins by code. The code is the only way to learn a group's id. */
export async function joinGroup(uid, input) {
  const code = normalizeGroupCode(input);
  if (code.length !== GROUP_CODE_LENGTH) {
    throw new Error(`That code doesn't look right. Group codes are ${GROUP_CODE_LENGTH} characters.`);
  }
  const { db, storeMod } = await firebase();
  const index = await storeMod.getDoc(storeMod.doc(db, 'groupCodes', code));
  const groupId = index.exists() ? index.data()?.groupId : null;
  if (typeof groupId !== 'string' || !groupId) throw new Error('No group has that code.');
  const ref = storeMod.doc(db, 'groups', groupId);
  // arrayUnion is the whole write: the rules accept exactly "members grew by me".
  await storeMod.updateDoc(ref, { members: storeMod.arrayUnion(uid) });
  const snap = await storeMod.getDoc(ref);
  const group = parseGroup(snap.id, snap.data());
  if (!group) throw new Error('That group no longer exists.');
  return group;
}

/** Takes this account's numbers out with it, from every source, then leaves. */
export async function leaveGroup(uid, group) {
  const { db, storeMod } = await firebase();
  const progress = await storeMod.getDocs(
    storeMod.collection(db, 'groups', group.id, 'progress'),
  );
  const batch = storeMod.writeBatch(db);
  for (const d of progress.docs) {
    if (d.data()?.uid === uid) batch.delete(d.ref);
  }
  batch.update(storeMod.doc(db, 'groups', group.id), { members: storeMod.arrayRemove(uid) });
  await batch.commit();
}

/** Owner only. Progress first: deleting a document leaves its subcollections behind. */
export async function deleteGroup(group) {
  const { db, storeMod } = await firebase();
  const progress = await storeMod.getDocs(
    storeMod.collection(db, 'groups', group.id, 'progress'),
  );
  const batch = storeMod.writeBatch(db);
  for (const d of progress.docs) batch.delete(d.ref);
  batch.delete(storeMod.doc(db, 'groupCodes', group.code));
  batch.delete(storeMod.doc(db, 'groups', group.id));
  await batch.commit();
}

/** Owner only. Null clears the challenge, and the group goes back to counting weeks. */
export async function setChallenge(group, challenge) {
  const { db, storeMod } = await firebase();
  await storeMod.updateDoc(storeMod.doc(db, 'groups', group.id), {
    challenge: challenge
      ? {
        title: challenge.title.trim().slice(0, MAX_CHALLENGE_TITLE),
        kind: challenge.kind === 'most' ? 'most' : 'least',
        startsOn: challenge.startsOn,
        endsOn: challenge.endsOn,
      }
      : storeMod.deleteField(),
  });
}
