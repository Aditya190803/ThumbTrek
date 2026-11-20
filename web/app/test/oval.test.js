// The lap oval's rules, as DESIGN.md states them. Every caption asserted here is one of
// the spec's own examples, so a client that disagrees with this file disagrees with the
// design.
//
//   node --test web/app/test/oval.test.js

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  appLanes,
  lapScale,
  lapsText,
  ovalDistance,
  ovalGeometry,
  ovalModel,
} from '../oval.js';

test('with no limit a lap is the next whole 400 m, never under 400 m', () => {
  assert.equal(lapScale(0), 400);
  assert.equal(lapScale(1), 400);
  assert.equal(lapScale(400), 400);
  assert.equal(lapScale(401), 800);
  assert.equal(lapScale(1840), 2000);
  assert.equal(lapScale(1840, null), 2000);
  assert.equal(lapScale(1840, 0), 2000);
  assert.equal(lapScale(1840, NaN), 2000);
});

test('with a limit a lap is the limit', () => {
  assert.equal(lapScale(1840, 2000), 2000);
  assert.equal(lapScale(5000, 2000), 2000);
  assert.equal(lapScale(10, 150), 150);
});

test('distances keep two decimals from a kilometre up', () => {
  assert.equal(ovalDistance(0), '0 m');
  assert.equal(ovalDistance(159.6), '160 m');
  assert.equal(ovalDistance(999.6), '1.00 km');
  assert.equal(ovalDistance(2000), '2.00 km');
  assert.equal(ovalDistance(1480), '1.48 km');
});

test('laps show one decimal', () => {
  assert.equal(lapsText(1840), '4.6 laps');
  assert.equal(lapsText(400), '1.0 laps');
  assert.equal(lapsText(0), '0.0 laps');
});

test('no limit: the caption counts laps and nothing is judged', () => {
  const model = ovalModel({ totalMetres: 1840, apps: [{ name: 'Instagram', metres: 920 }] });
  assert.equal(model.limited, false);
  assert.equal(model.state, 'open');
  assert.equal(model.stateText, null);
  assert.equal(model.caption, '4.6 laps, no limit');
  assert.equal(model.scaleMetres, 2000);
  assert.equal(model.lanes[0].tone, 'volt');
  assert.equal(model.lanes[0].percent, 92);
  assert.equal(model.lanes[1].percent, 46);
});

test('under the limit: the total and each app are shares of the limit', () => {
  const model = ovalModel({
    totalMetres: 1480,
    limitMetres: 2000,
    apps: [
      { name: 'Instagram', metres: 640 },
      { name: 'YouTube', metres: 470 },
    ],
  });
  assert.equal(model.state, 'under');
  assert.equal(model.stateText, 'Under the limit');
  assert.equal(model.caption, '520 m left of 2.00 km');
  assert.equal(model.lanes[0].percent, 74);
  assert.equal(model.lanes[0].tone, 'volt');
  assert.equal(model.lanes[1].percent, 32);
  assert.equal(model.lanes[2].percent, 23.5);
});

test('from 80% of the limit the total lane warns and the caption stays as it is', () => {
  const below = ovalModel({ totalMetres: 1599, limitMetres: 2000 });
  assert.equal(below.state, 'under');
  assert.equal(below.lanes[0].tone, 'volt');

  const at = ovalModel({ totalMetres: 1600, limitMetres: 2000 });
  assert.equal(at.state, 'near');
  assert.equal(at.lanes[0].tone, 'warn');

  const near = ovalModel({ totalMetres: 1840, limitMetres: 2000 });
  assert.equal(near.state, 'near');
  assert.equal(near.stateText, 'Close to the limit');
  assert.equal(near.caption, '160 m left of 2.00 km');
  assert.equal(near.lanes[0].percent, 92);
});

test('exactly on the limit is not over it', () => {
  const model = ovalModel({ totalMetres: 2000, limitMetres: 2000 });
  assert.equal(model.state, 'near');
  assert.equal(model.caption, '0 m left of 2.00 km');
  assert.equal(model.lanes[0].percent, 100);
  assert.equal(model.lanes[0].tone, 'warn');
});

test('over the limit: a full lap in danger and the overshoot in the caption', () => {
  const model = ovalModel({
    totalMetres: 2240,
    limitMetres: 2000,
    apps: [{ name: 'Reddit', metres: 2240 }],
  });
  assert.equal(model.state, 'over');
  assert.equal(model.stateText, 'Over the limit');
  assert.equal(model.caption, '240 m over 2.00 km');
  assert.equal(model.lanes[0].percent, 100);
  assert.equal(model.lanes[0].tone, 'danger');
  // An app lane never runs past one lap either.
  assert.equal(model.lanes[1].percent, 100);
});

test('an empty day keeps lane 1 and draws no progress', () => {
  const open = ovalModel({ totalMetres: 0 });
  assert.equal(open.lanes.length, 1);
  assert.deepEqual(open.lanes[0], { lane: 1, name: 'Total', metres: 0, percent: 0, tone: 'volt' });
  assert.equal(open.caption, '0.0 laps, no limit');
  assert.equal(open.scaleMetres, 400);

  const limited = ovalModel({ totalMetres: 0, limitMetres: 2000 });
  assert.equal(limited.state, 'under');
  assert.equal(limited.caption, '2.00 km left of 2.00 km');
  assert.equal(limited.lanes[0].percent, 0);

  assert.equal(ovalModel().lanes.length, 1);
});

test('up to four apps each get a lane, busiest first', () => {
  const lanes = appLanes([
    { name: 'Reddit', metres: 120 },
    { name: 'Instagram', metres: 640 },
    { name: 'X', metres: 250 },
    { name: 'YouTube', metres: 470 },
  ]);
  assert.deepEqual(lanes.map((lane) => lane.name), ['Instagram', 'YouTube', 'X', 'Reddit']);
  assert.ok(lanes.every((lane) => !lane.other));
});

test('beyond four apps the rest are summed into the last lane as Other', () => {
  const model = ovalModel({
    totalMetres: 1600,
    apps: [
      { name: 'a', metres: 500 },
      { name: 'b', metres: 400 },
      { name: 'c', metres: 300 },
      { name: 'd', metres: 200 },
      { name: 'e', metres: 150 },
      { name: 'f', metres: 50 },
    ],
  });
  assert.deepEqual(model.lanes.map((lane) => lane.name), ['Total', 'a', 'b', 'c', 'Other']);
  assert.deepEqual(model.lanes.map((lane) => lane.lane), [1, 2, 3, 4, 5]);
  assert.equal(model.lanes[4].metres, 400);
  assert.equal(model.lanes[4].other, true);
  assert.equal(model.lanes[4].percent, 25);
});

test('apps with no distance or no name get no lane, and ties keep a stable order', () => {
  const lanes = appLanes([
    { name: 'zeta', metres: 10 },
    { name: 'idle', metres: 0 },
    { name: '', metres: 90 },
    { name: 'alpha', metres: 10 },
    { name: 'broken', metres: NaN },
  ]);
  assert.deepEqual(lanes.map((lane) => lane.name), ['alpha', 'zeta']);
});

test('geometry: a stadium 1.75 wide, 1.35 on a phone, with 6% lanes', () => {
  const wide = ovalGeometry(5);
  assert.equal(wide.width, 1000);
  assert.equal(wide.height, 571);
  assert.equal(wide.band, 34);
  assert.equal(wide.lanes.length, 5);
  assert.equal(wide.lines.length, 6);
  assert.equal(wide.infield.x, 170);
  assert.equal(wide.infield.rx, wide.infield.height / 2);

  const phone = ovalGeometry(5, { phone: true });
  assert.equal(phone.height, 741);
  assert.equal(phone.band, 44);
});

test('geometry: every lane starts at the finish line', () => {
  for (const count of [1, 3, 5]) {
    const shape = ovalGeometry(count);
    assert.equal(shape.lanes.length, count);
    for (const lane of shape.lanes) assert.equal(lane.x + lane.rx, shape.finish.x);
    assert.equal(shape.finish.y2, count * shape.band);
  }
  assert.equal(ovalGeometry(0).lanes.length, 1);
  assert.equal(ovalGeometry(9).lanes.length, 5);
});
