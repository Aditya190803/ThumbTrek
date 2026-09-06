/**
 * Do the documents this client writes actually pass `firestore.rules`?
 *
 * A rules rejection arrives over REST as a bare `PERMISSION_DENIED` with no clue which
 * clause failed, so a write that violates `validDay()` looks exactly like a write from
 * someone who is not signed in. That is a bad thing to discover from a user's bug report,
 * and it is not something a unit test of `buildDayDocument` catches -- the document can be
 * perfectly correct and still be rejected for having 65 keys or an integer stored as a
 * double.
 *
 * So the two predicates are transcribed from `firestore.rules` and run against the payloads
 * this client actually builds, *after* they have been through the typed encoding, since the
 * encoding is where an integer can quietly become a double. Transcribed, not simulated: if
 * the real rules change, this file has to be updated by hand, and the header of each
 * predicate says where its original lives.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { buildDayDocument, combinedTotals, SOURCE } from '../lib/sync.js';
import { fromFields, toFields } from '../lib/firestore.js';

/** `nonNegInt(value)`: `value is int && value >= 0`. */
const nonNegInt = (value) => Number.isInteger(value) && value >= 0;

/** A typed value survived encoding as a Firestore int64, not a double. */
const encodedAsInt = (typed) => typed !== undefined && 'integerValue' in typed;

/** firestore.rules, match /users/{uid}/days/{dayId} → validDay(). */
function validDay(dayId, data) {
  const allowed = ['date', 'source', 'um', 'apps', 'updatedAt'];
  return Object.keys(data).every((key) => allowed.includes(key))
    && typeof data.date === 'string'
    && data.date.length === 10
    && typeof data.source === 'string'
    && data.source.length <= 16
    && dayId === `${data.date}__${data.source}`
    && nonNegInt(data.um)
    && (!('apps' in data)
      || (data.apps !== null && typeof data.apps === 'object' && Object.keys(data.apps).length <= 64));
}

/** firestore.rules, match /users/{uid} → validScore(). */
function validScore(data) {
  const allowed = ['displayName', 'photoUrl', 'friendCode', 'weekKey', 'weekPixels',
    'monthKey', 'monthPixels', 'totalPixels', 'weekUm', 'monthUm', 'totalUm', 'sources'];
  const optionalInt = (field) => !(field in data) || nonNegInt(data[field]);
  return Object.keys(data).every((key) => allowed.includes(key))
    && typeof data.displayName === 'string' && data.displayName.length <= 64
    && typeof data.photoUrl === 'string' && data.photoUrl.length <= 512
    && typeof data.friendCode === 'string' && data.friendCode.length <= 16
    && typeof data.weekKey === 'string' && data.weekKey.length <= 16
    && typeof data.monthKey === 'string' && data.monthKey.length <= 16
    && ['weekPixels', 'monthPixels', 'totalPixels', 'weekUm', 'monthUm', 'totalUm'].every(optionalInt)
    && (!('sources' in data)
      || (data.sources !== null && typeof data.sources === 'object'
        && Object.keys(data.sources).length <= 8));
}

/** What `request.resource.data` is for a merging write: the document after the merge. */
function postMerge(existing, patch) {
  const merged = { ...(existing ?? {}) };
  for (const [key, value] of Object.entries(patch)) {
    merged[key] = value && typeof value === 'object' && !Array.isArray(value)
      ? { ...(merged[key] ?? {}), ...value }
      : value;
  }
  return merged;
}

const IDENTITY = {
  displayName: 'Silent Scroller #4821',
  photoUrl: '',
  friendCode: 'ABCD1234',
  weekKey: '2026-W37',
  monthKey: '2026-09',
};

const SOURCE_ENTRY = {
  weekKey: '2026-W37',
  weekUm: 120_000,
  monthKey: '2026-09',
  monthUm: 480_000,
  totalUm: 9_000_000,
  updatedAt: new Date('2026-09-06T10:00:00Z'),
};

// ————————————————————————————— day documents —————————————————————————————

test('an ordinary day document passes validDay', () => {
  const doc = buildDayDocument('2026-09-06', [
    { domain: 'youtube.com', um: 940_000 },
    { domain: 'instagram.com', um: 900_000 },
  ]);
  const encoded = toFields(doc);
  assert.ok(encodedAsInt(encoded.um), 'um must cross the wire as an int64, not a double');
  assert.ok(encodedAsInt(encoded.apps.mapValue.fields['youtube.com']));
  assert.ok(validDay(`${doc.date}__${SOURCE}`, fromFields(encoded)));
});

test('an empty day and a 100-site day both pass validDay', () => {
  const empty = buildDayDocument('2026-09-06', []);
  assert.ok(validDay('2026-09-06__web', fromFields(toFields(empty))));

  const many = [];
  for (let i = 0; i < 100; i++) many.push({ domain: `s${i}.com`, um: 1000 - i });
  const busy = buildDayDocument('2026-09-06', many);
  // The 64-key cap is a rules clause, not a nicety: 65 keys is PERMISSION_DENIED.
  assert.ok(validDay('2026-09-06__web', fromFields(toFields(busy))));
});

test('a mismatched doc id fails validDay, which is the clause that stops id spoofing', () => {
  const doc = buildDayDocument('2026-09-06', []);
  assert.equal(validDay('2026-09-06__android', fromFields(toFields(doc))), false);
});

// ————————————————————————————— the board row —————————————————————————————

test('the first write of a row is complete, so it passes against a missing document', () => {
  // The contract amendment: a merge carrying only sources.web is rejected when the row
  // does not exist, because validScore sees the post-merge document.
  const sourcesOnly = { sources: { [SOURCE]: SOURCE_ENTRY } };
  assert.equal(validScore(postMerge(null, sourcesOnly)), false, 'sources-only must fail');

  const whatWeWrite = { ...IDENTITY, sources: { [SOURCE]: SOURCE_ENTRY } };
  assert.ok(validScore(postMerge(null, whatWeWrite)), 'our write must succeed');
});

test('our merge leaves another source untouched and still passes', () => {
  const existing = {
    ...IDENTITY,
    displayName: 'From the phone',
    totalPixels: 5_000_000,
    sources: { android: { weekKey: '2026-W37', weekUm: 1, monthKey: '2026-09', monthUm: 2, totalUm: 3 } },
  };
  const merged = postMerge(existing, { ...IDENTITY, sources: { [SOURCE]: SOURCE_ENTRY } });
  assert.ok(validScore(merged));
  assert.deepEqual(merged.sources.android, existing.sources.android, 'android must survive');
  assert.equal(merged.totalPixels, 5_000_000, 'legacy Android fields must survive');
});

test('the combined-totals write passes, and its integers stay integers', () => {
  const merged = postMerge(
    { ...IDENTITY, sources: { [SOURCE]: SOURCE_ENTRY } },
    { ...IDENTITY, ...combinedTotals({ [SOURCE]: SOURCE_ENTRY }, IDENTITY.weekKey, IDENTITY.monthKey) },
  );
  assert.ok(validScore(merged));
  const encoded = toFields({ ...IDENTITY, ...combinedTotals({ [SOURCE]: SOURCE_ENTRY }, IDENTITY.weekKey, IDENTITY.monthKey) });
  for (const field of ['weekUm', 'monthUm', 'totalUm']) {
    assert.ok(encodedAsInt(encoded[field]), `${field} must be an int64`);
  }
});

test('an anonymous handle and a Google display name both fit the length limits', () => {
  // displayName <= 64, photoUrl <= 512, friendCode <= 16.
  assert.ok(validScore(postMerge(null, { ...IDENTITY, displayName: 'Unbothered Wanderer #9999' })));
  assert.ok(validScore(postMerge(null, {
    ...IDENTITY,
    photoUrl: `https://lh3.googleusercontent.com/${'a'.repeat(400)}`,
  })));
  assert.equal(
    validScore(postMerge(null, { ...IDENTITY, displayName: 'x'.repeat(65) })),
    false,
    'a 65-character name would be rejected, so it must be truncated before it is sent',
  );
});

test('sources stays inside the 8-key limit with two clients', () => {
  const merged = postMerge(
    { ...IDENTITY, sources: { android: { totalUm: 1 } } },
    { ...IDENTITY, sources: { [SOURCE]: SOURCE_ENTRY } },
  );
  assert.equal(Object.keys(merged.sources).length, 2);
  assert.ok(validScore(merged));
});
