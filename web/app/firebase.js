// Firebase wiring: SDK loading, config discovery, auth, and the offline cache.
//
// Buildless by design. The SDK comes from Google's CDN as ES modules at a pinned version —
// no npm, no bundler, no package.json anywhere in web/. Pin the version rather than
// tracking a floating one: an SDK that changes under a static page changes it without a
// deploy, and there is no lockfile here to catch it.

const SDK = 'https://www.gstatic.com/firebasejs/12.9.0';

/** Thrown when firebase-config.js is missing or still holds the example placeholders. */
export class NotConfiguredError extends Error {
  constructor(reason) {
    super(reason);
    this.name = 'NotConfiguredError';
  }
}

const PLACEHOLDER = /^YOUR_/;

/**
 * The config is loaded dynamically, not with a static import, for one reason: a static
 * import of a file that does not exist fails the whole module graph before any of our code
 * runs, and the page would go blank instead of explaining what is missing. A clone of this
 * repo has no firebase-config.js, and that has to produce instructions, not a white screen.
 */
async function loadConfig() {
  let module;
  try {
    module = await import('./firebase-config.js');
  } catch {
    throw new NotConfiguredError(
      'web/app/firebase-config.js is missing. Copy firebase-config.example.js to ' +
        'firebase-config.js and fill in the values from the Firebase console.',
    );
  }
  const config = module.firebaseConfig;
  if (!config || typeof config !== 'object') {
    throw new NotConfiguredError(
      'web/app/firebase-config.js does not export a `firebaseConfig` object.',
    );
  }
  const missing = ['apiKey', 'authDomain', 'projectId', 'appId'].filter(
    (key) => !config[key] || PLACEHOLDER.test(String(config[key])),
  );
  if (missing.length) {
    throw new NotConfiguredError(
      `web/app/firebase-config.js still has placeholder values for: ${missing.join(', ')}.`,
    );
  }
  return config;
}

let started = null;

/**
 * Boots Firebase once per page and hands back everything the rest of the app needs.
 *
 * Firestore is opened with a persistent (IndexedDB) cache and the multi-tab manager, which
 * is what makes a revisit paint from disk before the network answers — and what makes the
 * dashboard readable on a train. `initializeFirestore` has to be used instead of
 * `getFirestore` because the cache can only be chosen at construction time.
 */
export function firebase() {
  if (started) return started;
  started = (async () => {
    const config = await loadConfig();
    const [appMod, authMod, storeMod] = await Promise.all([
      import(`${SDK}/firebase-app.js`),
      import(`${SDK}/firebase-auth.js`),
      import(`${SDK}/firebase-firestore.js`),
    ]);
    const app = appMod.initializeApp(config);
    const auth = authMod.getAuth(app);
    let db;
    let persistent = true;
    try {
      db = storeMod.initializeFirestore(app, {
        localCache: storeMod.persistentLocalCache({
          tabManager: storeMod.persistentMultipleTabManager(),
        }),
      });
    } catch (error) {
      // Private-mode Safari and browsers with site data blocked have no IndexedDB. Losing
      // the cache is a degraded experience, not a broken one — fall back to memory so the
      // page still works online, and hand the flag to the UI so it can say that a reload
      // with no network will come back empty rather than letting the user find out.
      console.warn('ThumbTrek: persistent cache unavailable, falling back to memory.', error);
      db = storeMod.initializeFirestore(app, {});
      persistent = false;
    }
    return { app, auth, db, authMod, storeMod, config, persistent };
  })();
  return started;
}

export async function signIn() {
  const { auth, authMod } = await firebase();
  const provider = new authMod.GoogleAuthProvider();
  try {
    await authMod.signInWithPopup(auth, provider);
  } catch (error) {
    // A blocked popup is the single most common sign-in failure on the web, and it is not
    // the user's fault twice: fall through to a full-page redirect, which no blocker eats.
    if (
      error?.code === 'auth/popup-blocked' ||
      error?.code === 'auth/operation-not-supported-in-this-environment'
    ) {
      await authMod.signInWithRedirect(auth, provider);
      return;
    }
    throw error;
  }
}

export async function signOutOfThumbTrek() {
  const { auth, authMod } = await firebase();
  await authMod.signOut(auth);
}

/**
 * Firebase only deletes a user who signed in within the last five minutes. Four is used so
 * a sign-in that is just inside the window does not run out while the data is deleted.
 */
const RECENT_SIGN_IN_MS = 4 * 60 * 1000;

export async function signedInRecently() {
  const { auth } = await firebase();
  const last = Date.parse(auth.currentUser?.metadata?.lastSignInTime ?? '');
  return Number.isFinite(last) && Date.now() - last < RECENT_SIGN_IN_MS;
}

/**
 * Asks Google again for the account that is already signed in. A popup only: a redirect
 * would reload the page half-way through deleting an account. Must run straight from a
 * click, or the browser blocks the popup.
 */
export async function reauthenticate() {
  const { auth, authMod } = await firebase();
  await authMod.reauthenticateWithPopup(auth.currentUser, new authMod.GoogleAuthProvider());
}

export async function deleteSignedInUser() {
  const { auth, authMod } = await firebase();
  await authMod.deleteUser(auth.currentUser);
}

/** Completes a redirect sign-in, if this load is the return leg of one. */
export async function completeRedirectSignIn() {
  const { auth, authMod } = await firebase();
  try {
    await authMod.getRedirectResult(auth);
  } catch (error) {
    console.warn('ThumbTrek: redirect sign-in did not complete.', error);
  }
}

export async function watchAuth(callback) {
  const { auth, authMod } = await firebase();
  return authMod.onAuthStateChanged(auth, callback);
}

/**
 * Turns a Firebase error into something a person can act on. The raw messages name internal
 * APIs and leave the reader with nothing to do; every case here ends in a next step.
 */
export function explainError(error) {
  const code = error?.code ?? '';
  if (code === 'auth/unauthorized-domain') {
    return (
      'This domain is not on the Firebase project\'s authorised list, so Google sign-in ' +
      'refuses to run. Add it under Authentication, Settings, Authorized domains.'
    );
  }
  if (code === 'auth/popup-closed-by-user' || code === 'auth/cancelled-popup-request') {
    return 'Sign-in was closed before it finished. Try again when you are ready.';
  }
  if (code === 'auth/popup-blocked') {
    return (
      'The browser blocked the Google window. Allow popups for this site, then try again.'
    );
  }
  if (code === 'auth/user-mismatch') {
    return 'That was a different Google account. Choose the one this dashboard is signed in with.';
  }
  if (code === 'auth/network-request-failed') {
    return 'The network dropped during sign-in. Check the connection and try again.';
  }
  if (code === 'permission-denied') {
    return (
      'Firestore refused that read. Either the security rules are not deployed yet, or ' +
      'this account is not allowed to see that document.'
    );
  }
  if (code === 'failed-precondition') {
    return (
      'That leaderboard query needs a composite index that has not been created yet. ' +
      'Deploy firestore.indexes.json, or open the link in the browser console to create it.'
    );
  }
  if (code === 'unavailable') {
    return 'Firestore is unreachable, so this is the last copy saved on this device.';
  }
  return error?.message || String(error);
}
