// Mirrors the identity half of docs/sync-protocol.md §4.
//
// The friend code and the anonymous handle are the one place where "close enough" is fatal:
// a code computed differently here than on the phone is a different code, so the invite the
// user already sent stops resolving and their identity quietly splits in two.
//
// So the expectations are not taken from this implementation. They are recomputed inside the
// test from the spec's own words, using node:crypto rather than WebCrypto — a second,
// independent path through the same derivation. A transcription error in identity.js (a
// wrong mask, an off-by-one into the alphabet, a signed byte) fails here; a bug in SHA-256
// itself is not something this repo can catch, and does not need to.
//
//   node --test web/app/test/identity.test.js

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

import {
  FRIEND_CODE_LENGTH,
  anonymousHandle,
  formatFriendCode,
  friendCode,
  inviteLink,
  inviteMessage,
  normalizeFriendCode,
} from '../identity.js';

const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

const ADJECTIVES = [
  'Silent', 'Feral', 'Midnight', 'Restless', 'Caffeinated', 'Sneaky', 'Blurry',
  'Infinite', 'Rogue', 'Turbo', 'Velvet', 'Nocturnal', 'Bottomless', 'Unbothered',
  'Wandering', 'Relentless',
];
const NOUNS = [
  'Scroller', 'Thumb', 'Trekker', 'Swiper', 'Lurker', 'Wanderer', 'Nomad',
  'Voyager', 'Flicker', 'Drifter',
];

/** The spec, spelled out again: SHA-256("thumbtrek:<purpose>:<uid>"). */
const sha = (purpose, uid) => createHash('sha256').update(`thumbtrek:${purpose}:${uid}`).digest();

/** §4: first 8 bytes, each masked & 0x1F, indexed into the Crockford alphabet. */
function expectedCode(uid) {
  const bytes = sha('code', uid);
  let out = '';
  for (let i = 0; i < 8; i++) out += ALPHABET[bytes[i] & 0x1f];
  return out;
}

/** §4: ADJ[b0 % 16] + " " + NOUN[b1 % 10] + " #" + ((b2<<8 | b3) % 10000), padded to 4. */
function expectedHandle(uid) {
  const b = sha('handle', uid);
  const number = String(((b[2] << 8) | b[3]) % 10_000).padStart(4, '0');
  return `${ADJECTIVES[b[0] % 16]} ${NOUNS[b[1] % 10]} #${number}`;
}

// A spread of uids, including ones shaped like real Firebase uids and ones chosen to walk
// the alphabet: a single fixture can pass while a sign-extension bug hides in byte 0x80+.
const UIDS = [
  'abc',
  'uid-0',
  'kJ8s0PqR2vXy3Zn1Aa2Bb3Cc4Dd5',
  '000000000000000000000000000',
  'ᚠᚢᚦ', // non-ASCII: the digest is over UTF-8 bytes, not code points
  'a'.repeat(200),
];

test('friend codes match the spec byte for byte', async () => {
  for (const uid of UIDS) {
    assert.equal(await friendCode(uid), expectedCode(uid), `uid ${uid}`);
  }
});

test('friend codes are eight Crockford symbols and stable per uid', async () => {
  const code = await friendCode('kJ8s0PqR2vXy3Zn1Aa2Bb3Cc4Dd5');
  assert.equal(code.length, FRIEND_CODE_LENGTH);
  assert.match(code, /^[0-9A-HJKMNP-TV-Z]{8}$/); // no I, L, O or U
  assert.equal(code, await friendCode('kJ8s0PqR2vXy3Zn1Aa2Bb3Cc4Dd5'));
  assert.notEqual(code, await friendCode('kJ8s0PqR2vXy3Zn1Aa2Bb3Cc4Dd6'));
});

test('friend codes are grouped for reading aloud, and only when they are whole', () => {
  assert.equal(formatFriendCode('ABCD1234'), 'ABCD-1234');
  assert.equal(formatFriendCode('ABC'), 'ABC'); // a partial code is not dressed up
  assert.equal(formatFriendCode(''), '');
});

test('friend codes normalize what people paste', () => {
  // These four cases are exactly the ones StatsTest.kt pins on the phone.
  assert.equal(normalizeFriendCode('abcd-1234'), 'ABCD1234');
  assert.equal(normalizeFriendCode(' https://thumbtrek.app/i/abcd1234 '), 'ABCD1234');
  assert.equal(normalizeFriendCode('IOLUABCD'), '101VABCD'); // I/L→1, O→0, U→V
  assert.equal(normalizeFriendCode('waytoolongcode').length, 8);
});

test('normalization survives the ways a code actually arrives', () => {
  assert.equal(normalizeFriendCode('ABCD 1234'), 'ABCD1234'); // read out and typed back
  assert.equal(normalizeFriendCode('abcd 1234'), 'ABCD1234'); // non-breaking space
  assert.equal(normalizeFriendCode('thumbtrek.app/i/ABCD1234'), 'ABCD1234'); // no scheme
  assert.equal(normalizeFriendCode(''), '');
  assert.equal(normalizeFriendCode('/'), '');
});

test('anonymous handles match the spec', async () => {
  for (const uid of UIDS) {
    assert.equal(await anonymousHandle(uid), expectedHandle(uid), `uid ${uid}`);
  }
});

test('anonymous handles are stable and zero-padded', async () => {
  const handle = await anonymousHandle('uid-0');
  assert.match(handle, /^[A-Z][a-z]+ [A-Z][a-z]+ #\d{4}$/);
  assert.equal(handle, await anonymousHandle('uid-0'));
});

test('invite links are the ones the phone already shares', () => {
  assert.equal(inviteLink('ABCD1234'), 'https://thumbtrek.app/i/ABCD1234');
  // The message carries the grouped form, and the link round-trips back through normalize.
  const message = inviteMessage('ABCD1234');
  assert.match(message, /ABCD-1234/);
  assert.equal(normalizeFriendCode(message.split('\n').pop()), 'ABCD1234');
});
