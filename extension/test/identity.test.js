/**
 * Identity parity. A different derivation here does not produce a slightly different code,
 * it splits the user's identity: their invites stop resolving and their board row appears
 * under a second name.
 *
 * The expected values are computed from the contract's own words rather than copied from a
 * run of this code -- otherwise the test only proves the implementation matches itself.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

import {
  anonymousHandle, formatFriendCode, friendCode, FRIEND_CODE_LENGTH, inviteLink,
  normalizeFriendCode,
} from '../lib/identity.js';

const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** The spec, re-implemented independently with node's crypto, as an oracle. */
function expectedCode(uid) {
  const bytes = createHash('sha256').update(`thumbtrek:code:${uid}`).digest();
  let out = '';
  for (let i = 0; i < 8; i++) out += CODE_ALPHABET[bytes[i] & 0x1f];
  return out;
}

const ADJ = [
  'Silent', 'Feral', 'Midnight', 'Restless', 'Caffeinated', 'Sneaky', 'Blurry', 'Infinite',
  'Rogue', 'Turbo', 'Velvet', 'Nocturnal', 'Bottomless', 'Unbothered', 'Wandering',
  'Relentless',
];
const NOUN = [
  'Scroller', 'Thumb', 'Trekker', 'Swiper', 'Lurker', 'Wanderer', 'Nomad', 'Voyager',
  'Flicker', 'Drifter',
];

function expectedHandle(uid) {
  const b = createHash('sha256').update(`thumbtrek:handle:${uid}`).digest();
  const number = String(((b[2] << 8) | b[3]) % 10000).padStart(4, '0');
  return `${ADJ[b[0] % 16]} ${NOUN[b[1] % 10]} #${number}`;
}

const UIDS = [
  'abc123', '', 'uT8Kx2mQpLzR4vN9', 'a'.repeat(128),
  'firebase-uid-with-dashes', '🙂 unicode uid',
];

test('friendCode matches the SHA-256 / Crockford spec for every uid shape', async () => {
  for (const uid of UIDS) {
    assert.equal(await friendCode(uid), expectedCode(uid), `uid: ${uid}`);
  }
});

test('friendCode is 8 Crockford symbols and is stable', async () => {
  const code = await friendCode('abc123');
  assert.equal(code.length, FRIEND_CODE_LENGTH);
  assert.match(code, /^[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{8}$/);
  // No I, L, O or U: a code has to survive being read aloud.
  assert.ok(!/[ILOU]/.test(code));
  assert.equal(code, await friendCode('abc123'));
});

test('anonymousHandle matches the spec, zero-padded to four digits', async () => {
  for (const uid of UIDS) {
    assert.equal(await anonymousHandle(uid), expectedHandle(uid), `uid: ${uid}`);
  }
  assert.match(await anonymousHandle('abc123'), /^[A-Z][a-z]+ [A-Z][a-z]+ #\d{4}$/);
});

test('formatFriendCode groups into fours, and leaves anything else alone', () => {
  assert.equal(formatFriendCode('ABCD1234'), 'ABCD-1234');
  assert.equal(formatFriendCode('SHORT'), 'SHORT');
});

test('friend codes normalize what people actually paste', () => {
  assert.equal(normalizeFriendCode('abcd-1234'), 'ABCD1234');
  assert.equal(normalizeFriendCode(' https://thumbtrek.adityamer.dev/i/abcd1234 '), 'ABCD1234');
  assert.equal(normalizeFriendCode(' https://thumbtrek.app/i/abcd1234 '), 'ABCD1234'); // old host still parses
  // Crockford disambiguation: I/L -> 1, O -> 0, U -> V
  assert.equal(normalizeFriendCode('IOLUABCD'), '101VABCD');
  assert.equal(normalizeFriendCode('waytoolongcode').length, 8);
  assert.equal(normalizeFriendCode(''), '');
  assert.equal(normalizeFriendCode('a b c d 1 2 3 4'), 'ABCD1234');
});

test('the invite link is the one the phone prints', () => {
  assert.equal(inviteLink('ABCD1234'), 'https://thumbtrek.adityamer.dev/i/ABCD1234');
});
