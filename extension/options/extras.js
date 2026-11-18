/**
 * The options page's newer sections: the shared limit, a separate limit for each site,
 * weekly and monthly reports, and backup and restore.
 *
 * Kept apart from options.js, which already carries the limit, sites, history and account.
 * Everything here reads the local store; only the shared limit talks to the worker, for
 * the same reason sync does: work started in this tab dies when the tab closes.
 */

import { api } from '../lib/browser.js';
import { allRows, restoreRows } from '../lib/db.js';
import {
  BackupError, backupFilename, buildBackup, parseBackup, restoreSummary,
} from '../lib/backup.js';
import { todayKey } from '../lib/dates.js';
import { siteName } from '../lib/domain.js';
import { MAX_APP_LIMIT_M, MIN_APP_LIMIT_M, parseAppLimit } from '../lib/limits.js';
import {
  report, reportDayLabel, reportDeltaText, reportTitle,
} from '../lib/report.js';
import {
  customLabels, dailyLimit, listedSites, readSettings, restoreSettings, setSharedLimit,
  setSiteLimit,
} from '../lib/settings.js';
import { formatDistance } from '../lib/stats.js';
import { micrometresToMetres } from '../lib/units.js';

const $ = (id) => document.getElementById(id);

function note(id, text, kind = '') {
  const element = $(id);
  element.textContent = text;
  element.className = `note ${kind}`.trim();
}

function make(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function ask(type) {
  const reply = await api.runtime.sendMessage({ type });
  if (!reply?.ok) throw new Error(reply?.error ?? 'The extension did not respond.');
  return reply.result;
}

// ———————————————————————————— Shared limit ————————————————————————————

async function renderSharedLimit() {
  const [settings, status] = await Promise.all([
    readSettings(),
    ask('trek/sync-status').catch(() => null),
  ]);
  const canShare = Boolean(status?.account) && settings.leaderboardOptIn;
  const toggle = $('shared-limit');
  toggle.setAttribute('aria-checked', settings.sharedLimit && canShare ? 'true' : 'false');
  toggle.disabled = !canShare;
  $('shared-limit-help').textContent = !canShare
    ? 'Needs sync. Sign in and turn on publishing under Leaderboard and sync first.'
    : settings.sharedLimit
      ? 'On. Scrolling on your phone counts against this limit, as of its last sync.'
      : 'Keep one limit in your account, and count your phone’s scrolling against it.';
}

$('shared-limit').addEventListener('click', async () => {
  const on = $('shared-limit').getAttribute('aria-checked') !== 'true';
  await setSharedLimit(on);
  // On: reconcile now, so the phone's limit (or this one) is in place before the tab
  // closes. Off: take the account's copy away; each device keeps the limit it has.
  ask(on ? 'trek/sync-now' : 'trek/shared-limit-off').catch(() => {}).finally(renderSharedLimit);
  renderSharedLimit();
});

// ———————————————————————————— A limit for each site ————————————————————————————

async function renderSiteLimits() {
  const settings = await readSettings();
  const host = $('site-limits');
  const focused = host.contains(document.activeElement) ? document.activeElement.dataset.domain : null;
  host.replaceChildren();
  const sites = listedSites(settings).filter((site) => site.tracked);
  if (sites.length === 0) {
    host.append(make('p', 'hint', 'Switch a site on under Sites to give it a limit.'));
    return;
  }
  for (const site of sites) {
    const limit = settings.siteLimits[site.domain] ?? null;
    const form = make('form', 'row site-limit');
    form.noValidate = true;
    const text = make('div', 'row-text');
    text.append(
      make('strong', '', site.label),
      make('span', '', limit === null ? 'No limit' : `${formatDistance(limit)} a day`),
    );
    const input = make('input');
    input.type = 'number';
    input.inputMode = 'numeric';
    input.min = String(MIN_APP_LIMIT_M);
    input.max = String(MAX_APP_LIMIT_M);
    input.step = '1';
    input.placeholder = 'metres';
    input.value = limit === null ? '' : String(limit);
    input.dataset.domain = site.domain;
    input.setAttribute('aria-label', `Daily limit for ${site.label}, in metres. Leave empty for none.`);
    const save = make('button', 'btn btn-ghost', 'Save');
    save.type = 'submit';
    form.append(text, input, save);
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const raw = input.value.trim();
      if (raw === '') {
        await setSiteLimit(site.domain, null);
        note('site-limit-note', `${site.label} has no limit of its own now.`);
      } else {
        const value = parseAppLimit(raw);
        if (value === null) {
          input.setAttribute('aria-invalid', 'true');
          note('site-limit-note', `Enter a whole number from ${MIN_APP_LIMIT_M} to ${MAX_APP_LIMIT_M} metres.`, 'error');
          return;
        }
        await setSiteLimit(site.domain, value);
        note('site-limit-note', `${site.label} is limited to ${formatDistance(value)} a day.`, 'good');
      }
      renderSiteLimits();
    });
    host.append(form);
  }
  if (focused) host.querySelector(`[data-domain="${CSS.escape(focused)}"]`)?.focus();
}

// ———————————————————————————— Reports ————————————————————————————

const REPORTS = [
  { label: 'This week', span: 'week', offset: 0 },
  { label: 'Last week', span: 'week', offset: 1 },
  { label: 'This month', span: 'month', offset: 0 },
  { label: 'Last month', span: 'month', offset: 1 },
];
let reportChoice = 0;

function reportLine(label, value, caption) {
  const item = make('li', 'bar-row');
  const head = make('div', 'bar-head');
  head.append(make('span', 'bar-label', label), make('span', 'bar-value num', value));
  item.append(head);
  if (caption) item.append(make('p', 'hint', caption));
  return item;
}

async function renderReport() {
  const [rows, settings] = await Promise.all([allRows(), readSettings()]);
  const days = new Map();
  for (const row of rows) {
    if (!days.has(row.date)) days.set(row.date, new Map());
    days.get(row.date).set(row.domain, micrometresToMetres(row.um));
  }
  const choice = REPORTS[reportChoice];
  const r = report(choice.span, days, {
    today: todayKey(),
    offset: choice.offset,
    limitMeters: dailyLimit(settings),
    limitSince: settings.limitSince,
  });

  for (const button of $('report-choice').querySelectorAll('button')) {
    button.setAttribute('aria-pressed', String(Number(button.dataset.index) === reportChoice));
  }
  $('report-title').textContent = reportTitle(r);

  const host = $('report-rows');
  host.replaceChildren();
  const noun = choice.span;
  if (r.totalMeters <= 0) {
    host.append(make('li', 'hint', `Nothing was measured in this ${noun}.`));
    return;
  }
  host.append(
    reportLine('Total', formatDistance(r.totalMeters), reportDeltaText(r)),
    reportLine(
      'Daily average',
      formatDistance(r.dailyAverageMeters),
      `${r.activeDays} of ${r.elapsedDays} days tracked${r.running ? ', so far' : ''}`,
    ),
  );
  if (r.cleanDays !== null) {
    host.append(reportLine('Days within your limit', `${r.cleanDays} of ${r.judgedDays}`, null));
  }
  host.append(reportLine('Highest day', formatDistance(r.highest.meters), reportDayLabel(r.highest.date)));
  // With one tracked day the lowest is the highest again; saying it twice adds nothing.
  if (r.activeDays > 1) {
    host.append(reportLine('Lowest day', formatDistance(r.lowest.meters), reportDayLabel(r.lowest.date)));
  }
  const labels = customLabels(settings);
  const top = Math.max(r.apps[0]?.meters ?? 0, 1);
  for (const app of r.apps) {
    const change = app.previousMeters <= 0 || app.previousMeters === app.meters
      ? null
      : `${app.meters > app.previousMeters ? 'Up' : 'Down'} from ${formatDistance(app.previousMeters)}`;
    const item = reportLine(siteName(app.key, labels), formatDistance(app.meters), change);
    const rail = make('div', 'rail');
    rail.setAttribute('aria-hidden', 'true');
    const fill = make('i');
    fill.style.width = `${(app.meters / top) * 100}%`;
    rail.append(fill);
    item.append(rail);
    host.append(item);
  }
}

REPORTS.forEach((choice, index) => {
  const button = make('button', 'chip-btn', choice.label);
  button.type = 'button';
  button.dataset.index = String(index);
  button.addEventListener('click', () => {
    reportChoice = index;
    renderReport();
  });
  $('report-choice').append(button);
});

// ———————————————————————————— Backup and restore ————————————————————————————

$('backup-button').addEventListener('click', async () => {
  const [rows, settings] = await Promise.all([allRows(), readSettings()]);
  if (rows.length === 0) {
    note('backup-note', 'Nothing measured yet, so there is nothing to back up.');
    return;
  }
  const backup = buildBackup(rows, settings, new Date().toISOString());
  const url = URL.createObjectURL(new Blob([JSON.stringify(backup, null, 2)], { type: 'application/json' }));
  const link = make('a');
  link.href = url;
  link.download = backupFilename(todayKey());
  link.click();
  // Revoked later, not at once: see the CSV export in options.js.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
  note('backup-note', 'Backup saved. Keep the file somewhere safe.', 'good');
});

$('restore-button').addEventListener('click', () => $('restore-file').click());

$('restore-file').addEventListener('change', async (event) => {
  const [file] = event.target.files;
  // Cleared first, so choosing the same file again still fires a change.
  event.target.value = '';
  if (!file) return;
  try {
    const parsed = parseBackup(await file.text());
    if (parsed.rows.length === 0) throw new BackupError('That backup has no history in it.');
    await restoreRows(parsed.rows);
    await restoreSettings(parsed.settings);
    const { days, from, to } = restoreSummary(parsed.rows);
    note(
      'backup-note',
      `Restored ${days === 1 ? '1 day' : `${days} days`} of history, from ${from} to ${to}.`,
      'good',
    );
    document.dispatchEvent(new CustomEvent('trek:restored'));
  } catch (error) {
    note(
      'backup-note',
      error instanceof BackupError ? error.message : 'Could not read that file.',
      'error',
    );
  }
});

export async function renderExtras() {
  await Promise.all([renderSharedLimit(), renderSiteLimits(), renderReport()]);
}
