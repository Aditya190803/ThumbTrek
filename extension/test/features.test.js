// Shared limit, per-site limits, reports, groups and backup: the rules, without a browser.
// The same cases as app/src/test/.../FeaturesTest.kt, so the clients agree by construction.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import {
  BackupError, buildBackup, parseBackup, restoreSummary,
} from '../lib/backup.js';
import {
  challengeEnd, challengeProblem, groupStandings, groupWindow, newGroupCode,
  normalizeGroupCode, parseGroup, windowUm,
} from '../lib/groups.js';
import {
  BILLING_ENFORCED, PREMIUM_FEATURES, appLimitStatuses, limitLevel, parseAppLimit,
  reconcileLimit,
} from '../lib/limits.js';
import {
  report, reportDayLabel, reportDeltaText, reportTitle,
} from '../lib/report.js';

// --- shared between the extension and the dashboard ------------------------------------

test('the shared modules are byte-identical in the extension and the dashboard', () => {
  for (const name of ['report.js', 'groups.js', 'limits.js']) {
    const here = readFileSync(new URL(`../lib/${name}`, import.meta.url), 'utf8');
    const there = readFileSync(new URL(`../../web/app/${name}`, import.meta.url), 'utf8');
    assert.equal(here, there, `${name} has drifted between extension/lib and web/app`);
  }
});

// --- shared limit ----------------------------------------------------------------------

test('the newest limit edit wins', () => {
  const local = { limitUm: 500_000_000, setAt: 2_000 };
  assert.equal(reconcileLimit(local, null).action, 'push');
  assert.equal(reconcileLimit(local, { limitUm: 300_000_000, setAt: 1_000 }).action, 'push');
  const newer = { limitUm: 300_000_000, setAt: 3_000 };
  assert.deepEqual(reconcileLimit(local, newer), { action: 'adopt', limit: newer });
  assert.equal(reconcileLimit(local, { ...local }).action, 'none');
});

test('a tie keeps the server copy, so two clients never ping-pong', () => {
  const remote = { limitUm: 0, setAt: 2_000 };
  assert.deepEqual(
    reconcileLimit({ limitUm: 500_000_000, setAt: 2_000 }, remote),
    { action: 'adopt', limit: remote },
  );
});

test('nothing is held back while billing is not enforced', () => {
  assert.equal(BILLING_ENFORCED, false);
  assert.equal(PREMIUM_FEATURES.length, 5);
});

// --- per-site limits -------------------------------------------------------------------

test('site limits are judged like the daily one', () => {
  const statuses = appLimitStatuses(
    new Map([['ig', 640], ['yt', 90]]),
    { ig: 500, yt: 100, x: 50 },
  );
  assert.deepEqual(statuses.map((s) => s.key), ['ig', 'yt', 'x']);
  assert.equal(statuses[0].over, true);
  assert.deepEqual(statuses.map((s) => s.level), [2, 1, 0]);
  assert.equal(limitLevel(0, 100), 0);
  assert.equal(limitLevel(100, 100), 1);
  assert.equal(limitLevel(50, 0), 0);
});

test('a site limit has to be a whole number inside the bounds', () => {
  assert.equal(parseAppLimit('250'), 250);
  assert.equal(parseAppLimit(' 5 '), 5);
  assert.equal(parseAppLimit('4'), null);
  assert.equal(parseAppLimit('10001'), null);
  assert.equal(parseAppLimit('12.5'), null);
  assert.equal(parseAppLimit(''), null);
});

// --- reports ---------------------------------------------------------------------------

const SUNDAY = '2026-11-15';
const day = (entries) => new Map(Object.entries(entries));
const HISTORY = new Map([
  ['2026-11-03', day({ ig: 400, yt: 100 })],
  ['2026-11-06', day({ ig: 500 })],
  ['2026-11-09', day({ ig: 300, yt: 200 })],
  ['2026-11-11', day({ ig: 100 })],
  ['2026-11-14', day({ yt: 900, x: 0 })],
]);

test('a weekly report adds up its own week only', () => {
  const r = report('week', HISTORY, { today: SUNDAY, limitMeters: 500 });
  assert.equal(r.from, '2026-11-09');
  assert.equal(r.to, SUNDAY);
  assert.equal(r.running, false);
  assert.equal(r.totalMeters, 1500);
  assert.equal(r.previousMeters, 1000);
  assert.equal(r.deltaPercent, 50);
  assert.equal(r.activeDays, 3);
  assert.equal(r.elapsedDays, 7);
  assert.equal(r.dailyAverageMeters, 1500 / 7);
  assert.equal(r.cleanDays, 2);
  assert.equal(r.judgedDays, 3);
  assert.deepEqual(r.highest, { date: '2026-11-14', meters: 900 });
  assert.deepEqual(r.lowest, { date: '2026-11-11', meters: 100 });
  assert.deepEqual(r.apps.map((a) => a.key), ['yt', 'ig']);
  assert.equal(r.apps[0].previousMeters, 100);
  assert.equal(reportTitle(r), '9 to 15 Nov');
  assert.equal(reportDeltaText(r), '50% more than the week before');
  assert.equal(reportDayLabel('2026-11-14'), 'Sat 14 Nov');
});

test('a running week averages over the days so far', () => {
  const r = report('week', HISTORY, { today: '2026-11-11' });
  assert.equal(r.running, true);
  assert.equal(r.elapsedDays, 3);
  assert.equal(r.totalMeters, 600);
  assert.equal(r.cleanDays, null);
  assert.equal(r.judgedDays, 0);
});

test('days before the limit began are not judged', () => {
  const r = report('week', HISTORY, { today: SUNDAY, limitMeters: 500, limitSince: '2026-11-12' });
  assert.equal(r.judgedDays, 1);
  assert.equal(r.cleanDays, 0);
});

test('last week and the month are reachable by offset', () => {
  const last = report('week', HISTORY, { today: SUNDAY, offset: 1 });
  assert.equal(last.from, '2026-11-02');
  assert.equal(last.to, '2026-11-08');
  assert.equal(last.totalMeters, 1000);
  assert.equal(last.deltaPercent, null);
  assert.equal(reportDeltaText(last), null);

  const month = report('month', HISTORY, { today: SUNDAY });
  assert.equal(month.from, '2026-11-01');
  assert.equal(month.running, true);
  assert.equal(month.totalMeters, 2500);
  assert.equal(reportTitle(month), 'November 2026');

  const previous = report('month', HISTORY, { today: '2026-01-15', offset: 1 });
  assert.equal(previous.from, '2025-12-01');
  assert.equal(previous.to, '2025-12-31');
});

test('a week that spans two months names both', () => {
  const r = report('week', new Map(), { today: '2026-12-02' });
  assert.equal(reportTitle(r), '30 Nov to 6 Dec');
});

// --- groups ----------------------------------------------------------------------------

const GROUP = { id: 'g1', name: 'Flatmates', owner: 'a', code: 'ABCD1234', members: ['a', 'b', 'c'], challenge: null };
const QUIET = { title: 'Quiet week', kind: 'least', startsOn: '2026-11-10', endsOn: '2026-11-16' };

test('a group with no challenge counts the current week', () => {
  const window = groupWindow(GROUP, '2026-11-12');
  assert.equal(window.key, '2026-W46');
  assert.equal(window.from, '2026-11-09');
  assert.equal(window.to, '2026-11-15');
  assert.equal(window.kind, 'most');
});

test('a challenge owns the window before, during and after', () => {
  const g = { ...GROUP, challenge: QUIET };
  assert.equal(groupWindow(g, '2026-11-08').status, 'Starts 10 Nov');
  assert.equal(groupWindow(g, '2026-11-12').status, '4 days left');
  assert.equal(groupWindow(g, '2026-11-16').status, 'Last day');
  assert.equal(groupWindow(g, '2026-11-20').status, 'Ended 16 Nov');
  assert.equal(groupWindow(g, '2026-11-12').key, '2026-11-10..2026-11-16');
});

test('window distance ignores days outside it', () => {
  const window = groupWindow(GROUP, '2026-11-12');
  const days = new Map([
    ['2026-11-08', 5], ['2026-11-09', 10], ['2026-11-15', 20], ['2026-11-16', 40], ['junk', 99],
  ]);
  assert.equal(windowUm(days, window), 30);
});

test('standings sum sources and ignore stale windows', () => {
  const window = groupWindow(GROUP, '2026-11-12');
  const doc = (uid, source, name, windowKey, um) => ({ uid, source, name, photo: '', windowKey, um });
  const board = groupStandings(GROUP, [
    doc('a', 'android', 'Asha', window.key, 300),
    doc('a', 'web', 'Asha', window.key, 200),
    doc('b', 'android', 'Bo', '2026-W45', 9_000),
    doc('c', 'android', 'Cy', window.key, 800),
    doc('z', 'android', 'Stranger', window.key, 1),
  ], window, 'a');
  assert.deepEqual(board.map((row) => row.uid), ['c', 'a', 'b']);
  assert.equal(board[1].um, 500);
  assert.equal(board[1].isMe, true);
  assert.equal(board[2].name, 'Bo');
  assert.equal(board[2].um, null);
});

test('in a least challenge an empty row never wins', () => {
  const g = { ...GROUP, challenge: QUIET };
  const window = groupWindow(g, '2026-11-12');
  const board = groupStandings(g, [
    { uid: 'a', source: 'android', name: 'Asha', photo: '', windowKey: window.key, um: 300 },
    { uid: 'c', source: 'android', name: 'Cy', photo: '', windowKey: window.key, um: 100 },
  ], window, 'a');
  assert.deepEqual(board.map((row) => row.uid), ['c', 'a', 'b']);
});

test('challenges are checked before they are written', () => {
  assert.equal(challengeProblem('Quiet week', '2026-11-10', '2026-11-10'), null);
  assert.notEqual(challengeProblem(' ', '2026-11-10', '2026-11-10'), null);
  assert.notEqual(challengeProblem('Quiet', '2026-11-10', '2026-11-09'), null);
  assert.notEqual(challengeProblem('Quiet', '2026-11-10', challengeEnd('2026-11-10', 93)), null);
  assert.equal(challengeProblem('Quiet', '2026-11-10', challengeEnd('2026-11-10', 92)), null);
  assert.equal(challengeEnd('2026-11-10', 7), '2026-11-16');
});

test('group codes look like friend codes, and paste forgivingly', () => {
  const code = newGroupCode(new Uint8Array([0, 1, 31, 32, 33, 255, 10, 20]));
  assert.equal(code, '01Z01ZAM');
  assert.equal(normalizeGroupCode(' abcd-1234 '), 'ABCD1234');
  assert.equal(normalizeGroupCode('IOLUABCD'), '101VABCD');
});

test('a half-written group document still parses', () => {
  assert.equal(parseGroup('g', null), null);
  const g = parseGroup('g', { name: 'N', members: ['a', 7], challenge: { title: 'T', kind: 'odd', startsOn: 'x' } });
  assert.deepEqual(g.members, ['a']);
  assert.equal(g.challenge, null);
  assert.equal(parseGroup('g', { challenge: QUIET }).challenge.kind, 'least');
});

// --- backup ----------------------------------------------------------------------------

const ROWS = [
  { date: '2026-11-01', domain: 'instagram.com', um: 42_000 },
  { date: '2026-11-01', domain: 'reddit.com', um: 8_400 },
  { date: '2026-11-02', domain: 'instagram.com', um: 21_000 },
  { date: '2026-11-02', domain: 'empty.com', um: 0 },
];
const SETTINGS = { dailyLimitM: 250, siteLimits: { 'instagram.com': 100 }, customSites: { 'my.site': 'Mine' } };

test('a backup round-trips through JSON', () => {
  const backup = buildBackup(ROWS, SETTINGS, '2026-11-15T10:00:00Z');
  assert.equal(backup.days.length, 2);
  assert.deepEqual(Object.keys(backup.days[1].apps), ['instagram.com']);
  const parsed = parseBackup(JSON.stringify(backup));
  assert.deepEqual(parsed.rows, ROWS.slice(0, 3));
  assert.deepEqual(parsed.settings, {
    limitMeters: 250, siteLimits: { 'instagram.com': 100 }, customSites: { 'my.site': 'Mine' },
  });
  assert.deepEqual(restoreSummary(parsed.rows), { days: 2, from: '2026-11-01', to: '2026-11-02' });
});

test('files that are not ours are refused with a reason', () => {
  assert.throws(() => parseBackup('not json'), BackupError);
  assert.throws(() => parseBackup('{"format":"something-else"}'), BackupError);
  assert.throws(
    () => parseBackup('{"format":"thumbtrek-backup","version":1,"source":"android","days":[]}'),
    /Android app/,
  );
  assert.throws(
    () => parseBackup('{"format":"thumbtrek-backup","version":99,"source":"web"}'),
    /newer/,
  );
});

test('one bad row does not cost the rest of the backup', () => {
  const parsed = parseBackup(JSON.stringify({
    format: 'thumbtrek-backup',
    version: 1,
    source: 'web',
    days: [
      { date: 'not-a-date', apps: { a: 5 } },
      { date: '2026-11-01', apps: { a: 5, b: -1, c: 'x', d: 1.5 } },
      { date: '2026-11-02' },
    ],
  }));
  assert.deepEqual(parsed.rows, [{ date: '2026-11-01', domain: 'a', um: 5 }]);
  assert.equal(parsed.settings.limitMeters, null);
});
