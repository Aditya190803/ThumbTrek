// The friendship graph — the only thing this page writes.
//
// Reimplements SocialRepository's two-edge protocol exactly, because both endpoints of a
// friendship are documents that either side may write and neither side may forge:
//
//   users/{me}/friends/{them}   = { since, status }
//   users/{them}/friends/{me}   = { since, status }
//
// A request writes BOTH edges as "pending"; accepting flips both to "accepted"; declining
// and removing both delete both. firestore.rules allows a write to an edge only by one of
// its two endpoints, which is what makes "add someone else to their own list" possible
// without letting an outsider touch anybody's friends. Writing one edge and hoping the
// other side notices would leave the request invisible to the person being asked.

import { firebase } from './firebase.js';
import { FRIEND_CODE_LENGTH, friendCode, normalizeFriendCode } from './identity.js';

const STATUS_PENDING = 'pending';
const STATUS_ACCEPTED = 'accepted';

function edgeRef(storeMod, db, owner, other) {
  return storeMod.doc(db, 'users', owner, 'friends', other);
}

/**
 * Publishes the code → uid mapping invites resolve against.
 *
 * Best effort and deliberately conditional: writing it publishes a public record that this
 * uid exists, and docs/sync-protocol.md §5 says nothing is written until the user opts in.
 * So the caller only reaches here when a board row already exists — the opt-in has already
 * happened on some client. A code already claimed by another account must not break
 * anything either, which is why the failure is swallowed the way the phone swallows it.
 */
export async function registerFriendCode(uid) {
  const code = await friendCode(uid);
  try {
    const { db, storeMod } = await firebase();
    await storeMod.setDoc(storeMod.doc(db, 'friendCodes', code), { uid });
  } catch (error) {
    console.warn('ThumbTrek: could not register the friend code index.', error);
  }
  return code;
}

/** Resolves a code to a uid through the invite index, or null when nobody holds it. */
export async function resolveCode(code) {
  const { db, storeMod } = await firebase();
  const snap = await storeMod.getDoc(storeMod.doc(db, 'friendCodes', code));
  const uid = snap.exists() ? snap.data()?.uid : null;
  return typeof uid === 'string' && uid ? uid : null;
}

/**
 * Sends a trek request. Throws with the same sentences the phone shows, because a person
 * comparing the two screens should not have to work out that they mean the same thing.
 */
export async function sendRequest(myUid, input) {
  const code = normalizeFriendCode(input);
  if (code.length !== FRIEND_CODE_LENGTH) {
    throw new Error(
      `That code doesn't look right — friend codes are ${FRIEND_CODE_LENGTH} characters.`,
    );
  }
  if (code === (await friendCode(myUid))) throw new Error("That's your own code.");

  const friend = await resolveCode(code);
  if (!friend) throw new Error('No trekker has that code yet.');
  if (friend === myUid) throw new Error("That's your own code.");

  const { db, storeMod } = await firebase();
  const mine = await storeMod.getDoc(edgeRef(storeMod, db, myUid, friend));
  if (mine.exists()) {
    throw new Error(
      mine.data()?.status === STATUS_PENDING
        ? 'Request already sent — waiting on them.'
        : "You're already trekking together.",
    );
  }

  const edge = { since: storeMod.serverTimestamp(), status: STATUS_PENDING };
  const batch = storeMod.writeBatch(db);
  batch.set(edgeRef(storeMod, db, myUid, friend), edge);
  batch.set(edgeRef(storeMod, db, friend, myUid), edge);
  await batch.commit();
  return friend;
}

/** Accepts a request: flips both pending edges to accepted in one batch. */
export async function acceptRequest(myUid, friendUid) {
  const { db, storeMod } = await firebase();
  // merge, not update: the other side's edge may already be gone if they removed us first,
  // and recreating a bare accepted edge keeps the pair symmetric instead of half-broken.
  const patch = { status: STATUS_ACCEPTED };
  const batch = storeMod.writeBatch(db);
  batch.set(edgeRef(storeMod, db, myUid, friendUid), patch, { merge: true });
  batch.set(edgeRef(storeMod, db, friendUid, myUid), patch, { merge: true });
  await batch.commit();
}

/** Declines a request, or removes an existing friend — both drop both edges. */
export async function removeFriend(myUid, friendUid) {
  const { db, storeMod } = await firebase();
  const batch = storeMod.writeBatch(db);
  batch.delete(edgeRef(storeMod, db, myUid, friendUid));
  batch.delete(edgeRef(storeMod, db, friendUid, myUid));
  await batch.commit();
}
