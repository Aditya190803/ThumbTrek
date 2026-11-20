// firestore.rules against the Firestore emulator: the shared limit, groups, group codes,
// progress, entitlements, and the whole account-deletion sequence from
// web/app/account-delete.js, run as the real module through the real SDK.
//
// Run with `npm test` in this folder, which starts the emulator around `node --test`.

import { after, before, beforeEach, describe, test } from 'node:test';
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';

import {
  assertFails, assertSucceeds, initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import * as fs from 'firebase/firestore';

import { deleteAccountData, firestoreStore } from '../../../web/app/account-delete.js';

const {
  arrayRemove, arrayUnion, collection, deleteDoc, doc, getDoc, getDocs, query,
  serverTimestamp, setDoc, setLogLevel, updateDoc, where, writeBatch,
} = fs;

// Every assertFails is a refused write, and the SDK logs each one as a warning.
setLogLevel('error');

let env;

before(async () => {
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '127.0.0.1:8085').split(':');
  env = await initializeTestEnvironment({
    projectId: 'demo-thumbtrek',
    firestore: {
      host,
      port: Number(port),
      rules: readFileSync(new URL('../../../firestore.rules', import.meta.url), 'utf8'),
    },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const db = (uid) => env.authenticatedContext(uid).firestore();

/** Writes documents with the rules off: the state a test starts from. */
async function seed(docs) {
  await env.withSecurityRulesDisabled(async (context) => {
    const store = context.firestore();
    for (const [path, data] of Object.entries(docs)) await setDoc(doc(store, path), data);
  });
}

/** Reads documents with the rules off: what is really left afterwards. */
async function exists(path) {
  let found;
  await env.withSecurityRulesDisabled(async (context) => {
    found = (await getDoc(doc(context.firestore(), path))).exists();
  });
  return found;
}

async function read(path) {
  let data;
  await env.withSecurityRulesDisabled(async (context) => {
    data = (await getDoc(doc(context.firestore(), path))).data();
  });
  return data;
}

const group = (owner, members, code) => ({ name: 'Trekkers', owner, code, members });
const progress = (uid, source) => ({
  uid, source, name: uid, photo: '', windowKey: '2026-W47', um: 1000,
});

// ---------------------------------------------------------------------------------------

describe('settings/limits', () => {
  const limits = { limitUm: 250_000_000, setAt: 1_763_600_000_000, source: 'android' };

  test('the owner writes, reads and deletes it', async () => {
    const ref = doc(db('alice'), 'users/alice/settings/limits');
    await assertSucceeds(setDoc(ref, { ...limits, updatedAt: serverTimestamp() }));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(setDoc(ref, { ...limits, limitUm: 0 }));
    await assertSucceeds(deleteDoc(ref));
  });

  test('nobody else reads or writes it', async () => {
    await seed({ 'users/alice/settings/limits': limits });
    const ref = doc(db('bob'), 'users/alice/settings/limits');
    await assertFails(getDoc(ref));
    await assertFails(setDoc(ref, limits));
    await assertFails(deleteDoc(ref));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/alice/settings/limits')));
  });

  test('a malformed limit is rejected', async () => {
    const mine = db('alice');
    const ref = doc(mine, 'users/alice/settings/limits');
    await assertFails(setDoc(ref, { ...limits, extra: true }));
    await assertFails(setDoc(ref, { ...limits, limitUm: -1 }));
    await assertFails(setDoc(ref, { ...limits, limitUm: 1.5 }));
    await assertFails(setDoc(ref, { ...limits, setAt: '1' }));
    await assertFails(setDoc(ref, { ...limits, source: 'x'.repeat(17) }));
    await assertFails(setDoc(ref, { limitUm: 1, setAt: 1 }));
    await assertFails(setDoc(doc(mine, 'users/alice/settings/other'), limits));
  });
});

// ---------------------------------------------------------------------------------------

describe('groups and group codes', () => {
  async function create(uid, id, code) {
    const store = db(uid);
    const batch = writeBatch(store);
    batch.set(doc(store, 'groups', id), { ...group(uid, [uid], code), createdAt: serverTimestamp() });
    batch.set(doc(store, 'groupCodes', code), { groupId: id });
    return batch.commit();
  }

  test('the owner creates a group and its code in one batch', async () => {
    await assertSucceeds(create('alice', 'g1', 'ABCD1234'));
    assert.equal((await read('groupCodes/ABCD1234')).groupId, 'g1');
  });

  test('a group must be created by its owner with only the owner in it', async () => {
    const store = db('alice');
    await assertFails(setDoc(doc(store, 'groups/g1'), group('bob', ['bob'], 'ABCD1234')));
    await assertFails(setDoc(doc(store, 'groups/g1'), group('alice', ['alice', 'bob'], 'ABCD1234')));
    await assertFails(setDoc(doc(store, 'groups/g1'), group('alice', ['alice'], 'SHORT')));
  });

  test('a code can only be claimed for your own group, with that group\'s own code', async () => {
    await seed({ 'groups/g1': group('alice', ['alice'], 'ABCD1234') });
    await assertFails(setDoc(doc(db('bob'), 'groupCodes/ABCD1234'), { groupId: 'g1' }));
    await assertFails(setDoc(doc(db('alice'), 'groupCodes/WXYZ9999'), { groupId: 'g1' }));
    await assertFails(setDoc(doc(db('bob'), 'groupCodes/NOPE0000'), { groupId: 'missing' }));
    // Re-pointing an existing code is an update, which no client may make.
    await seed({ 'groupCodes/ABCD1234': { groupId: 'g1' } });
    await assertFails(setDoc(doc(db('alice'), 'groupCodes/ABCD1234'), { groupId: 'g1' }));
  });

  test('a code resolves with get, but the collection cannot be listed', async () => {
    await seed({ 'groupCodes/ABCD1234': { groupId: 'g1' } });
    await assertSucceeds(getDoc(doc(db('carol'), 'groupCodes/ABCD1234')));
    await assertFails(getDocs(collection(db('carol'), 'groupCodes')));
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'groupCodes/ABCD1234')));
  });

  test('joining adds exactly yourself', async () => {
    await seed({ 'groups/g1': group('alice', ['alice'], 'ABCD1234') });
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('carol') }));
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('bob', 'carol') }));
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('bob'), name: 'Mine' }));
    await assertSucceeds(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('bob') }));
    // Joining twice is a no-op, not an error.
    await assertSucceeds(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('bob') }));
    assert.deepEqual((await read('groups/g1')).members, ['alice', 'bob']);
  });

  test('a full group takes nobody else', async () => {
    const members = ['alice', ...Array.from({ length: 49 }, (_, i) => `m${i}`)];
    await seed({ 'groups/g1': group('alice', members, 'ABCD1234') });
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayUnion('bob') }));
  });

  test('members read the group, outsiders do not', async () => {
    await seed({ 'groups/g1': group('alice', ['alice', 'bob'], 'ABCD1234') });
    await assertSucceeds(getDoc(doc(db('bob'), 'groups/g1')));
    await assertFails(getDoc(doc(db('carol'), 'groups/g1')));
    await assertSucceeds(getDocs(query(collection(db('bob'), 'groups'), where('members', 'array-contains', 'bob'))));
    await assertFails(getDocs(collection(db('bob'), 'groups')));
  });

  test('a member leaves; the owner cannot, and nobody removes someone else', async () => {
    await seed({ 'groups/g1': group('alice', ['alice', 'bob', 'carol'], 'ABCD1234') });
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayRemove('carol') }));
    await assertFails(updateDoc(doc(db('alice'), 'groups/g1'), { members: arrayRemove('alice') }));
    await assertSucceeds(updateDoc(doc(db('bob'), 'groups/g1'), { members: arrayRemove('bob') }));
    // The owner may remove a member as an owner edit.
    await assertSucceeds(updateDoc(doc(db('alice'), 'groups/g1'), { members: arrayRemove('carol') }));
    assert.deepEqual((await read('groups/g1')).members, ['alice']);
  });

  test('the owner cannot hand the group away or change its code', async () => {
    await seed({ 'groups/g1': group('alice', ['alice', 'bob'], 'ABCD1234') });
    await assertFails(updateDoc(doc(db('alice'), 'groups/g1'), { owner: 'bob' }));
    await assertFails(updateDoc(doc(db('alice'), 'groups/g1'), { code: 'WXYZ9999' }));
    await assertSucceeds(updateDoc(doc(db('alice'), 'groups/g1'), { name: 'Renamed' }));
    await assertFails(updateDoc(doc(db('bob'), 'groups/g1'), { name: 'Mine now' }));
  });

  test('only the owner deletes the group and its code', async () => {
    await seed({
      'groups/g1': group('alice', ['alice', 'bob'], 'ABCD1234'),
      'groupCodes/ABCD1234': { groupId: 'g1' },
    });
    await assertFails(deleteDoc(doc(db('bob'), 'groups/g1')));
    await assertFails(deleteDoc(doc(db('bob'), 'groupCodes/ABCD1234')));
    const store = db('alice');
    const batch = writeBatch(store);
    batch.delete(doc(store, 'groupCodes/ABCD1234'));
    batch.delete(doc(store, 'groups/g1'));
    await assertSucceeds(batch.commit());
    assert.equal(await exists('groups/g1'), false);
    assert.equal(await exists('groupCodes/ABCD1234'), false);
  });
});

// ---------------------------------------------------------------------------------------

describe('group progress', () => {
  beforeEach(async () => {
    await seed({ 'groups/g1': group('alice', ['alice', 'bob'], 'ABCD1234') });
  });

  test('a member writes their own document, keyed uid__source', async () => {
    const store = db('bob');
    await assertSucceeds(setDoc(doc(store, 'groups/g1/progress/bob__android'), progress('bob', 'android')));
    await assertSucceeds(getDocs(collection(store, 'groups/g1/progress')));
    await assertFails(setDoc(doc(store, 'groups/g1/progress/bob__web'), progress('bob', 'android')));
    await assertFails(setDoc(doc(store, 'groups/g1/progress/alice__android'), progress('alice', 'android')));
    await assertFails(setDoc(doc(store, 'groups/g1/progress/bob__android'), { ...progress('bob', 'android'), um: -1 }));
  });

  test('a non-member neither writes nor reads', async () => {
    await seed({ 'groups/g1/progress/bob__android': progress('bob', 'android') });
    const store = db('carol');
    await assertFails(setDoc(doc(store, 'groups/g1/progress/carol__android'), progress('carol', 'android')));
    await assertFails(getDocs(collection(store, 'groups/g1/progress')));
    await assertFails(getDoc(doc(store, 'groups/g1/progress/bob__android')));
  });

  test('the owner deletes anyone\'s, a member only their own', async () => {
    await seed({
      'groups/g1/progress/alice__android': progress('alice', 'android'),
      'groups/g1/progress/bob__android': progress('bob', 'android'),
      'groups/g1/progress/bob__web': progress('bob', 'web'),
    });
    await assertFails(deleteDoc(doc(db('bob'), 'groups/g1/progress/alice__android')));
    await assertSucceeds(deleteDoc(doc(db('bob'), 'groups/g1/progress/bob__web')));
    await assertSucceeds(deleteDoc(doc(db('alice'), 'groups/g1/progress/bob__android')));
  });

  test('a member\'s own documents can be found by uid', async () => {
    await seed({
      'groups/g1/progress/alice__android': progress('alice', 'android'),
      'groups/g1/progress/bob__android': progress('bob', 'android'),
    });
    const snap = await assertSucceeds(
      getDocs(query(collection(db('bob'), 'groups/g1/progress'), where('uid', '==', 'bob'))),
    );
    assert.deepEqual(snap.docs.map((d) => d.id), ['bob__android']);
  });
});

// ---------------------------------------------------------------------------------------

describe('entitlements', () => {
  test('the owner reads theirs; no client writes any', async () => {
    await seed({ 'entitlements/alice': { premium: true, until: 1_800_000_000_000 } });
    await assertSucceeds(getDoc(doc(db('alice'), 'entitlements/alice')));
    await assertFails(getDoc(doc(db('bob'), 'entitlements/alice')));
    await assertFails(getDocs(collection(db('alice'), 'entitlements')));
    await assertFails(setDoc(doc(db('alice'), 'entitlements/alice'), { premium: true }));
    await assertFails(setDoc(doc(db('bob'), 'entitlements/bob'), { premium: true }));
    await assertFails(updateDoc(doc(db('alice'), 'entitlements/alice'), { until: 0 }));
    await assertFails(deleteDoc(doc(db('alice'), 'entitlements/alice')));
  });
});

// ---------------------------------------------------------------------------------------

describe('account deletion (web/app/account-delete.js)', () => {
  const row = (code) => ({
    displayName: 'Someone', photoUrl: '', friendCode: code, weekKey: '2026-W47', monthKey: '2026-11',
    weekUm: 1, monthUm: 1, totalUm: 1,
  });
  const edge = { status: 'accepted' };

  // alice owns g1 (with bob) and belongs to g2 (bob's). carol is alice's friend, dave sent
  // her a request. Every account has its own data that must survive someone else's deletion.
  const world = {
    'users/alice': row('ALICE001'),
    'users/bob': row('BOB00001'),
    'users/carol': row('CAROL001'),
    'friendCodes/ALICE001': { uid: 'alice' },
    'friendCodes/BOB00001': { uid: 'bob' },
    'groups/g1': group('alice', ['alice', 'bob'], 'GROUP001'),
    'groupCodes/GROUP001': { groupId: 'g1' },
    'groups/g1/progress/alice__android': progress('alice', 'android'),
    'groups/g1/progress/bob__android': progress('bob', 'android'),
    'groups/g2': group('bob', ['bob', 'alice', 'carol'], 'GROUP002'),
    'groupCodes/GROUP002': { groupId: 'g2' },
    'groups/g2/progress/alice__android': progress('alice', 'android'),
    'groups/g2/progress/alice__web': progress('alice', 'web'),
    'groups/g2/progress/bob__android': progress('bob', 'android'),
    'groups/g2/progress/carol__web': progress('carol', 'web'),
    'users/alice/friends/carol': edge,
    'users/carol/friends/alice': edge,
    'users/alice/friends/dave': { status: 'pending' },
    'users/dave/friends/alice': { status: 'pending' },
    'users/bob/friends/carol': edge,
    'users/carol/friends/bob': edge,
    'users/alice/days/2026-11-19__android': { date: '2026-11-19', source: 'android', um: 5 },
    'users/alice/days/2026-11-19__web': { date: '2026-11-19', source: 'web', um: 5 },
    'users/alice/days/2026-11-20__android': { date: '2026-11-20', source: 'android', um: 5 },
    'users/alice/settings/limits': { limitUm: 1, setAt: 1, source: 'android' },
    'users/bob/days/2026-11-19__android': { date: '2026-11-19', source: 'android', um: 5 },
    'users/bob/settings/limits': { limitUm: 1, setAt: 1, source: 'web' },
    'archive/2026-W45/scores/alice': { displayName: 'a', photoUrl: '', um: 1 },
    'archive/2026-W45/scores/bob': { displayName: 'b', photoUrl: '', um: 1 },
    'archive/2026-W46/scores/bob': { displayName: 'b', photoUrl: '', um: 1 },
    'entitlements/alice': { premium: true },
    'entitlements/bob': { premium: true },
  };

  async function remaining() {
    const out = [];
    for (const path of Object.keys(world)) if (await exists(path)) out.push(path);
    return out;
  }

  const span = { createdOn: '2026-09-01', today: '2026-11-20' };

  test('a group owner deletes everything they own, and only that', async () => {
    await seed(world);
    const store = firestoreStore(db('alice'), fs);
    await assertSucceeds(deleteAccountData(store, 'alice', { friendCode: 'ALICE001', ...span }));

    const left = await remaining();
    // Only the entitlement survives, because no client may delete it.
    assert.deepEqual(left.filter((p) => p.includes('alice')), ['entitlements/alice']);
    // The group alice owned is gone for everyone, bob's numbers in it too.
    for (const gone of ['groups/g1', 'groupCodes/GROUP001', 'groups/g1/progress/bob__android']) {
      assert.ok(!left.includes(gone), gone);
    }
    // bob's group is still there, without her.
    assert.deepEqual((await read('groups/g2')).members, ['bob', 'carol']);
    assert.deepEqual(
      left.filter((p) => p.startsWith('groups/g2')),
      ['groups/g2', 'groups/g2/progress/bob__android', 'groups/g2/progress/carol__web'],
    );
    // Other people's own data is untouched.
    for (const kept of ['users/bob', 'users/carol', 'users/bob/friends/carol', 'users/carol/friends/bob',
      'users/bob/days/2026-11-19__android', 'archive/2026-W45/scores/bob', 'friendCodes/BOB00001']) {
      assert.ok(left.includes(kept), kept);
    }
  });

  test('a plain member deletes their account, leaving the owner\'s group standing', async () => {
    await seed(world);
    const store = firestoreStore(db('bob'), fs);
    await assertSucceeds(deleteAccountData(store, 'bob', { friendCode: 'BOB00001', ...span }));

    const left = await remaining();
    assert.deepEqual(left.filter((p) => p.includes('bob')), ['entitlements/bob']);
    assert.deepEqual((await read('groups/g1')).members, ['alice']);
    assert.ok(left.includes('groups/g1/progress/alice__android'));
    // bob owned g2, so it went with him, alice's and carol's progress included.
    assert.ok(!left.some((p) => p.startsWith('groups/g2') || p === 'groupCodes/GROUP002'));
    assert.ok(!left.includes('users/carol/friends/bob'));
    assert.ok(left.includes('users/carol/friends/alice'));
  });

  test('a member-only account with a missing friend code and no archive rows', async () => {
    await seed({
      'groups/g1': group('alice', ['alice', 'erin'], 'GROUP001'),
      'groups/g1/progress/erin__web': progress('erin', 'web'),
      'groups/g1/progress/alice__web': progress('alice', 'web'),
      'users/erin/friends/alice': { status: 'pending' },
      'users/alice/friends/erin': { status: 'pending' },
      // A code somebody else holds must be left alone, not fail the run.
      'friendCodes/ERIN0001': { uid: 'mallory' },
    });
    const store = firestoreStore(db('erin'), fs);
    await assertSucceeds(deleteAccountData(store, 'erin', { friendCode: 'ERIN0001', ...span }));
    assert.deepEqual((await read('groups/g1')).members, ['alice']);
    assert.equal(await exists('groups/g1/progress/erin__web'), false);
    assert.equal(await exists('groups/g1/progress/alice__web'), true);
    assert.equal(await exists('users/alice/friends/erin'), false);
    assert.equal(await exists('friendCodes/ERIN0001'), true);
    // Running it again, as the dashboard offers after a failure, still succeeds.
    await assertSucceeds(deleteAccountData(store, 'erin', { friendCode: 'ERIN0001', ...span }));
  });

  test('nobody can run the sequence against someone else', async () => {
    await seed(world);
    const store = firestoreStore(db('mallory'), fs);
    await assertFails(deleteAccountData(store, 'alice', { friendCode: 'ALICE001', ...span }));
    assert.equal(await exists('users/alice'), true);
  });
});
