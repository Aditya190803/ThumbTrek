/**
 * The whole cross-browser story, in one object.
 *
 * Chrome/Edge/Brave expose `chrome.*`, and under MV3 its async methods return promises.
 * Firefox exposes `browser.*` (promises, spec-shaped) *and* a `chrome.*` alias that is
 * callback-only. Preferring `browser` when it exists therefore gets promises on both
 * engines, which is the only incompatibility that actually reaches this codebase.
 *
 * The rejected alternative was `webextension-polyfill`. It is the standard answer and it is
 * a dependency plus a bundling step, and this extension ships exactly what is in its
 * directory -- no npm, no build (contract §6 makes the same call about the Firebase SDK).
 * Five lines beat 30KB when the only thing being polyfilled is the return type.
 */
export const api = globalThis.browser?.runtime ? globalThis.browser : globalThis.chrome;

/** True inside an extension context that is still alive (a reloaded extension orphans it). */
export function extensionAlive() {
  try {
    return Boolean(api?.runtime?.id);
  } catch {
    return false;
  }
}
