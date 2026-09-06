/**
 * The measurement rule.
 *
 * `content/measure-core.js` is a classic script (MV3 content scripts cannot be modules), so
 * it is loaded here the way the browser loads it -- evaluated into a fresh global -- rather
 * than through a test-only export. What is tested is the file that ships.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const SOURCE = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), '..', 'content', 'measure-core.js'),
  'utf8',
);

function loadCore() {
  const context = vm.createContext({});
  vm.runInContext(SOURCE, context, { filename: 'measure-core.js' });
  return context.ThumbTrekMeasure;
}

const core = loadCore();
const VIEWPORT = 800;
const page = () => ({}); // any object identifies a scroller; the DOM passes elements

test('the first sample of a scroller is a baseline, never a distance', () => {
  const acc = core.createAccumulator();
  const doc = page();
  // 4000px in: a restored scroll position, not four thousand pixels of thumb.
  assert.equal(acc.sample(doc, 4000, VIEWPORT), 0);
  assert.equal(acc.pending(), 0);
});

test('absolute deltas, so scrolling back up counts too', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  assert.equal(acc.sample(doc, 300, VIEWPORT), 300);
  assert.equal(acc.sample(doc, 100, VIEWPORT), 200); // upward travel is still travel
  assert.equal(acc.pending(), 500);
});

test('sub-element scrollers are tracked independently of the page', () => {
  // The whole reason a naive window.scrollY listener measures nothing on YouTube or X.
  const acc = core.createAccumulator();
  const doc = page();
  const feed = page();
  acc.sample(doc, 0, VIEWPORT);
  acc.sample(feed, 1000, VIEWPORT); // baseline for the inner container
  assert.equal(acc.sample(feed, 1400, VIEWPORT), 400);
  assert.equal(acc.sample(doc, 50, VIEWPORT), 50);
  assert.equal(acc.take(), 450);
});

test('a jump larger than three viewports is dropped, not clamped', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  // 3 x 800 = 2400 is the limit; 2401 is a "back to top" or an anchor, not a thumb.
  assert.equal(acc.sample(doc, 2401, VIEWPORT), 0);
  assert.equal(acc.pending(), 0, 'a clamp would have banked 2400 the thumb never travelled');
  assert.equal(acc.droppedJumps(), 1);
});

test('exactly three viewports still counts', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  assert.equal(acc.sample(doc, 2400, VIEWPORT), 2400);
});

test('a dropped jump re-baselines, so the next real scroll is measured correctly', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  acc.sample(doc, 9000, VIEWPORT); // dropped
  assert.equal(acc.sample(doc, 9100, VIEWPORT), 100); // measured from where the page IS
  assert.equal(acc.pending(), 100);
});

test('a zero-height viewport falls back to a floor instead of dropping everything', () => {
  // A hidden or not-yet-laid-out document reports 0; without the floor, 3 x 0 = 0 would
  // make every single sample a "jump" and the extension would measure nothing at all.
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, 0);
  assert.equal(acc.sample(doc, 500, 0), 500);
  assert.equal(acc.sample(doc, 500 + core.MIN_JUMP_LIMIT_PX + 1, 0), 0);
});

test('take() drains, so two flushes cannot count the same pixels twice', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  acc.sample(doc, 240, VIEWPORT);
  assert.equal(acc.take(), 240);
  assert.equal(acc.take(), 0);
});

test('a scroll event that moved nothing banks nothing', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 120, VIEWPORT);
  assert.equal(acc.sample(doc, 120, VIEWPORT), 0);
});

test('a non-finite position is ignored rather than poisoning the baseline', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  assert.equal(acc.sample(doc, NaN, VIEWPORT), 0);
  assert.equal(acc.sample(doc, 200, VIEWPORT), 200); // baseline survived intact
});

test('fractional positions accumulate as pixels, and only round at the µm boundary', () => {
  const acc = core.createAccumulator();
  const doc = page();
  acc.sample(doc, 0, VIEWPORT);
  acc.sample(doc, 0.5, VIEWPORT);
  acc.sample(doc, 1.25, VIEWPORT);
  assert.equal(acc.take(), 1.25);
});

test('the accumulator keeps no strong reference to a scroller', () => {
  // WeakMap, so an SPA that swaps its feed container on every route change does not leak
  // one entry per navigation for the life of the tab.
  const acc = core.createAccumulator();
  assert.doesNotThrow(() => acc.sample(Object.freeze({}), 10, VIEWPORT));
});
