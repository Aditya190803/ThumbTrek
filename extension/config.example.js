/**
 * ThumbTrek extension — Firebase configuration.
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  COPY THIS FILE TO `config.js` AND FILL IN THE FOUR VALUES BELOW.            │
 * │  Until you do, sync is disabled and the options page says so in plain words. │
 * │  Measurement, history, streaks, records and badges all work without it.      │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * README.md walks through exactly which Firebase / Google Cloud console screen produces
 * each value. None of these are secrets — a Firebase web API key is a public identifier
 * and the security boundary is firestore.rules, not this file — but `config.js` is
 * gitignored anyway so a stale committed copy can't shadow a real one.
 */
export default {
  /**
   * Firebase console → Project settings → General → "Project ID".
   * For this project it really is `thumb-trek`; it is here rather than hard-coded so a
   * fork can point at its own project without editing source.
   */
  projectId: 'REPLACE_WITH_FIREBASE_PROJECT_ID',

  /**
   * Firebase console → Project settings → General → Your apps → Web app → SDK setup and
   * configuration → `apiKey`. Used to authenticate calls to the Identity Toolkit and the
   * secure-token endpoint, not to authorise them.
   *
   * NOTE: no Firebase *Web App* exists in the `thumb-trek` project yet. Registering one
   * (the "</>" button on that screen) is what mints this key and the appId below.
   */
  apiKey: 'REPLACE_WITH_FIREBASE_WEB_API_KEY',

  /**
   * Same screen, `appId` — looks like `1:1234567890:web:abcdef0123456789`.
   * Not needed by any REST call we make; recorded so the config matches what the console
   * hands you and so a future Firebase Installations / Analytics call has it available.
   */
  appId: 'REPLACE_WITH_FIREBASE_WEB_APP_ID',

  /**
   * Google Cloud console → APIs & Services → Credentials → OAuth 2.0 Client IDs.
   *
   * This must be a client whose authorised redirect URIs include this extension's
   * `chrome.identity.getRedirectURL()` value — `https://<extension-id>.chromiumapp.org/`.
   * See README.md § "The OAuth client", which also covers why the extension id has to be
   * pinned with a manifest `key` first, and how the "Chrome Extension" client type differs.
   */
  oauthClientId: 'REPLACE_WITH_OAUTH_CLIENT_ID.apps.googleusercontent.com',
};
