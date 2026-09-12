/**
 * The options page: sites, account, and the two irreversible buttons.
 *
 * Sync work is delegated to the service worker rather than done here. Not for tidiness --
 * `chrome.identity.launchWebAuthFlow` needs a user gesture in a page, so sign-in genuinely
 * runs here -- but because a sync started in this tab dies when the tab closes, and a user
 * who opts in and immediately closes settings must still get their backfill.
 */

import { api } from '../lib/browser.js';
import { allRows, wipeLocalData } from '../lib/db.js';
import { anonymousHandle, formatFriendCode, friendCode } from '../lib/identity.js';
import { CSV_FILENAME, toCsv } from '../lib/csv.js';
import { parseSiteInput, siteName } from '../lib/domain.js';
import { signIn, signOut } from '../lib/auth.js';
import { syncedAgo } from '../lib/sync.js';
import {
  addCustomSite, customLabels, listedSites, readSettings, removeCustomSite,
  setAnonymous, setLeaderboardOptIn, setSiteTracked, setTrackEverything,
} from '../lib/settings.js';

const $ = (id) => document.getElementById(id);

/** Round-trips a message through the worker and unwraps its `{ ok, result }` envelope. */
async function ask(type) {
  const reply = await api.runtime.sendMessage({ type });
  if (!reply?.ok) throw new Error(reply?.error ?? 'The extension did not respond.');
  return reply.result;
}

function note(id, text, kind = '') {
  const element = $(id);
  element.textContent = text;
  element.className = `note ${kind}`.trim();
}

function setToggle(id, on) {
  $(id).setAttribute('aria-checked', on ? 'true' : 'false');
}

// ————————————————————————————— Sites —————————————————————————————

async function renderSites() {
  const settings = await readSettings();
  setToggle('track-everything', settings.trackEverything);

  const sites = listedSites(settings);
  const host = $('site-list');
  host.replaceChildren();

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

    row.append(name);
    if (site.custom) {
      // Only user-added sites can be forgotten. The four built-ins can be switched off but
      // not removed -- mirroring Prefs, where TRACKED_APPS is a constant and customApps is
      // the mutable half.
      const remove = document.createElement('button');
      remove.className = 'remove';
      remove.type = 'button';
      remove.textContent = 'Forget';
      remove.title = `Remove ${site.domain} from the list. Measured history is kept.`;
      remove.addEventListener('click', async () => {
        await removeCustomSite(site.domain);
        note('add-note', `Removed ${site.domain}. Its measured history is still in your data.`);
        renderSites();
      });
      row.append(remove);
    }
    row.append(toggle);
    host.append(row);
  }

  const tracked = sites.filter((site) => site.tracked).length;
  $('tracked-count').textContent = settings.trackEverything
    ? 'everything'
    : `${tracked} of ${sites.length} on`;
}

$('track-everything').addEventListener('click', async () => {
  const settings = await readSettings();
  await setTrackEverything(!settings.trackEverything);
  renderSites();
});

$('add-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const raw = $('add-input').value;
  const domain = parseSiteInput(raw);
  if (!domain) {
    note('add-note', `"${raw.trim()}" isn't a domain we can track. Try something like reddit.com.`, 'error');
    return;
  }
  const settings = await readSettings();
  if (settings.trackedSites.includes(domain)) {
    note('add-note', `${domain} is already on the list.`);
    return;
  }
  await addCustomSite(domain, siteName(domain, customLabels(settings)));
  $('add-input').value = '';
  note('add-note', `Tracking ${domain}. Reload any open tab on it to start counting.`, 'good');
  renderSites();
});

// ———————————————————————————— Account ————————————————————————————

async function renderAccount() {
  const status = await ask('trek/sync-status');
  const settings = await readSettings();

  const banner = $('config-banner');
  banner.hidden = !status.configError;
  if (status.configError) $('config-message').textContent = status.configError;

  setToggle('opt-in', settings.leaderboardOptIn);
  // The switch reads as the reveal (on = Google name), so it shows the inverse of the
  // stored anonymous flag — same framing as the phone's "Show my name" toggle.
  setToggle('anonymous', !settings.anonymous);

  if (status.account) {
    $('account-name').textContent = status.account.displayName || 'Signed in';
    $('account-detail').textContent = settings.leaderboardOptIn
      ? 'Syncing this browser as the "web" source.'
      : 'Signed in. Nothing is published until you turn the leaderboard on.';
    $('auth-button').textContent = 'Sign out';
    const code = await friendCode(status.account.uid);
    $('friend-code').textContent = formatFriendCode(code);
    $('handle-preview').textContent = settings.anonymous
      ? `Anonymous — publishing as "${await anonymousHandle(status.account.uid)}".`
      : `Publishing as "${status.account.displayName || 'your Google name'}" with photo.`;
  } else {
    $('account-name').textContent = 'Not signed in';
    $('account-detail').textContent = 'Sign in with Google to sync across devices.';
    $('auth-button').textContent = 'Sign in';
    $('friend-code').textContent = 'Sign in to get yours.';
    $('handle-preview').textContent = 'Anonymous by default — your handle appears after sign-in.';
  }

  $('sync-when').textContent = status.lastError
    ? 'sync failed'
    : (syncedAgo(status.lastSyncedAt) ?? 'never synced');
  if (status.lastError) {
    const retry = status.nextAttemptAt > Date.now()
      ? ` Retrying automatically in about ${Math.ceil((status.nextAttemptAt - Date.now()) / 60000)} min.`
      : '';
    note('sync-note', `${status.lastError}${retry}`, 'error');
  } else if (!status.configError) {
    note('sync-note', '');
  }
}

$('auth-button').addEventListener('click', async () => {
  const status = await ask('trek/sync-status');
  const button = $('auth-button');
  button.disabled = true;
  try {
    if (status.account) {
      await signOut();
      note('sync-note', 'Signed out. Your local history is untouched.');
    } else {
      // Must run here, in a page, from a click: Chrome refuses an interactive
      // launchWebAuthFlow started anywhere else.
      await signIn();
      note('sync-note', 'Signed in.', 'good');
    }
  } catch (error) {
    note('sync-note', error.message, 'error');
  } finally {
    button.disabled = false;
    renderAccount();
  }
});

$('opt-in').addEventListener('click', async () => {
  const settings = await readSettings();
  const next = !settings.leaderboardOptIn;
  setToggle('opt-in', next);
  try {
    if (next) {
      await setLeaderboardOptIn(true);
      note('sync-note', 'Publishing your whole history — this may take a moment.');
      await ask('trek/opted-in');
    } else {
      // Withdraw first, then flip the flag: doing it the other way round leaves a window
      // in which the flag says "opted out" while the documents are still published, and a
      // browser closed inside that window never withdraws them.
      note('sync-note', 'Removing what this browser published…');
      await ask('trek/opted-out');
      await setLeaderboardOptIn(false);
      note('sync-note', 'This browser no longer publishes anything.', 'good');
    }
  } catch (error) {
    note('sync-note', error.message, 'error');
  }
  renderAccount();
});

$('anonymous').addEventListener('click', async () => {
  const settings = await readSettings();
  await setAnonymous(!settings.anonymous);
  await renderAccount();
  // The published name changes immediately rather than at the next alarm, so the toggle
  // means what it says the moment it is flipped.
  if ((await readSettings()).leaderboardOptIn) await ask('trek/sync-now');
  renderAccount();
});

$('sync-button').addEventListener('click', async () => {
  const button = $('sync-button');
  button.disabled = true;
  note('sync-note', 'Syncing…');
  try {
    await ask('trek/sync-now');
  } catch (error) {
    note('sync-note', error.message, 'error');
  } finally {
    button.disabled = false;
    renderAccount();
  }
});

// ————————————————————————————— Data —————————————————————————————

async function renderData() {
  const rows = await allRows();
  const days = new Set(rows.map((row) => row.date)).size;
  $('row-count').textContent = `${rows.length} rows · ${days} days`;
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
  note('data-note', `Exported ${rows.length} rows.`, 'good');
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
  await wipeLocalData();
  note('data-note', 'Local data deleted.', 'good');
  renderData();
});

async function render() {
  await Promise.all([renderSites(), renderAccount(), renderData()]);
}

render().catch((error) => note('sync-note', error.message, 'error'));
