/**
 * The options page: daily limit, sites, history and badges, account, and the two
 * irreversible buttons.
 *
 * Sync work is delegated to the service worker rather than done here. Not for tidiness --
 * `chrome.identity.launchWebAuthFlow` needs a user gesture in a page, so sign-in genuinely
 * runs here -- but because a sync started in this tab dies when the tab closes, and a user
 * who opts in and immediately closes settings must still get their backfill.
 */

import { api } from '../lib/browser.js';
import { allRows, dayTotals, wipeLocalData } from '../lib/db.js';
import { anonymousHandle, formatFriendCode, friendCode } from '../lib/identity.js';
import { CSV_FILENAME, toCsv } from '../lib/csv.js';
import { parseSiteInput, siteName } from '../lib/domain.js';
import { signIn, signOut } from '../lib/auth.js';
import { syncedAgo } from '../lib/sync.js';
import { micrometresToMetres } from '../lib/units.js';
import { addDays, dayLabel, todayKey } from '../lib/dates.js';
import {
  BASELINE_DAYS, MAX_DAILY_LIMIT_M, MIN_DAILY_LIMIT_M, badges, formatDistance, limitStreak,
  personalRecord, suggestedLimit, totalThisMonth, totalThisWeek, trackedDaysSince, trekStreak,
  typicalDayMeters, weekOverWeekDelta, weeklyBuckets,
} from '../lib/stats.js';
import {
  addCustomSite, customLabels, dailyLimit, listedSites, parseDailyLimit, readSettings,
  removeCustomSite, setAnonymous, setDailyLimit, setLeaderboardOptIn, setSiteTracked,
  setTrackEverything, startBaseline,
} from '../lib/settings.js';

const $ = (id) => document.getElementById(id);
const metres = (um) => formatDistance(micrometresToMetres(um));
const plural = (n, one, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

/** Round-trips a message through the worker and unwraps its `{ ok, result }` envelope. */
async function ask(type) {
  const reply = await api.runtime.sendMessage({ type });
  if (!reply?.ok) throw new Error(reply?.error ?? 'The extension did not respond.');
  return reply.result;
}

/** Every note is a polite live region in the HTML, so setting its text announces it. */
function note(id, text, kind = '') {
  const element = $(id);
  element.textContent = text;
  element.className = `note ${kind}`.trim();
}

function setToggle(id, on) {
  $(id).setAttribute('aria-checked', on ? 'true' : 'false');
}

// ———————————————————————————— Daily limit ————————————————————————————

/**
 * Baseline is the default: a fresh install learns a typical day for a week and then sets a
 * limit about 10% under it. Setting one by hand is allowed, and goes through a caution step.
 */
async function typicalDay() {
  const totals = await dayTotals();
  const dayMeters = new Map([...totals].map(([key, um]) => [key, micrometresToMetres(um)]));
  const today = todayKey();
  return {
    typical: typicalDayMeters(dayMeters, addDays(today, -28), today, 3),
    dayMeters,
    today,
  };
}

async function renderLimit() {
  const settings = await readSettings();
  const limit = dailyLimit(settings);
  const learning = limit === null && Boolean(settings.baselineSince);
  const { typical, dayMeters, today } = await typicalDay();
  const days = learning
    ? Math.min(BASELINE_DAYS, trackedDaysSince(dayMeters, settings.baselineSince, today).length)
    : 0;
  $('limit-current').textContent = limit !== null
    ? `${formatDistance(limit)} / day`
    : learning ? `Learning · day ${days} of ${BASELINE_DAYS}` : 'Not set';
  $('limit-lede').textContent = limit !== null
    ? 'Stay within your limit to earn a clean day. It covers scrolling measured in this '
      + 'browser; your phone keeps its own.'
    : learning
      ? `ThumbTrek is learning your usual day. After ${BASELINE_DAYS} tracked days your limit `
        + 'is set about 10% under it. You can also set one yourself now.'
      : 'No limit and no clean-day scores. Learn your baseline and ThumbTrek will suggest one, '
        + 'or set your own below.';
  $('limit-typical').hidden = typical === null;
  $('limit-typical').textContent = typical === null ? ''
    : `Your usual day lately: about ${formatDistance(typical)}`;
  $('limit-baseline').hidden = limit !== null || learning;
  $('limit-save').textContent = limit === null ? 'Set my own limit' : 'Change limit';
  $('limit-clear').hidden = limit === null && !learning;
  const input = $('limit-input');
  // Never overwrite what the user is in the middle of typing.
  if (document.activeElement !== input) input.value = limit === null ? '' : String(limit);
}

/**
 * The caution step, worded like ManualLimitDialog on the phone. The dialog resolves to
 * "confirm", "alternative" (the better option it offered) or "cancel".
 */
function cautionFor(newM, typical, currentM) {
  const verb = currentM === null ? 'Set' : 'Change';
  if (typical === null) {
    return {
      title: 'Set a limit without a baseline?',
      body: `ThumbTrek doesn’t know your usual day in this browser yet, so ${formatDistance(newM)} `
        + 'is a guess. Too low and every day reads as over; too high and it never matters. '
        + `Learning your baseline takes ${BASELINE_DAYS} tracked days.`,
      alternative: 'Learn my baseline instead',
      verb,
    };
  }
  if (newM > typical) {
    return {
      title: `${verb} your limit to ${formatDistance(newM)}?`,
      body: `That’s above your usual day of about ${formatDistance(typical)}, so most days would `
        + 'pass under it without you noticing. Limits work best a little below what you '
        + 'normally scroll.',
      verb,
    };
  }
  if (newM < typical * 0.5) {
    const suggested = suggestedLimit(typical);
    return {
      title: `${verb} your limit to ${formatDistance(newM)}?`,
      body: `That’s less than half your usual day of about ${formatDistance(typical)}, so most `
        + 'days will count as over. Big cuts are hard to keep. About '
        + `${formatDistance(suggested)} is a steadier start.`,
      alternative: suggested !== newM ? `Use ${formatDistance(suggested)} instead` : null,
      suggested,
      verb,
    };
  }
  return {
    title: `${verb} your limit to ${formatDistance(newM)}?`,
    body: `That’s ${Math.round((1 - newM / typical) * 100)}% under your usual day of about `
      + `${formatDistance(typical)}. Clean days are judged against it from today.`,
    verb,
  };
}

function confirmLimit(caution, newM) {
  const dialog = $('limit-confirm');
  $('limit-confirm-title').textContent = caution.title;
  $('limit-confirm-body').textContent = caution.body;
  $('limit-confirm-ok').textContent = `${caution.verb} to ${formatDistance(newM)}`;
  const alt = $('limit-confirm-alt');
  alt.hidden = !caution.alternative;
  alt.textContent = caution.alternative ?? '';
  dialog.returnValue = 'cancel';
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue || 'cancel'), { once: true });
    dialog.showModal();
  });
}

$('limit-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = $('limit-input');
  const value = parseDailyLimit(input.value);
  if (value === null) {
    input.setAttribute('aria-invalid', 'true');
    note('limit-note', input.value.trim() === ''
      ? 'Enter your daily limit in metres.'
      : `Between ${MIN_DAILY_LIMIT_M} and ${MAX_DAILY_LIMIT_M.toLocaleString('en-US')} m, so a `
        + "typo can't break the game.", 'error');
    input.focus();
    return;
  }
  input.removeAttribute('aria-invalid');
  const current = dailyLimit(await readSettings());
  const { typical } = await typicalDay();
  const caution = cautionFor(value, typical, current);
  const choice = await confirmLimit(caution, value);
  if (choice === 'cancel') {
    note('limit-note', 'Nothing was changed.');
    return;
  }
  if (choice === 'alternative' && caution.suggested === undefined) {
    await startBaseline();
    input.value = '';
    note('limit-note', `Learning your baseline. Your limit is set after ${BASELINE_DAYS} tracked days.`, 'good');
  } else {
    const chosen = choice === 'alternative' ? caution.suggested : value;
    await setDailyLimit(chosen);
    input.blur();
    note('limit-note', `Daily limit set to ${formatDistance(chosen)}.`, 'good');
  }
  await Promise.all([renderLimit(), renderTrek()]);
});

$('limit-input').addEventListener('input', () => $('limit-input').removeAttribute('aria-invalid'));

$('limit-baseline').addEventListener('click', async () => {
  await startBaseline();
  note('limit-note', `Learning your baseline. Your limit is set after ${BASELINE_DAYS} tracked days.`, 'good');
  await Promise.all([renderLimit(), renderTrek()]);
});

$('limit-clear').addEventListener('click', async () => {
  await setDailyLimit(null);
  note('limit-note', 'Tracking without a limit. No clean-day judgments, and none set automatically.');
  await Promise.all([renderLimit(), renderTrek()]);
  $('limit-input').focus();
});

// ————————————————————————————— Sites —————————————————————————————

async function renderSites() {
  const settings = await readSettings();
  setToggle('track-everything', settings.trackEverything);

  const sites = listedSites(settings);
  const host = $('site-list');
  // The list is rebuilt on every change (including ones arriving via storage.onChanged), so
  // remember which switch had focus and put it back, rather than dropping keyboard users
  // to the top of the page.
  const focused = host.contains(document.activeElement) ? document.activeElement.dataset.domain : null;
  host.replaceChildren();
  // Under "track everything" the per-site switches are kept (they are what applies again the
  // moment it is turned off) but dimmed, so nobody thinks switching one off stops it.
  host.classList.toggle('overridden', settings.trackEverything);

  for (const site of sites) {
    const row = document.createElement('div');
    row.className = 'site';

    const name = document.createElement('div');
    name.className = 'site-name';
    const label = document.createElement('b');
    label.textContent = site.label;
    const domain = document.createElement('span');
    domain.textContent = site.domain;
    name.append(label, domain);

    const toggle = document.createElement('button');
    toggle.className = 'toggle';
    toggle.type = 'button';
    toggle.setAttribute('role', 'switch');
    toggle.setAttribute('aria-checked', site.tracked ? 'true' : 'false');
    toggle.setAttribute('aria-label', `Track ${site.label}`);
    toggle.addEventListener('click', async () => {
      await setSiteTracked(site.domain, !site.tracked);
      renderSites();
    });
    toggle.dataset.domain = site.domain;

    row.append(name);
    if (site.custom) {
      // Only user-added sites can be forgotten. The four built-ins can be switched off but
      // not removed -- mirroring Prefs, where TRACKED_APPS is a constant and customApps is
      // the mutable half.
      const remove = document.createElement('button');
      remove.className = 'remove';
      remove.type = 'button';
      remove.textContent = 'Forget';
      remove.setAttribute('aria-label', `Forget ${site.domain}`);
      remove.title = `Remove ${site.domain} from the list. Measured history is kept.`;
      remove.addEventListener('click', async () => {
        await removeCustomSite(site.domain);
        note('add-note', `Removed ${site.domain}. Its measured history is still in your data.`);
        await renderSites();
        $('add-input').focus();
      });
      row.append(remove);
    }
    row.append(toggle);
    host.append(row);
  }

  if (focused) host.querySelector(`[data-domain="${CSS.escape(focused)}"]`)?.focus();

  const tracked = sites.filter((site) => site.tracked).length;
  $('tracked-count').textContent = settings.trackEverything
    ? 'Every site'
    : `${tracked} of ${sites.length} on`;
}

$('track-everything').addEventListener('click', async () => {
  const settings = await readSettings();
  await setTrackEverything(!settings.trackEverything);
  renderSites();
});

$('add-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const input = $('add-input');
  const raw = input.value;
  if (raw.trim() === '') {
    note('add-note', 'Type a domain or paste a link, e.g. reddit.com.', 'error');
    input.focus();
    return;
  }
  const domain = parseSiteInput(raw);
  if (!domain) {
    input.setAttribute('aria-invalid', 'true');
    note('add-note', `"${raw.trim()}" isn't a domain we can track. Try something like reddit.com.`, 'error');
    return;
  }
  input.removeAttribute('aria-invalid');
  const settings = await readSettings();
  if (settings.trackedSites.includes(domain)) {
    note('add-note', `${domain} is already on the list.`);
    return;
  }
  await addCustomSite(domain, siteName(domain, customLabels(settings)));
  input.value = '';
  // The service worker filters on arrival (content/measure.js), so an open tab starts
  // counting on its next flush. No reload needed.
  note('add-note', `Tracking ${domain}. It counts from your next scroll there.`, 'good');
  renderSites();
});

$('add-input').addEventListener('input', () => $('add-input').removeAttribute('aria-invalid'));

// ———————————————————————— History, records, badges ————————————————————————

/**
 * The phone's History and Badges tabs, condensed: records, eight weeks, and the full badge
 * ladder with progress. Read straight from IndexedDB like the popup -- no account involved.
 */
async function renderTrek() {
  const [totals, settings] = await Promise.all([dayTotals(), readSettings()]);
  const today = todayKey();
  const limit = dailyLimit(settings);

  let allTime = 0;
  for (const value of totals.values()) allTime += value;
  const record = personalRecord(totals);
  const streak = trekStreak(totals.keys(), today);
  const dayMeters = new Map([...totals].map(([key, um]) => [key, micrometresToMetres(um)]));
  // Days before the limit began are never scored against it.
  const earliest = [...dayMeters.keys()].sort()[0];
  const firstDay = settings.limitSince
    ? (earliest !== undefined && earliest > settings.limitSince ? earliest : settings.limitSince)
    : undefined;
  const clean = limit === null ? null : limitStreak(dayMeters, limit, today, firstDay);

  $('rec-today').textContent = metres(totals.get(today) ?? 0);
  $('rec-week').textContent = metres(totalThisWeek(totals, today));
  $('rec-month').textContent = metres(totalThisMonth(totals, today));
  $('rec-all').textContent = metres(allTime);
  $('rec-best').textContent = record ? metres(record[1]) : '—';
  $('rec-best-when').textContent = record ? dayLabel(record[0]) : 'No days yet';
  $('rec-streak').textContent = plural(streak, 'day');
  // Clean days only exist with a limit; baseline mode says nothing here at all.
  $('rec-clean').textContent = clean === null ? '' : `${plural(clean, 'day')} within limit`;

  const delta = weekOverWeekDelta(totals, today);
  $('history-delta').textContent = delta === null
    ? ''
    : `${delta >= 0 ? '↑' : '↓'} ${Math.abs(delta)}% vs last week`;

  // Eight weeks on the shared rail primitive, newest first: the question is usually "how
  // was this week", so it goes where the eye lands.
  const weeks = weeklyBuckets(totals, 8, today).reverse();
  const peak = Math.max(...weeks.map((week) => week.value), 1);
  const host = $('weeks');
  host.replaceChildren();
  if (totals.size === 0) {
    const empty = document.createElement('p');
    empty.className = 'faint';
    empty.textContent = 'Nothing measured yet. Weeks appear here as you scroll tracked sites.';
    host.append(empty);
  } else {
    const list = document.createElement('ul');
    list.className = 'week-rows';
    weeks.forEach((week, index) => {
      const item = document.createElement('li');
      item.className = 'measure-row';
      const line = document.createElement('div');
      line.className = 'line';
      const label = document.createElement('span');
      label.className = 'label';
      label.textContent = index === 0 ? 'This week' : `Week of ${week.label}`;
      const value = document.createElement('span');
      value.className = 'value num';
      value.textContent = metres(week.value);
      line.append(label, value);
      const rail = document.createElement('div');
      rail.className = 'rail';
      rail.setAttribute('aria-hidden', 'true');
      const fill = document.createElement('i');
      fill.style.width = `${(week.value / peak) * 100}%`;
      fill.classList.toggle('zero', week.value === 0);
      rail.append(fill);
      item.append(line, rail);
      list.append(item);
    });
    host.append(list);
  }

  const all = badges(
    micrometresToMetres(allTime), micrometresToMetres(record?.[1] ?? 0), streak, clean ?? 0,
  );
  // Clean-day badges are hidden in baseline mode: a ladder you cannot climb without a limit
  // you never chose is a judgment, not an achievement.
  const shown = limit === null ? all.filter((badge) => !badge.id.startsWith('clean_')) : all;
  const earned = shown.filter((badge) => badge.earned).length;
  $('badge-count').textContent = `${earned} of ${shown.length} earned`;
  $('badge-lede').textContent = limit === null
    ? 'Earned from your local history alone. Clean-day badges appear once you set a daily limit.'
    : 'Earned from your local history alone.';

  const badgeHost = $('badge-list');
  badgeHost.replaceChildren();
  for (const badge of shown) {
    const item = document.createElement('li');
    item.className = `badge${badge.earned ? ' earned' : ''}`;
    const emoji = document.createElement('span');
    emoji.className = 'badge-emoji';
    emoji.setAttribute('aria-hidden', 'true');
    emoji.textContent = badge.emoji;
    const text = document.createElement('div');
    text.className = 'badge-text';
    const title = document.createElement('b');
    title.textContent = badge.label;
    const detail = document.createElement('span');
    // State in words, not just opacity: "Earned" or how far along.
    detail.textContent = badge.earned
      ? `Earned · ${badge.detail}`
      : `${Math.floor(badge.progress * 100)}% · ${badge.detail}`;
    text.append(title, detail);
    item.append(emoji, text);
    if (!badge.earned) {
      const rail = document.createElement('div');
      rail.className = 'rail';
      rail.setAttribute('aria-hidden', 'true');
      const fill = document.createElement('i');
      fill.style.width = `${badge.progress * 100}%`;
      fill.classList.toggle('zero', badge.progress === 0);
      rail.append(fill);
      text.append(rail);
    }
    badgeHost.append(item);
  }
}

// ———————————————————————————— Account ————————————————————————————

/** True while the sync note is showing an error that came from the worker's status. */
let statusErrorShown = false;

async function renderAccount() {
  const status = await ask('trek/sync-status');
  const settings = await readSettings();
  const signedIn = Boolean(status.account);

  const banner = $('config-banner');
  banner.hidden = !status.configError;
  if (status.configError) $('config-message').textContent = status.configError;

  setToggle('opt-in', settings.leaderboardOptIn);
  // The switch reads as the reveal (on = Google name), so it shows the inverse of the
  // stored anonymous flag — same framing as the phone's "Show my name" toggle.
  setToggle('anonymous', !settings.anonymous);
  // Publishing needs an account. An opted-in user who signed out keeps the switch usable,
  // so they are never locked out of withdrawing.
  $('opt-in').disabled = !signedIn && !settings.leaderboardOptIn;
  $('anonymous').disabled = !signedIn;
  $('sync-button').disabled = !signedIn || !settings.leaderboardOptIn || Boolean(status.configError);
  $('auth-button').disabled = Boolean(status.configError) && !signedIn;

  if (signedIn) {
    $('account-name').textContent = status.account.displayName || 'Signed in';
    $('account-detail').textContent = settings.leaderboardOptIn
      ? 'Syncing this browser as the "web" source.'
      : 'Signed in. Nothing is published until you turn the leaderboard on.';
    $('auth-button').textContent = 'Sign out';
    const code = await friendCode(status.account.uid);
    $('friend-code').textContent = formatFriendCode(code);
    $('copy-code').hidden = false;
    $('copy-code').dataset.code = formatFriendCode(code);
    $('handle-preview').textContent = settings.anonymous
      ? `Off — publishing anonymously as "${await anonymousHandle(status.account.uid)}".`
      : `On — publishing as "${status.account.displayName || 'your Google name'}" with photo.`;
  } else {
    $('account-name').textContent = 'Not signed in';
    $('account-detail').textContent = 'Sign in with Google to sync across devices.';
    $('auth-button').textContent = 'Sign in';
    $('friend-code').textContent = 'Sign in to get yours.';
    $('copy-code').hidden = true;
    $('handle-preview').textContent = 'Anonymous by default — your handle appears after sign-in.';
  }

  $('sync-detail').textContent = !signedIn
    ? 'Sign in and turn on the leaderboard to sync.'
    : !settings.leaderboardOptIn
      ? 'Off until you publish to the leaderboard.'
      : 'Runs every 30 minutes while you are publishing.';

  const ago = syncedAgo(status.lastSyncedAt);
  $('sync-when').textContent = !settings.leaderboardOptIn
    ? 'Off'
    : status.lastError
      ? 'Sync failed'
      : (ago ? `Synced ${ago}` : 'Not synced yet');
  if (status.lastError && settings.leaderboardOptIn) {
    const retry = status.nextAttemptAt > Date.now()
      ? ` Retrying automatically in about ${Math.ceil((status.nextAttemptAt - Date.now()) / 60000)} min.`
      : '';
    note('sync-note', `${status.lastError}${retry}`, 'error');
    statusErrorShown = true;
  } else if (statusErrorShown) {
    // Only clear an error this function put there. Clearing unconditionally erased every
    // success message ("Signed in.", "Published.") the instant it was written, because
    // each handler calls renderAccount() right after setting its note.
    note('sync-note', '');
    statusErrorShown = false;
  }
}

/** For handlers that write their own note: it replaces any status error on screen. */
function actionNote(text, kind = '') {
  statusErrorShown = false;
  note('sync-note', text, kind);
}

$('auth-button').addEventListener('click', async () => {
  const status = await ask('trek/sync-status');
  const button = $('auth-button');
  button.disabled = true;
  try {
    if (status.account) {
      await signOut();
      actionNote('Signed out. Your local history is untouched.');
    } else {
      // Must run here, in a page, from a click: Chrome refuses an interactive
      // launchWebAuthFlow started anywhere else.
      actionNote('Opening Google sign-in…');
      await signIn();
      actionNote('Signed in. Turn on the leaderboard below to publish.', 'good');
    }
  } catch (error) {
    actionNote(error.message, 'error');
  } finally {
    button.disabled = false;
    renderAccount();
  }
});

$('opt-in').addEventListener('click', async () => {
  const toggle = $('opt-in');
  const settings = await readSettings();
  const next = !settings.leaderboardOptIn;
  setToggle('opt-in', next);
  // One request at a time: a double-click mid-backfill would otherwise race an opt-in
  // against the opt-out it triggered.
  toggle.disabled = true;
  try {
    if (next) {
      await setLeaderboardOptIn(true);
      actionNote('Publishing your whole history — this may take a moment.');
      const result = await ask('trek/opted-in');
      if (!result?.lastError) actionNote('Published. This browser now syncs every 30 minutes.', 'good');
    } else {
      // Withdraw first, then flip the flag: doing it the other way round leaves a window
      // in which the flag says "opted out" while the documents are still published, and a
      // browser closed inside that window never withdraws them.
      actionNote('Removing what this browser published…');
      await ask('trek/opted-out');
      await setLeaderboardOptIn(false);
      actionNote('This browser no longer publishes anything.', 'good');
    }
  } catch (error) {
    actionNote(error.message, 'error');
  }
  toggle.disabled = false;
  renderAccount();
});

$('anonymous').addEventListener('click', async () => {
  const toggle = $('anonymous');
  toggle.disabled = true;
  try {
    const settings = await readSettings();
    await setAnonymous(!settings.anonymous);
    await renderAccount();
    // The published name changes immediately rather than at the next alarm, so the toggle
    // means what it says the moment it is flipped.
    if ((await readSettings()).leaderboardOptIn) await ask('trek/sync-now');
  } catch (error) {
    actionNote(error.message, 'error');
  }
  toggle.disabled = false;
  renderAccount();
});

$('copy-code').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText($('copy-code').dataset.code ?? '');
    actionNote('Friend code copied.', 'good');
  } catch {
    actionNote('Could not copy. Select the code and copy it by hand.', 'error');
  }
});

$('sync-button').addEventListener('click', async () => {
  const button = $('sync-button');
  button.disabled = true;
  actionNote('Syncing…');
  try {
    const result = await ask('trek/sync-now');
    if (!result?.lastError) actionNote('Synced.', 'good');
  } catch (error) {
    actionNote(error.message, 'error');
  } finally {
    button.disabled = false;
    renderAccount();
  }
});

// ————————————————————————————— Data —————————————————————————————

async function renderData() {
  const rows = await allRows();
  const days = new Set(rows.map((row) => row.date)).size;
  $('row-count').textContent = `${plural(rows.length, 'row')} · ${plural(days, 'day')}`;
  $('export-button').disabled = rows.length === 0;
}

$('export-button').addEventListener('click', async () => {
  const [rows, settings] = await Promise.all([allRows(), readSettings()]);
  if (rows.length === 0) {
    note('data-note', 'Nothing measured yet, so there is nothing to export.');
    return;
  }
  const blob = new Blob([toCsv(rows, customLabels(settings))], { type: 'text/csv' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = CSV_FILENAME;
  link.click();
  // Revoked on the next task, not immediately: revoking synchronously after click() races
  // the browser's own fetch of the blob and produces an empty file on some builds.
  setTimeout(() => URL.revokeObjectURL(url), 30_000);
  note('data-note', `Exported ${plural(rows.length, 'row')}.`, 'good');
});

/**
 * Two deliberate clicks. The first arms the button and renames it to say what the second
 * one does; ten seconds of inaction disarms it again, so a page left open does not sit
 * there with a loaded gun.
 */
let disarmTimer = null;
$('wipe-button').addEventListener('click', async (event) => {
  const button = event.currentTarget;
  if (button.dataset.armed !== 'true') {
    button.dataset.armed = 'true';
    button.textContent = 'Click again to delete everything';
    note('data-note', 'This erases all local measurements. There is no undo.', 'error');
    clearTimeout(disarmTimer);
    disarmTimer = setTimeout(() => {
      button.dataset.armed = 'false';
      button.textContent = 'Delete everything';
      note('data-note', '');
    }, 10_000);
    return;
  }
  clearTimeout(disarmTimer);
  button.dataset.armed = 'false';
  button.textContent = 'Delete everything';
  try {
    await wipeLocalData();
    note('data-note', 'Local data deleted.', 'good');
  } catch (error) {
    note('data-note', `Could not delete local data: ${error.message}`, 'error');
  }
  renderData();
  renderTrek();
});

// Settings changed elsewhere (the popup's "Count this site", another options tab) show up
// here without a reload.
api.storage.onChanged.addListener((changes, area) => {
  if (area !== 'local' || !changes.settings) return;
  renderSites();
  renderLimit();
  renderTrek();
});

async function render() {
  await Promise.all([renderLimit(), renderSites(), renderTrek(), renderData()]);
  // Arriving from the popup's "Set a daily limit": land in the field, ready to type.
  if (location.hash === '#limit') $('limit-input').focus();
  await renderAccount();
}

render().catch((error) => actionNote(error.message, 'error'));
