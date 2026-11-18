// The lap oval's lane maths, against the worked examples in DESIGN.md ("The lap oval").
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  lapScale, lapsText, ovalGeometry, ovalModel, siteLanes, trackDistance,
} from '../lib/oval.js';

const site = (name, meters) => ({ name, meters });

test('distances are whole metres, then kilometres with two fixed decimals', () => {
  assert.equal(trackDistance(0), '0 m');
  assert.equal(trackDistance(160.4), '160 m');
  assert.equal(trackDistance(999.6), '1.00 km');
  assert.equal(trackDistance(1480), '1.48 km');
  assert.equal(trackDistance(2000), '2.00 km');
});

test('a lap is 400 m and laps show one decimal', () => {
  assert.equal(lapsText(1840), '4.6 laps');
  assert.equal(lapsText(0), '0.0 laps');
  assert.equal(lapsText(400), '1.0 lap');
});

test('with a limit, one lap is the limit', () => {
  assert.equal(lapScale(1480, 2000), 2000);
  assert.equal(lapScale(5000, 2000), 2000);
});

test('with no limit, one lap is the next whole 400 m, with a 400 m minimum', () => {
  assert.equal(lapScale(0), 400);
  assert.equal(lapScale(120), 400);
  assert.equal(lapScale(400), 400);
  assert.equal(lapScale(401), 800);
  assert.equal(lapScale(1840), 2000);
});

test('under the limit: shares of the limit and the "left of" caption', () => {
  const model = ovalModel({
    totalM: 1480,
    limitM: 2000,
    sites: [site('YouTube', 470), site('Instagram', 640), site('Reddit', 120), site('X', 250)],
  });
  assert.equal(model.state, 'ok');
  assert.equal(model.status, 'Within limit');
  assert.deepEqual(model.figure, ['1.48', 'km']);
  assert.equal(model.caption, '520 m left of 2.00 km');
  assert.deepEqual(model.lanes.map((lane) => [lane.lane, lane.name, lane.pct]), [
    [1, 'Total', 74],
    [2, 'Instagram', 32],
    [3, 'YouTube', 23.5],
    [4, 'X', 12.5],
    [5, 'Reddit', 6],
  ]);
  assert.equal(model.lanes[0].tone, 'volt');
  assert.deepEqual(model.others, []);
});

test('from 80% of the limit the total lane warns and the caption stays as it is', () => {
  const model = ovalModel({ totalM: 1840, limitM: 2000 });
  assert.equal(model.state, 'warn');
  assert.equal(model.status, 'Close to limit');
  assert.equal(model.lanes[0].tone, 'warn');
  assert.equal(model.lanes[0].pct, 92);
  assert.equal(model.caption, '160 m left of 2.00 km');
  assert.equal(ovalModel({ totalM: 1599, limitM: 2000 }).state, 'ok');
  assert.equal(ovalModel({ totalM: 1600, limitM: 2000 }).state, 'warn');
});

test('exactly at the limit is a full lap that is not over', () => {
  const model = ovalModel({ totalM: 2000, limitM: 2000 });
  assert.equal(model.state, 'warn');
  assert.equal(model.lanes[0].pct, 100);
  assert.equal(model.caption, '0 m left of 2.00 km');
});

test('over the limit: a full danger lap and the "over" caption', () => {
  const model = ovalModel({ totalM: 2240, limitM: 2000, sites: [site('YouTube', 2240)] });
  assert.equal(model.state, 'over');
  assert.equal(model.status, 'Over limit');
  assert.equal(model.lanes[0].tone, 'danger');
  assert.equal(model.lanes[0].pct, 100);
  assert.equal(model.caption, '240 m over 2.00 km');
  // A site lane never draws more than one lap either.
  assert.equal(model.lanes[1].pct, 100);
});

test('no limit: laps in the caption and the 400 m scale', () => {
  const model = ovalModel({ totalM: 1840, sites: [site('YouTube', 1000), site('X', 840)] });
  assert.equal(model.state, 'open');
  assert.equal(model.status, null);
  assert.equal(model.scaleM, 2000);
  assert.equal(model.caption, '4.6 laps, no limit');
  assert.deepEqual(model.lanes.map((lane) => lane.pct), [92, 50, 42]);
  assert.equal(model.lanes[0].tone, 'volt');
});

test('an unusable limit is treated as no limit', () => {
  assert.equal(ovalModel({ totalM: 100, limitM: 0 }).state, 'open');
  assert.equal(ovalModel({ totalM: 100, limitM: undefined }).state, 'open');
});

test('an empty day draws lane 1 with no progress and reads 0 m', () => {
  const open = ovalModel({ totalM: 0 });
  assert.deepEqual(open.figure, ['0', 'm']);
  assert.equal(open.lanes.length, 1);
  assert.equal(open.lanes[0].pct, 0);
  assert.equal(open.caption, '0.0 laps, no limit');

  const limited = ovalModel({ totalM: 0, limitM: 2000 });
  assert.equal(limited.state, 'ok');
  assert.equal(limited.lanes[0].pct, 0);
  assert.equal(limited.caption, '2.00 km left of 2.00 km');
});

test('fewer sites draw fewer lanes, and sites with no distance get none', () => {
  const model = ovalModel({ totalM: 300, sites: [site('X', 0), site('Reddit', 300)] });
  assert.deepEqual(model.lanes.map((lane) => lane.name), ['Total', 'Reddit']);
});

test('more than four sites: three keep their lanes and the rest become Other', () => {
  const sites = [
    site('a.com', 50), site('b.com', 400), site('c.com', 300), site('d.com', 200),
    site('e.com', 100), site('f.com', 25),
  ];
  const { lanes, others } = siteLanes(sites);
  assert.deepEqual(lanes.map((lane) => [lane.name, lane.meters]), [
    ['b.com', 400], ['c.com', 300], ['d.com', 200], ['Other', 175],
  ]);
  assert.deepEqual(others.map((other) => other.name), ['e.com', 'a.com', 'f.com']);

  const model = ovalModel({ totalM: 1075, sites });
  assert.equal(model.lanes.length, 5);
  assert.equal(model.lanes[4].name, 'Other');
  assert.equal(model.lanes[4].other, true);
  assert.equal(model.lanes[4].lane, 5);
  assert.deepEqual(model.others.map((other) => other.distance), ['100 m', '50 m', '25 m']);
});

test('exactly four sites all keep their own lane', () => {
  const { lanes, others } = siteLanes([site('a', 4), site('b', 3), site('c', 2), site('d', 1)]);
  assert.deepEqual(lanes.map((lane) => lane.name), ['a', 'b', 'c', 'd']);
  assert.deepEqual(others, []);
});

test('the spoken label names every lane, so the drawing is never the only source', () => {
  const model = ovalModel({ totalM: 640, limitM: 2000, sites: [site('Instagram', 640)] });
  assert.equal(model.label,
    'Today drawn as a running track. 640 m, 1.36 km left of 2.00 km. '
    + 'Lane 1, Total, 640 m. Lane 2, Instagram, 640 m.');
});

test('geometry: stadiums nested by one band per lane, finish line at the start point', () => {
  const g = ovalGeometry(5);
  assert.equal(g.lanes.length, 5);
  assert.equal(g.lines.length, 6);
  assert.deepEqual(g.ground, { x: 0, y: 0, width: 1000, height: 740, rx: 370 });
  assert.deepEqual(g.infield, { x: 200, y: 200, width: 600, height: 340, rx: 170 });
  assert.deepEqual(g.lanes[0], { x: 20, y: 20, width: 960, height: 700, rx: 350 });
  // A stroked rect starts at x + rx on its top edge: the same x for every lane.
  for (const lane of g.lanes) assert.equal(lane.x + lane.rx, 370);
  assert.deepEqual(g.finish, { x: 370, y1: 0, y2: 200 });

  const small = ovalGeometry(1);
  assert.equal(small.lanes.length, 1);
  assert.deepEqual(small.infield, { x: 40, y: 40, width: 920, height: 660, rx: 330 });
  assert.equal(ovalGeometry(9).lanes.length, 5);
  assert.equal(ovalGeometry(0).lanes.length, 1);
});
