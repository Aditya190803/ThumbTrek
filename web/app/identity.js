// Port of app/src/main/java/com/thumbtrek/app/social/Identity.kt.
//
// docs/sync-protocol.md §4 makes these derivations the spec rather than an implementation
// detail: one Google account is one uid is one friend code, shared by the phone, the
// extension and this page. A client that computes either differently splits the user's
// identity in half — their code stops matching the card in their hand, and the invite link
// they already sent someone resolves to nobody.
//
// The only change from the Kotlin is that SHA-256 arrives through WebCrypto, which is
// async, so friendCode and anonymousHandle return promises. Everything derived from a
// string alone stays synchronous, because the pages need it inside event handlers.

/** Crockford base32: no I, L, O or U, so a code can't be misread or misheard. */
const CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 8 symbols = 40 bits. Collision odds stay negligible well past a million trekkers. */
export const FRIEND_CODE_LENGTH = 8;

async function digest(purpose, uid) {
  const input = new TextEncoder().encode(`thumbtrek:${purpose}:${uid}`);
  return new Uint8Array(await crypto.subtle.digest('SHA-256', input));
}

/** First 8 bytes of SHA-256("thumbtrek:code:" + uid), each masked & 0x1F into the alphabet. */
export async function friendCode(uid) {
  const bytes = await digest('code', uid);
  let code = '';
  for (let i = 0; i < FRIEND_CODE_LENGTH; i++) code += CODE_ALPHABET[bytes[i] & 0x1f];
  return code;
}

export function formatFriendCode(code) {
  return code.length === FRIEND_CODE_LENGTH ? `${code.slice(0, 4)}-${code.slice(4)}` : code;
}

/**
 * Accepts what people actually paste: lower case, spaces, the grouping dash, or the whole
 * `https://thumbtrek.adityamer.dev/i/<code>` invite link.
 *
 * The Unicode property escapes stand in for Kotlin's `Char.isLetterOrDigit`, which is also
 * Unicode-aware: a pasted code with a stray accented character loses the same characters on
 * both clients rather than normalising to two different codes.
 */
export function normalizeFriendCode(input) {
  const afterLastSlash = String(input).trim().split('/').pop();
  return [...afterLastSlash.toUpperCase()]
    .filter((ch) => /[\p{L}\p{N}]/u.test(ch))
    .map((ch) => (ch === 'I' || ch === 'L' ? '1' : ch === 'O' ? '0' : ch === 'U' ? 'V' : ch))
    .join('')
    .slice(0, FRIEND_CODE_LENGTH);
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

/**
 * Identity half of a friend edge: the writer's real Google name and photo, clamped to
 * the lengths firestore.rules enforces. Mirrors SocialRepository.requestIdentityFields —
 * a blank name falls back to 'Trekker' so a rule rejection can never silently drop the
 * whole request batch.
 */
export function edgeIdentity(displayName, photoURL) {
  const name = String(displayName ?? '').slice(0, 64);
  return {
    name: /^\s*$/.test(name) ? 'Trekker' : name,
    photo: String(photoURL ?? '').slice(0, 512),
  };
}

/**
 * The link Identity.kt hands to the phone's share sheet. /i/<code> is rewritten to the
 * invite page in web/vercel.json, so a link the app produced today resolves on the site.
 */
export function inviteLink(code) {
  return `https://thumbtrek.adityamer.dev/i/${code}`;
}

export function inviteMessage(code) {
  return (
    "I'm tracking how far my thumb scrolls with ThumbTrek. Add me with code " +
    `${formatFriendCode(code)} and let's see who treks further this week.\n\n` +
    inviteLink(code)
  );
}
