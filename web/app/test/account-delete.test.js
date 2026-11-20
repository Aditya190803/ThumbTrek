// Account deletion: the archive weeks it clears and the order it deletes in. The order is
// what the rules depend on (a progress or group-code delete reads its group), and it has to
// match the Android client step for step.

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  BATCH_LIMIT, deleteAccountData, deleteAuthUser, groupOps, weekKeysBetween,
} from '../account-delete.js';

test('week keys walk across a year boundary in ISO week-years', () => {
  assert.deepEqual(weekKeysBetween('2025-12-15', '2026-01-12'), [
    '2025-W51', '2025-W52', '2026-W01', '2026-W02', '2026-W03',
  ]);
  // 29 December 2025 is a Monday whose Thursday is 1 January: week one of 2026.
  assert.deepEqual(weekKeysBetween('2025-12-29', '2025-12-31'), ['2026-W01']);
  assert.deepEqual(weekKeysBetween('2026-01-04', '2026-01-05'), ['2026-W01', '2026-W02']);
});

test('week keys include a 53rd week and both ends', () => {
  // 2026 starts on a Thursday, so it has 53 ISO weeks.
  assert.deepEqual(weekKeysBetween('2026-12-27', '2027-01-04'), [
    '2026-W52', '2026-W53', '2027-W01',
  ]);
  assert.deepEqual(weekKeysBetween('2026-11-20', '2026-11-20'), ['2026-W47']);
  assert.equal(weekKeysBetween('2024-11-20', '2026-11-20').length, 105);
});

test('an owner takes the group down, a member only leaves', () => {
  const group = { id: 'g1', owner: 'me', code: 'ABCD1234' };
  assert.deepEqual(groupOps('me', group, ['groups/g1/progress/x__android'], true), [
    { type: 'delete', path: 'groups/g1/progress/x__android' },
    { type: 'delete', path: 'groupCodes/ABCD1234' },
    { type: 'delete', path: 'groups/g1' },
  ]);
  // A missing index entry is left alone: its delete rule would read a document that is not there.
  assert.deepEqual(groupOps('me', group, [], false), [{ type: 'delete', path: 'groups/g1' }]);
  assert.deepEqual(groupOps('you', group, ['groups/g1/progress/you__web'], true), [
    { type: 'delete', path: 'groups/g1/progress/you__web' },
    { type: 'removeMember', path: 'groups/g1', uid: 'you' },
  ]);
});

/** An in-memory Firestore that records every batch it commits. */
function fakeStore(docs) {
  const data = new Map(Object.entries(docs));
  const batches = [];
  const children = (path) => [...data.entries()]
    .filter(([p]) => p.startsWith(`${path}/`) && !p.slice(path.length + 1).includes('/'))
    .map(([p, d]) => ({ path: p, data: d }));
  return {
    batches,
    data,
    async list(path, field, value) {
      return children(path).filter((d) => !field || d.data[field] === value);
    },
    async arrayContains(path, field, value) {
      return children(path).filter((d) => d.data[field]?.includes(value));
    },
    async get(path) {
      return data.get(path) ?? null;
    },
    async commit(ops) {
      assert.ok(ops.length > 0 && ops.length <= BATCH_LIMIT, `batch of ${ops.length}`);
      batches.push(ops);
      for (const op of ops) {
        if (op.type === 'delete') data.delete(op.path);
        else data.get(op.path).members = data.get(op.path).members.filter((m) => m !== op.uid);
      }
    },
  };
}

test('everything the account owns goes, in the protocol order', async () => {
  const store = fakeStore({
    'groups/mine': { owner: 'me', code: 'MINE0000', members: ['me', 'f1'] },
    'groups/mine/progress/me__android': { uid: 'me' },
    'groups/mine/progress/f1__web': { uid: 'f1' },
    'groupCodes/MINE0000': { groupId: 'mine' },
    'groups/theirs': { owner: 'f1', code: 'THEM0000', members: ['f1', 'me'] },
    'groups/theirs/progress/me__android': { uid: 'me' },
    'groups/theirs/progress/f1__android': { uid: 'f1' },
    'groups/other': { owner: 'f1', code: 'OTHR0000', members: ['f1'] },
    'users/me': { friendCode: 'CODE0001' },
    'users/me/friends/f1': { status: 'accepted' },
    'users/f1/friends/me': { status: 'accepted' },
    'friendCodes/CODE0001': { uid: 'me' },
    'friendCodes/CODE0002': { uid: 'someone-else' },
    'users/me/days/2026-11-19__android': { um: 1 },
    'users/me/days/2026-11-19__web': { um: 1 },
    'users/me/settings/limits': { limitUm: 5 },
    'archive/2026-W46/scores/me': { um: 1 },
    'users/f1': { friendCode: 'F1F1F1F1' },
  });

  await deleteAccountData(store, 'me', {
    friendCode: 'CODE0002', // derived code held by someone else: must be skipped
    createdOn: '2026-11-04',
    today: '2026-11-20',
  });

  const paths = store.batches.map((batch) => batch.map((op) => `${op.type} ${op.path}`));
  assert.deepEqual(paths, [
    ['delete groups/mine/progress/me__android', 'delete groups/mine/progress/f1__web',
      'delete groupCodes/MINE0000', 'delete groups/mine'],
    ['delete groups/theirs/progress/me__android', 'removeMember groups/theirs'],
    ['delete users/me/friends/f1', 'delete users/f1/friends/me'],
    ['delete friendCodes/CODE0001'],
    ['delete users/me/days/2026-11-19__android', 'delete users/me/days/2026-11-19__web',
      'delete users/me/settings/limits'],
    ['delete archive/2026-W45/scores/me', 'delete archive/2026-W46/scores/me',
      'delete archive/2026-W47/scores/me'],
    ['delete users/me'],
  ]);

  // What is left is other people's, untouched.
  assert.deepEqual([...store.data.keys()].sort(), [
    'friendCodes/CODE0002', 'groups/other', 'groups/theirs',
    'groups/theirs/progress/f1__android', 'users/f1',
  ]);
  assert.deepEqual(store.data.get('groups/theirs').members, ['f1']);
});

test('a big ledger is split into batches of at most 500, still in order', async () => {
  const docs = {};
  for (let i = 0; i < 1200; i += 1) docs[`users/me/days/d${String(i).padStart(4, '0')}__android`] = {};
  const store = fakeStore(docs);
  await deleteAccountData(store, 'me', { createdOn: '2026-11-20', today: '2026-11-20' });
  const sizes = store.batches.map((b) => b.length);
  assert.deepEqual(sizes, [500, 500, 201, 1, 1]);
  assert.equal(store.batches[2].at(-1).path, 'users/me/settings/limits');
  assert.equal(store.data.size, 0);
});

test('running it again on a deleted account only repeats the blind deletes', async () => {
  const store = fakeStore({});
  await deleteAccountData(store, 'me', { friendCode: 'X', createdOn: '2026-11-20', today: '2026-11-20' });
  assert.deepEqual(store.batches.flat().map((op) => op.path), [
    'users/me/settings/limits', 'archive/2026-W47/scores/me', 'users/me',
  ]);
});

test('the auth user is retried once after re-authentication, and only for a stale sign-in', async () => {
  const calls = [];
  let fail = { code: 'auth/requires-recent-login' };
  await deleteAuthUser({
    remove: async () => {
      calls.push('remove');
      if (fail) {
        const error = fail;
        fail = null;
        throw error;
      }
    },
    reauthenticate: async () => calls.push('reauth'),
  });
  assert.deepEqual(calls, ['remove', 'reauth', 'remove']);

  await assert.rejects(
    deleteAuthUser({
      remove: async () => {
        throw Object.assign(new Error('nope'), { code: 'auth/network-request-failed' });
      },
      reauthenticate: async () => assert.fail('must not re-authenticate'),
    }),
    { code: 'auth/network-request-failed' },
  );
});
