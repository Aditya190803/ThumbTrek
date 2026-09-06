/**
 * Domain attribution, the tracking decision, and CSV export -- the three places a site's
 * identity turns into a stored key or a user-visible label.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_SITES, parseSiteInput, registrableDomain, siteName,
} from '../lib/domain.js';
import { DEFAULTS, listedSites, shouldTrack } from '../lib/settings.js';
import { toCsv } from '../lib/csv.js';

test('subdomains collapse to the registrable domain', () => {
  assert.equal(registrableDomain('www.youtube.com'), 'youtube.com');
  assert.equal(registrableDomain('m.youtube.com'), 'youtube.com');
  assert.equal(registrableDomain('music.youtube.com'), 'youtube.com');
  assert.equal(registrableDomain('youtube.com'), 'youtube.com');
  assert.equal(registrableDomain('old.reddit.com'), 'reddit.com');
});

test('hostnames are normalised before they become a key', () => {
  assert.equal(registrableDomain('WWW.Reddit.COM'), 'reddit.com');
  assert.equal(registrableDomain('reddit.com.'), 'reddit.com'); // fully-qualified form
});

test('twitter.com folds into x.com, so one feed is one row', () => {
  assert.equal(registrableDomain('twitter.com'), 'x.com');
  assert.equal(registrableDomain('mobile.twitter.com'), 'x.com');
  assert.equal(registrableDomain('x.com'), 'x.com');
});

test('two-level suffixes keep the label that was actually registered', () => {
  assert.equal(registrableDomain('www.bbc.co.uk'), 'bbc.co.uk');
  assert.equal(registrableDomain('news.com.au'), 'news.com.au');
  assert.equal(registrableDomain('shop.example.co.jp'), 'example.co.jp');
});

test('things with no registrable domain are never filed anywhere', () => {
  // An empty key would be a row in the database that means nothing.
  assert.equal(registrableDomain('localhost'), '');
  assert.equal(registrableDomain('127.0.0.1'), '');
  assert.equal(registrableDomain('192.168.1.14'), '');
  assert.equal(registrableDomain('[::1]'), '');
  assert.equal(registrableDomain(''), '');
  assert.equal(registrableDomain(undefined), '');
});

test('the four defaults are the four feeds the phone tracks', () => {
  assert.deepEqual([...DEFAULT_SITES.keys()], [
    'instagram.com', 'youtube.com', 'x.com', 'reddit.com',
  ]);
  assert.deepEqual(DEFAULTS.trackedSites, [...DEFAULT_SITES.keys()]);
});

test('site labels fall back through built-in, custom, then the key itself', () => {
  assert.equal(siteName('youtube.com'), 'YouTube');
  assert.equal(siteName('hn.example', new Map([['hn.example', 'Hacker News']])), 'Hacker News');
  assert.equal(siteName('unknown.example'), 'unknown.example');
});

test('the add-a-site field takes a domain, a hostname, or a pasted URL', () => {
  assert.equal(parseSiteInput('news.ycombinator.com'), 'ycombinator.com');
  assert.equal(parseSiteInput('  https://www.reddit.com/r/all  '), 'reddit.com');
  assert.equal(parseSiteInput('youtube.com/feed/subscriptions'), 'youtube.com');
  assert.equal(parseSiteInput('nonsense'), '');
  assert.equal(parseSiteInput(''), '');
  assert.equal(parseSiteInput('   '), '');
});

test('tracking is on by default for the four, off for everything else', () => {
  assert.equal(shouldTrack('youtube.com', DEFAULTS), true);
  assert.equal(shouldTrack('reddit.com', DEFAULTS), true);
  assert.equal(shouldTrack('wikipedia.org', DEFAULTS), false);
});

test('track everything means everything with a domain, and nothing without one', () => {
  const settings = { ...DEFAULTS, trackEverything: true };
  assert.equal(shouldTrack('wikipedia.org', settings), true);
  // Still not localhost: there is no registrable domain to file it under.
  assert.equal(shouldTrack('', settings), false);
});

test('switching a custom site off does not forget that it exists', () => {
  // The Prefs.customApps rule: the enabled set and the added set are separate.
  const settings = {
    ...DEFAULTS,
    customSites: { 'ycombinator.com': 'Hacker News' },
    trackedSites: ['youtube.com'],
  };
  const listed = listedSites(settings);
  const hn = listed.find((site) => site.domain === 'ycombinator.com');
  assert.ok(hn, 'a switched-off custom site must still be listed');
  assert.equal(hn.tracked, false);
  assert.equal(hn.custom, true);
  assert.equal(hn.label, 'Hacker News');
  // The four built-ins are always listed, and are never removable.
  assert.equal(listed.filter((site) => !site.custom).length, 4);
});

test('a tracked domain in neither list is still shown rather than counted invisibly', () => {
  const settings = { ...DEFAULTS, trackedSites: ['ghost.example'] };
  const listed = listedSites(settings);
  assert.ok(listed.some((site) => site.domain === 'ghost.example' && site.tracked));
});

test('CSV keeps the phone export shape, with an honest unit in the header', () => {
  const csv = toCsv([
    { date: '2026-09-05', domain: 'youtube.com', um: 940_000 },
    { date: '2026-09-06', domain: 'instagram.com', um: 900_000 },
  ]);
  assert.equal(csv, 'date,site,um\n'
    + '2026-09-05,"YouTube",940000\n'
    + '2026-09-06,"Instagram",900000\n');
});

test('CSV escapes a label that contains a quote or a comma', () => {
  const csv = toCsv(
    [{ date: '2026-09-06', domain: 'weird.example', um: 1 }],
    new Map([['weird.example', 'He said "hi", loudly']]),
  );
  assert.equal(csv, 'date,site,um\n2026-09-06,"He said ""hi"", loudly",1\n');
});

test('an empty export is a header, not an empty file', () => {
  assert.equal(toCsv([]), 'date,site,um\n');
});
