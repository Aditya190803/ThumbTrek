/**
 * A port of `app/src/main/java/com/thumbtrek/app/social/Identity.kt`, byte for byte.
 *
 * Contract §4: one Google account, one uid, one friend code, shared by every client. These
 * derivations are the spec. A client that computes either of them differently does not
 * produce a "slightly different" code -- it splits the user's identity in half, because the
 * friend code is what invites resolve against and the anonymous handle is the name on the
 * board. There is no server-side reconciliation for that; the only defence is that the
 * derivations are pure, tested, and identical.
 *
 * The Kotlin uses `MessageDigest`; we use WebCrypto's `crypto.subtle.digest`, which is the
 * only SHA-256 available in a service worker without shipping an implementation. It is
 * async, so everything here returns a promise -- the one shape difference from the Kotlin,
 * and the reason the callers await.
 *
 * On the byte masking: Kotlin's `Byte` is signed, so the source reads `bytes[i].toInt() and
 * 0x1F` / `and 0xFF` to get the unsigned value back. A `Uint8Array` element is already
 * unsigned, so the masks below are the same arithmetic with the sign-repair removed.
 */

/** Crockford base32: no I, L, O or U, so a code can't be misread or misheard. */
const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 8 symbols = 40 bits. Collision odds stay negligible well past a million trekkers. */
export const FRIEND_CODE_LENGTH = 8;

async function digest(purpose, uid) {
  const bytes = new TextEncoder().encode(`thumbtrek:${purpose}:${uid}`);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
}

/** First 8 bytes of SHA-256("thumbtrek:code:" + uid), each masked & 0x1F into Crockford. */
export async function friendCode(uid) {
  const bytes = await digest('code', uid);
  let out = '';
  for (let i = 0; i < FRIEND_CODE_LENGTH; i++) out += CODE_ALPHABET[bytes[i] & 0x1f];
  return out;
}

export function formatFriendCode(code) {
  return code.length === FRIEND_CODE_LENGTH ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

/**
 * Accepts what people actually paste: lower case, spaces, the grouping dash, or the whole
 * `https://thumbtrek.app/i/<code>` invite link.
 */
export function normalizeFriendCode(input) {
  const afterSlash = input.trim().split('/').pop();
  let out = '';
  for (const ch of afterSlash.toUpperCase()) {
    // Kotlin filters to letters-or-digits *before* substituting, so a dash or space is
    // dropped rather than mapped. Order matters: doing it the other way round would turn
    // "O" in a URL path into a "0" that was never part of the code.
    if (!/[0-9A-Z]/.test(ch)) continue;
    out += ch === 'I' || ch === 'L' ? '1' : ch === 'O' ? '0' : ch === 'U' ? 'V' : ch;
    if (out.length === FRIEND_CODE_LENGTH) break;
  }
  return out;
}

const ANON_ADJECTIVES = [
  'Silent', 'Feral', 'Midnight', 'Restless', 'Caffeinated', 'Sneaky', 'Blurry',
  'Infinite', 'Rogue', 'Turbo', 'Velvet', 'Nocturnal', 'Bottomless', 'Unbothered',
  'Wandering', 'Relentless',
];

const ANON_NOUNS = [
  'Scroller', 'Thumb', 'Trekker', 'Swiper', 'Lurker', 'Wanderer', 'Nomad',
  'Voyager', 'Flicker', 'Drifter',
];

/** Stable pseudonym for a uid, e.g. "Silent Scroller #4821". */
export async function anonymousHandle(uid) {
  const bytes = await digest('handle', uid);
  const adjective = ANON_ADJECTIVES[bytes[0] % ANON_ADJECTIVES.length];
  const noun = ANON_NOUNS[bytes[1] % ANON_NOUNS.length];
  const number = String(((bytes[2] << 8) | bytes[3]) % 10_000).padStart(4, '0');
  return `${adjective} ${noun} #${number}`;
}

export function inviteLink(code) {
  return `https://thumbtrek.app/i/${code}`;
}
