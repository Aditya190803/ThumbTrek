/**
 * Best-effort bridge to the Linux desktop client over native messaging.
 *
 * When the extension runs inside a browser the user linked with
 * `thumbtrek extension install`, a `com.thumbtrek.native` host is registered
 * and every counted batch is forwarded there with per-domain µm — the
 * desktop daemon stores it and suppresses its own wheel-event guess for that
 * browser, so the same scroll is never counted twice.
 *
 * When there is no host (every other platform, most installs) the first
 * `connectNative` throws, we back off for the rest of the worker's life, and
 * everything else behaves exactly as before. Nothing here is awaited: ingest
 * must never get slower or fail because the desktop side is absent.
 */
export const NATIVE_HOST = 'com.thumbtrek.native';

let port = null;
let dead = false;

/** Resolved lazily (not via lib/browser.js): extension APIs can appear after
 * this module loads, and tests swap globals per case. */
function runtime() {
  return globalThis.browser?.runtime ?? globalThis.chrome?.runtime;
}

/** Visible for tests; the worker never touches it directly. */
export function __resetNativeBridge() {
  try { port?.disconnect(); } catch { /* already gone */ }
  port = null;
  dead = false;
}

export function forwardToDesktop(payload) {
  if (dead) return false;
  try {
    const rt = runtime();
    if (!rt?.connectNative) {
      dead = true;
      return false;
    }
    if (!port) {
      port = rt.connectNative(NATIVE_HOST);
    }
    port.postMessage(payload);
    return true;
  } catch {
    dead = true;
    try { port?.disconnect(); } catch { /* already gone */ }
    port = null;
    return false;
  }
}
