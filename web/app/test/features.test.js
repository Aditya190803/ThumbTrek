// The dashboard's side of the shared limit, reports and groups. The pure modules are
// shared with the extension, which tests them in depth; this file checks they are still
// the same bytes, and the dashboard's own glue around them.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { groupStandings, groupWindow, normalizeGroupCode, parseGroup } from '../groups.js';
import { BILLING_ENFORCED, PREMIUM_FEATURES, reconcileLimit } from '../limits.js';
import { report, reportTitle } from '../report.js';
import { reportDays } from '../stats.js';

test('the shared modules are byte-identical in the dashboard and the extension', () => {
  for (const name of ['report.js', 'groups.js', 'limits.js']) {
    const here = readFileSync(new URL(`../${name}`, import.meta.url), 'utf8');
    const there = readFileSync(new URL(`../../../extension/lib/${name}`, import.meta.url), 'utf8');
    assert.equal(here, there, `${name} has drifted between web/app and extension/lib`);
  }
});

test('Premium is not enforced and lists the same five features as the page', () => {
  assert.equal(BILLING_ENFORCED, false);
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  for (const feature of PREMIUM_FEATURES) assert.ok(html.includes(feature), feature);
});

test('reportDays sums every source and keeps an unsplit total as Other', () => {
  const days = reportDays([
    { date: '2026-11-16', source: 'android', um: 3_000_000, apps: { YouTube: 2_000_000, Reddit: 1_000_000 } },
    { date: '2026-11-16', source: 'web', um: 1_500_000, apps: { YouTube: 1_000_000 } },
    { date: '2026-11-17', source: 'android', um: 4_000_000, apps: {} },
  ]);
  assert.deepEqual([...days.get('2026-11-16')], [['YouTube', 3], ['Reddit', 1], ['Other', 0.5]]);
  assert.deepEqual([...days.get('2026-11-17')], [['Other', 4]]);
});

test('a report over dashboard days matches the hand count', () => {
  const days = reportDays([
    { date: '2026-11-09', um: 10_000_000, apps: { YouTube: 10_000_000 } },
    { date: '2026-11-16', um: 20_000_000, apps: { YouTube: 20_000_000 } },
    { date: '2026-11-17', um: 40_000_000, apps: { YouTube: 30_000_000, Reddit: 10_000_000 } },
  ]);
  const r = report('week', days, { today: '2026-11-18', limitMeters: 25 });
  assert.equal(reportTitle(r), '16 to 22 Nov');
  assert.equal(r.totalMeters, 60);
  assert.equal(r.deltaPercent, 500);
  assert.equal(r.cleanDays, 1);
  assert.equal(r.judgedDays, 2);
  assert.deepEqual(r.apps.map((a) => a.key), ['YouTube', 'Reddit']);
});

test('the newest limit edit wins and a tie keeps the server copy', () => {
  assert.equal(reconcileLimit({ limitUm: 1, setAt: 5 }, { limitUm: 2, setAt: 4 }).action, 'push');
  assert.equal(reconcileLimit({ limitUm: 1, setAt: 4 }, { limitUm: 2, setAt: 5 }).action, 'adopt');
  assert.equal(reconcileLimit({ limitUm: 1, setAt: 5 }, { limitUm: 2, setAt: 5 }).action, 'adopt');
  assert.equal(reconcileLimit({ limitUm: 2, setAt: 5 }, { limitUm: 2, setAt: 5 }).action, 'none');
});

test('a group board ranks the least first in a least challenge, silent members last', () => {
  const group = parseGroup('g1', {
    name: 'Flat', owner: 'a', code: 'ABCD1234', members: ['a', 'b', 'c'],
    challenge: { title: 'No phone week', kind: 'least', startsOn: '2026-11-16', endsOn: '2026-11-22' },
  });
  const window = groupWindow(group, '2026-11-18');
  assert.equal(window.status, '4 days left');
  const rows = groupStandings(group, [
    { uid: 'a', source: 'android', name: 'Asha', windowKey: window.key, um: 9 },
    { uid: 'a', source: 'web', name: 'Asha', windowKey: window.key, um: 3 },
    { uid: 'b', source: 'android', name: 'Ben', windowKey: window.key, um: 5 },
    { uid: 'c', source: 'android', name: 'Cy', windowKey: '2026-W46', um: 0 },
  ], window, 'b');
  assert.deepEqual(rows.map((r) => [r.name, r.um, r.isMe]), [
    ['Ben', 5, true], ['Asha', 12, false], ['Cy', null, false],
  ]);
});

test('a pasted group code is cleaned the way a friend code is', () => {
  assert.equal(normalizeGroupCode(' abcd-1234 '), 'ABCD1234');
  assert.equal(normalizeGroupCode('https://thumbtrek.adityamer.dev/g/olio-u000'), '0110V000');
});
