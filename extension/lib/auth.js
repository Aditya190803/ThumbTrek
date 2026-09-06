/**
 * Google sign-in → Firebase ID token, over REST.
 *
 * Contract §6 specifies this path for the extension and says why: the Firebase JS SDK's
 * popup sign-in cannot run in an MV3 service worker (no `window`, no opener, no DOM), and
 * REST keeps the extension dependency-free and buildless. The chain is:
 *
 *   chrome.identity.launchWebAuthFlow   -- Google OAuth in a browser-managed window
 *     -> Google id_token (a JWT asserting who the user is, to *our OAuth client*)
 *   identitytoolkit accounts:signInWithIdp
 *     -> Firebase idToken + refreshToken + localId (the uid every client shares)
 *   securetoken.googleapis.com/v1/token
 *     -> a fresh Firebase idToken, because the first one expires in an hour and a
 *        background sync on a 30-minute alarm will outlive it
 *
 * ## The nonce
 *
 * `response_type=id_token` requires a nonce, and Google binds it into the returned JWT. We
 * generate one per attempt and check it on the way back. Without that check, a token minted
 * for a different sign-in attempt would be accepted -- the whole point of the parameter.
 *
 * ## Where the tokens live
 *
 * `chrome.storage.local`, not `chrome.storage.session`. Session storage is cleared when the
 * browser restarts, which would mean re-authenticating every morning for a background sync
 * the user never sees. Local storage for an extension is per-profile and not readable by
 * pages; the refresh token there is the same trust level as a logged-in cookie.
 */

import { api } from './browser.js';
import { loadConfig } from './config.js';

const TOKENS_KEY = 'auth';

/** Refresh this far before expiry, so a sync that starts now finishes on a live token. */
const REFRESH_SKEW_MS = 5 * 60 * 1000;

/** Any failure the user can act on: sign in again, or fix the console. */
export class AuthError extends Error {
  constructor(message, cause) {
    super(message);
    this.name = 'AuthError';
    this.cause = cause;
  }
}

function randomNonce() {
  const bytes = crypto.getRandomValues(new Uint8Array(24));
  return [...bytes].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Decodes a JWT payload. No signature check -- Google's transport did that, and the */
/** token is only ever handed straight back to Google, never trusted as an authorisation. */
function decodeJwt(token) {
  const payload = token.split('.')[1];
  if (!payload) throw new AuthError('Google returned a token we could not read.');
  const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
  return JSON.parse(json);
}

async function readTokens() {
  const stored = await api.storage.local.get(TOKENS_KEY);
  return stored?.[TOKENS_KEY] ?? null;
}

async function writeTokens(tokens) {
  await api.storage.local.set({ [TOKENS_KEY]: tokens });
  return tokens;
}

export async function clearTokens() {
  await api.storage.local.remove(TOKENS_KEY);
}

/** The signed-in account, or null. Cheap: reads storage, never touches the network. */
export async function currentAccount() {
  const tokens = await readTokens();
  if (!tokens?.uid) return null;
  return { uid: tokens.uid, displayName: tokens.displayName, photoUrl: tokens.photoUrl };
}

/**
 * Interactive sign-in. Must be called from a user gesture in an extension page -- Chrome
 * refuses an interactive `launchWebAuthFlow` started by an alarm.
 */
export async function signIn() {
  const config = await loadConfig();
  const redirectUri = api.identity.getRedirectURL();
  const nonce = randomNonce();

  const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  authUrl.searchParams.set('client_id', config.oauthClientId);
  authUrl.searchParams.set('response_type', 'id_token');
  authUrl.searchParams.set('redirect_uri', redirectUri);
  authUrl.searchParams.set('scope', 'openid email profile');
  authUrl.searchParams.set('nonce', nonce);
  // `select_account` rather than the default: a user with several Google accounts must be
  // able to choose which one holds their trek history, and silently reusing the last one is
  // how the identity gets split across two uids.
  authUrl.searchParams.set('prompt', 'select_account');

  let redirect;
  try {
    redirect = await api.identity.launchWebAuthFlow({ url: authUrl.toString(), interactive: true });
  } catch (error) {
    throw new AuthError(
      'Google sign-in was cancelled or blocked. If it closed immediately, the OAuth client '
      + `is probably missing this redirect URI: ${redirectUri}`,
      error,
    );
  }
  if (!redirect) throw new AuthError('Google sign-in was cancelled.');

  // The id_token comes back in the URL *fragment*, not the query string.
  const fragment = new URLSearchParams(new URL(redirect).hash.slice(1));
  const googleIdToken = fragment.get('id_token');
  if (!googleIdToken) {
    throw new AuthError(`Google did not return an ID token (${fragment.get('error') ?? 'no reason given'}).`);
  }
  if (decodeJwt(googleIdToken).nonce !== nonce) {
    throw new AuthError('Google returned a token for a different sign-in attempt.');
  }

  const response = await fetch(
    `https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp?key=${encodeURIComponent(config.apiKey)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        postBody: `id_token=${encodeURIComponent(googleIdToken)}&providerId=google.com`,
        requestUri: redirectUri,
        returnIdpCredential: true,
        returnSecureToken: true,
      }),
    },
  );
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new AuthError(
      `Firebase rejected the Google sign-in: ${body?.error?.message ?? response.status}. `
      + 'Check that Google is enabled under Authentication → Sign-in method, and that the '
      + 'OAuth client id in config.js is listed as an authorised client there.',
      body,
    );
  }

  return writeTokens({
    uid: body.localId,
    idToken: body.idToken,
    refreshToken: body.refreshToken,
    displayName: body.displayName ?? 'Trekker',
    photoUrl: body.photoUrl ?? '',
    expiresAt: Date.now() + Number(body.expiresIn ?? 3600) * 1000,
  });
}

/**
 * A live Firebase ID token, refreshing first if it is close to expiry.
 *
 * Everything that talks to Firestore goes through here, which is what makes the one-hour
 * expiry a non-event: a sync started by an alarm 45 minutes after the last one refreshes on
 * the way in rather than failing with a 401 the user would see as "sync broken".
 */
export async function freshIdToken() {
  const tokens = await readTokens();
  if (!tokens?.refreshToken) throw new AuthError('Not signed in.');
  if (tokens.idToken && tokens.expiresAt - REFRESH_SKEW_MS > Date.now()) return tokens.idToken;

  const config = await loadConfig();
  const response = await fetch(
    `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(config.apiKey)}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        refresh_token: tokens.refreshToken,
      }).toString(),
    },
  );
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    // A revoked or expired refresh token is terminal: clear it so the UI shows "signed
    // out" instead of retrying a dead credential on every alarm until the heat death.
    const reason = body?.error?.message ?? String(response.status);
    if (reason === 'TOKEN_EXPIRED' || reason === 'USER_DISABLED' || reason === 'INVALID_REFRESH_TOKEN') {
      await clearTokens();
      throw new AuthError('Your Google session expired. Sign in again to resume syncing.');
    }
    throw new AuthError(`Could not refresh the sign-in token: ${reason}`, body);
  }

  await writeTokens({
    ...tokens,
    idToken: body.id_token,
    refreshToken: body.refresh_token ?? tokens.refreshToken,
    expiresAt: Date.now() + Number(body.expires_in ?? 3600) * 1000,
  });
  return body.id_token;
}

export async function signOut() {
  await clearTokens();
}
