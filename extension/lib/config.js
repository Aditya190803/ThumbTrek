/**
 * Loads `config.js`, and refuses to guess.
 *
 * The real Firebase configuration is not in this repository: `app/google-services.json` is
 * gitignored, and no Firebase *Web App* is registered in the `thumb-trek` project, so the
 * `apiKey` and `appId` a web client needs do not exist yet. `config.example.js` is the
 * committed template.
 *
 * The failure this guards against is the quiet one. An unconfigured client that "just
 * doesn't sync" is indistinguishable from a network problem, a signed-out account, or a
 * rules rejection, and someone will spend an afternoon on it. So a missing or placeholder
 * config raises a `ConfigError` naming the exact field and the exact console screen, the
 * options page renders that sentence, and no request is ever attempted with a fake value.
 */

/** Thrown for anything the user can fix in a console. The message is shown verbatim. */
export class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

const REQUIRED = ['projectId', 'apiKey', 'oauthClientId'];

const WHERE = {
  projectId: 'Firebase console → Project settings → General → "Project ID"',
  apiKey: 'Firebase console → Project settings → General → Your apps → Web app → apiKey',
  appId: 'Firebase console → Project settings → General → Your apps → Web app → appId',
  oauthClientId: 'Google Cloud console → APIs & Services → Credentials → OAuth 2.0 Client IDs',
};

let cached;

/**
 * Resolves the config, or throws `ConfigError`.
 *
 * The import is dynamic so that a missing `config.js` is a catchable error rather than a
 * module-graph failure that takes the whole service worker down at registration time --
 * which is what a static `import ... from '../config.js'` would do, leaving the extension
 * looking bricked instead of unconfigured.
 */
export async function loadConfig() {
  if (cached) return cached;
  let module;
  try {
    module = await import('../config.js');
  } catch {
    throw new ConfigError(
      'ThumbTrek is not configured for sync yet. Copy extension/config.example.js to '
      + 'extension/config.js and fill in the four Firebase values — see extension/README.md. '
      + 'Everything except sync works without it.',
    );
  }
  const config = module.default ?? module;
  for (const field of REQUIRED) {
    const value = config?.[field];
    if (typeof value !== 'string' || value === '' || value.startsWith('REPLACE_WITH_')) {
      throw new ConfigError(
        `extension/config.js is missing \`${field}\`. Get it from: ${WHERE[field]}. `
        + 'Sync stays off until every value is filled in.',
      );
    }
  }
  cached = Object.freeze({ ...config });
  return cached;
}

/** True when sync is usable. Never throws, so UI can branch on it without a try. */
export async function isConfigured() {
  try {
    await loadConfig();
    return true;
  } catch {
    return false;
  }
}

/** The `ConfigError` message, or null when the config is fine. For rendering a banner. */
export async function configProblem() {
  try {
    await loadConfig();
    return null;
  } catch (error) {
    return error instanceof ConfigError ? error.message : String(error?.message ?? error);
  }
}
