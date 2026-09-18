import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { __resetNativeBridge, forwardToDesktop } from '../lib/native-bridge.js';

function stubChrome({ connectImpl, postImpl } = {}) {
  const posted = [];
  globalThis.chrome = {
    runtime: {
      connectNative(host) {
        if (connectImpl) return connectImpl(host);
        return {
          postMessage(msg) { (postImpl ?? posted.push.bind(posted))(msg); },
          disconnect() {},
        };
      },
    },
  };
  delete globalThis.browser;
  return posted;
}

describe('native-bridge', () => {
  it('forwards batches to com.thumbtrek.native', () => {
    __resetNativeBridge();
    const seen = {};
    stubChrome({ connectImpl: (host) => { seen.host = host; return { postMessage() {}, disconnect() {} }; } });
    assert.equal(forwardToDesktop({ type: 'trek/batch' }), true);
    assert.equal(seen.host, 'com.thumbtrek.native');
    delete globalThis.chrome;
  });

  it('backs off for the session when no host exists', () => {
    __resetNativeBridge();
    let calls = 0;
    stubChrome({ connectImpl: () => { calls += 1; throw new Error('no host'); } });
    assert.equal(forwardToDesktop({ type: 'trek/batch' }), false);
    assert.equal(forwardToDesktop({ type: 'trek/batch' }), false);
    assert.equal(calls, 1);
    delete globalThis.chrome;
  });

  it('backs off when the api is absent entirely', () => {
    __resetNativeBridge();
    delete globalThis.chrome;
    delete globalThis.browser;
    assert.equal(forwardToDesktop({ type: 'trek/batch' }), false);
  });
});
