import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const chrome = JSON.parse(readFileSync(new URL('../manifest.json', import.meta.url)));
const firefox = JSON.parse(readFileSync(new URL('../manifest.firefox.json', import.meta.url)));

describe('engine manifests', () => {
  it('share name, version and permissions', () => {
    assert.equal(firefox.name, chrome.name);
    assert.equal(firefox.version, chrome.version);
    assert.deepEqual([...firefox.permissions].sort(), [...chrome.permissions].sort());
    assert.deepEqual(firefox.host_permissions, chrome.host_permissions);
    assert.deepEqual(firefox.content_scripts, chrome.content_scripts);
  });

  it('chrome keeps the service worker', () => {
    assert.ok(chrome.background.service_worker.endsWith('service-worker.js'));
    assert.equal(chrome.background.type, 'module');
  });

  it('firefox uses classic scripts, no service worker, no pinned key', () => {
    assert.deepEqual(firefox.background, { scripts: ['background/shared.js'] });
    assert.ok(!('key' in firefox), 'pinned Chrome key must not ship to Firefox');
    assert.equal(firefox.browser_specific_settings.gecko.id, 'thumbtrek@adityamer.dev');
  });
});
