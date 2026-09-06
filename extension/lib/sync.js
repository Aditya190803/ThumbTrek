/**
 * Pushing the `web` source to Firestore, and taking it back again.
 *
 * The pure half of this file is a port of `social/SyncModel.kt`, which is the protocol layer
 * the Android track already implements -- `combine`, `dirtyDays`, the day-ledger shape and
 * `syncedAgo` are its semantics, not a second derivation of them. Where the two could drift,
 * SyncModel.kt is the reference.
 *
 * The one rule the whole file is built around (contract §2): **a source owns its own numbers
 * and must never write another source's.** Everything this client writes is either a
 * `…__web` day document or the `sources.web` key, plus the combined totals that are by
 * definition everyone's. It never writes `sources.android`, never touches an `…__android`
 * day document, and never writes the legacy `*Pixels` fields, which the contract marks
 * Android-only.
 *
 * That rule is what makes a phone and a browser syncing at the same second safe. The
 * per-source numbers -- the real record -- cannot race, because no two clients ever write
 * the same field. Only the combined figure can be briefly stale, and the next sync from
 * either side corrects it.
 */

import {
  allRows, clearPushed, dayTotals, getMeta, pushedTotals, recordPushed, setMeta,
} from './db.js';
import { anonymousHandle, friendCode } from './identity.js';
import { commit, FirestoreError, getDocument, listDocumentIds } from './firestore.js';
import { currentAccount } from './auth.js';
import { loadConfig } from './config.js';
import { readSettings } from './settings.js';
import { monthKey, totalThisMonth, totalThisWeek, weekKey } from './stats.js';
import { todayKey } from './dates.js';

/** Contract §2. Short, lowercase, and never contains a '.' -- it goes in a dotted path. */
export const SOURCE = 'web';

/** `firestore.rules`: `data.apps.keys().size() <= 64`. Exceeding it is a rejected write. */
export const MAX_APPS_PER_DAY = 64;

/** Firestore allows 500 writes per commit; well under it keeps request bodies small too. */
const WRITES_PER_COMMIT = 200;

const META_STATE = 'syncState';

/** Backoff: a minute, doubling, capped at six hours. */
const BACKOFF_BASE_MS = 60_000;
const BACKOFF_MAX_MS = 6 * 60 * 60 * 1000;

/**
 * Builds one day document (`SyncModel.dayLedgers`, one day of it).
 *
 * The interesting part is the 64-key cap. Under "track everything" a busy day can easily
 * touch more than 64 domains, and a document with 65 `apps` keys is not truncated by
 * Firestore -- it is *rejected* by `validDay()`, which would silently stall sync for that
 * day forever. So the busiest 63 sites are kept by name and the rest are summed into a
 * single `other` row.
 *
 * `um` stays the full day total including everything folded into `other`, because `um` is
 * what the board and the charts rank on: the Kotlin's rule is that a truncated breakdown
 * "must not quietly shrink the day's distance". Where Android simply drops the tail, this
 * keeps it visible as `other` -- a browser under "track everything" hits the cap on an
 * ordinary day, where a phone with 64 installed feed apps does not, so a silently missing
 * remainder here would be a chart people actually look at. `other` cannot collide with a
 * real key because a registrable domain always contains a dot.
 */
export function buildDayDocument(date, rows, now = new Date()) {
  const sorted = [...rows]
    .filter((row) => row.um > 0)
    .sort((a, b) => b.um - a.um || (a.domain < b.domain ? -1 : 1));

  const apps = {};
  let total = 0;
  let other = 0;
  sorted.forEach((row, index) => {
    total += row.um;
    if (index < MAX_APPS_PER_DAY - 1) apps[row.domain] = row.um;
    else other += row.um;
  });
  if (other > 0) apps.other = other;

  return { date, source: SOURCE, um: total, apps, updatedAt: now };
}

/**
 * Contract §3 step 2 / `SyncModel.combine`: sum over *every* source, including slugs this
 * build has never heard of, so a fourth client shipping later does not read as zero here.
 *
 * A source contributes its `weekUm` **only when its own `weekKey` matches the current week**,
 * and zero otherwise. The row-level key cannot do this job once a row has several writers:
 * if the extension last synced in W36 and the phone syncs in W37, the phone stamps the row
 * W37 while `sources.web.weekUm` still holds W36's distance, and summing blindly inflates
 * the weekly figure until the extension happens to sync again -- a leaderboard that pays out
 * for stopping.
 *
 * A source carrying no key at all counts as **stale**, not as grandfathered-fresh. It can
 * only be an entry written before this rule existed; reading it as current is the one
 * mistake that inflates a live board, where reading it as stale merely understates a figure
 * its own client corrects on its next sync. `totalUm` is all-time and has no key to go stale.
 */
export function combinedTotals(sources, currentWeek, currentMonth) {
  const combined = { weekUm: 0, monthUm: 0, totalUm: 0 };
  for (const entry of Object.values(sources ?? {})) {
    if (!entry || typeof entry !== 'object') continue;
    const num = (field) => {
      const value = Number(entry[field]);
      return Number.isFinite(value) && value > 0 ? Math.round(value) : 0;
    };
    if (entry.weekKey === currentWeek) combined.weekUm += num('weekUm');
    if (entry.monthKey === currentMonth) combined.monthUm += num('monthUm');
    combined.totalUm += num('totalUm');
  }
  return combined;
}

/**
 * The days whose local total no longer matches what the server was last told
 * (`SyncModel.dirtyDays`). An empty `pushed` map returns the whole history -- that is the
 * backfill, and it is why opting out clears the map rather than tracking a cursor.
 */
export function dirtyDays(totals, pushed) {
  const dates = [];
  for (const [date, um] of totals) {
    if (pushed.get(date) !== um) dates.push(date);
  }
  return dates.sort();
}

/**
 * True when `users/{uid}` exists only because this client put it there.
 *
 * Contract §5 step 2: the whole row is deleted only by the *last remaining writer*. Taken
 * unconditionally, an opt-out here would wipe an active phone's score, which is the opposite
 * of what the user asked for. So the row survives -- with `sources.web` removed and the
 * combined figures re-rolled -- whenever another source or a legacy Android pixel total is
 * still standing on it.
 */
export function boardRowIsOursAlone(row) {
  if (!row) return false;
  const otherSources = Object.keys(row.sources ?? {}).filter((key) => key !== SOURCE);
  const hasLegacy = ['weekPixels', 'monthPixels', 'totalPixels']
    .some((field) => Number(row[field]) > 0);
  return otherSources.length === 0 && !hasLegacy;
}

/**
 * "just now" / "12 min ago" / "3 h ago" / "5 days ago", word for word from
 * `SyncModel.syncedAgo`. Null when there is no timestamp, so the UI says "never" in its own
 * words rather than rendering a fake one. Clock skew putting `then` ahead of `now` reads as
 * "just now" rather than a negative age.
 */
export function syncedAgo(then, now = Date.now()) {
  if (!then || then <= 0) return null;
  const MINUTE = 60_000;
  const HOUR = 60 * MINUTE;
  const DAY = 24 * HOUR;
  const age = now - then;
  if (age < 2 * MINUTE) return 'just now';
  if (age < HOUR) return `${Math.floor(age / MINUTE)} min ago`;
  if (age < DAY) return `${Math.floor(age / HOUR)} h ago`;
  if (age < 2 * DAY) return 'yesterday';
  return `${Math.floor(age / DAY)} days ago`;
}

function chunk(items, size) {
  const out = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/**
 * The five strings `validScore()` insists on, plus the code that makes invites resolve.
 *
 * The lengths are clamped to the limits the rules actually enforce (`displayName` <= 64,
 * `photoUrl` <= 512). Google will happily hand back a longer name or a longer avatar URL,
 * and over REST that arrives as a bare `PERMISSION_DENIED` with no indication of which
 * clause failed -- indistinguishable from being signed out. Truncating a name is a visible
 * cosmetic loss; a board row that silently never publishes is not.
 */
const clamp = (text, limit) => (text.length <= limit ? text : text.slice(0, limit));

async function identityFields(uid, account, settings, today) {
  return {
    displayName: clamp(settings.anonymous
      ? await anonymousHandle(uid)
      : (account.displayName || 'Trekker'), 64),
    photoUrl: settings.anonymous ? '' : clamp(account.photoUrl || '', 512),
    friendCode: await friendCode(uid),
    weekKey: weekKey(today),
    monthKey: monthKey(today),
  };
}

/**
 * Contract §3 step 1, with the "first write of a row must be complete" rule handled.
 *
 * `validScore()` asserts displayName / photoUrl / friendCode / weekKey / monthKey are all
 * strings and, under a merging write, sees the *post-merge* document. A merge carrying only
 * `sources.web` therefore succeeds against an existing row and is rejected against a missing
 * one -- so every write from here carries the identity fields alongside `sources.web`, which
 * makes the first write of a row complete by construction.
 *
 * The retry is the belt to that braces (`SyncModel.mergeOrRecreate`): if the merge is denied
 * anyway, read the row back. Gone means the amendment's case -- an opt-out from another
 * client deleted it between our read and our write -- and we write it whole. Still there
 * means the denial is something else, a rules change or a clock-skewed token, and
 * force-replacing the document would destroy `sources.android`. That one is surfaced.
 *
 * (Android cannot make this distinction: Firestore gives its SDK no detail about which
 * clause failed, so it retries blind. Over REST we can afford the extra read, and a read is
 * cheaper than the risk of clobbering another client's numbers.)
 */
async function writeBoardRow(uid, fields, sourceEntry) {
  const path = `users/${uid}`;
  const data = { ...fields, sources: { [SOURCE]: sourceEntry } };
  const updateMask = [...Object.keys(fields), `sources.${SOURCE}`];
  try {
    await commit([{ path, data, updateMask }]);
    return;
  } catch (error) {
    if (!(error instanceof FirestoreError) || !error.permissionDenied) throw error;
    const existing = await getDocument(path);
    if (existing) throw error;
  }
  // The row is genuinely gone. Write it whole -- no updateMask, so this is a `set`.
  await commit([{ path, data }]);
}

async function readState() {
  return (await getMeta(META_STATE)) ?? {
    lastSyncedAt: null, lastError: null, failures: 0, nextAttemptAt: 0,
  };
}

async function recordSuccess() {
  const state = { lastSyncedAt: Date.now(), lastError: null, failures: 0, nextAttemptAt: 0 };
  await setMeta(META_STATE, state);
  return state;
}

async function recordFailure(message, transient) {
  const previous = await readState();
  const failures = transient ? previous.failures + 1 : 0;
  const state = {
    ...previous,
    lastError: message,
    failures,
    // A non-transient failure -- bad config, revoked token, a rules rejection -- will not
    // fix itself by waiting, so it does not earn a backoff. It sits in the UI until the
    // user acts, and the next scheduled sync retries it immediately in case they have.
    nextAttemptAt: transient
      ? Date.now() + Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** (failures - 1))
      : 0,
  };
  await setMeta(META_STATE, state);
  return state;
}

/** What the options page renders: enough to tell "off" from "broken" from "just fine". */
export async function syncStatus() {
  const [settings, account, state] = await Promise.all([
    readSettings(), currentAccount(), readState(),
  ]);
  let configError = null;
  try {
    await loadConfig();
  } catch (error) {
    configError = error.message;
  }
  return {
    optedIn: settings.leaderboardOptIn,
    account,
    configError,
    lastSyncedAt: state.lastSyncedAt,
    lastError: state.lastError,
    failures: state.failures,
    nextAttemptAt: state.nextAttemptAt,
  };
}

/**
 * Push everything that changed. Returns a status object; never throws, because both callers
 * -- an alarm and a button -- want the failure recorded and rendered, not propagated.
 *
 * @param force  skip the backoff window. What the "Sync now" button passes.
 */
export async function syncNow({ force = false } = {}) {
  const settings = await readSettings();
  // Contract §5: nothing is written to Firestore until the user turns the leaderboard on.
  // Signing in by itself publishes nothing.
  if (!settings.leaderboardOptIn) return syncStatus();

  const account = await currentAccount();
  if (!account) return syncStatus();

  const state = await readState();
  if (!force && state.nextAttemptAt > Date.now()) return syncStatus();

  try {
    await loadConfig(); // throws ConfigError, which is not transient
    const uid = account.uid;
    const today = todayKey();
    const totals = await dayTotals();

    // --- day documents (contract §3, the private ledger) ---------------------------
    const dates = dirtyDays(totals, await pushedTotals());
    if (dates.length > 0) {
      const rows = await allRows();
      const byDate = new Map();
      for (const row of rows) {
        if (!byDate.has(row.date)) byDate.set(row.date, []);
        byDate.get(row.date).push(row);
      }
      const now = new Date();
      const writes = dates.map((date) => {
        const data = buildDayDocument(date, byDate.get(date) ?? [], now);
        return { date, um: data.um, path: `users/${uid}/days/${date}__${SOURCE}`, data };
      });
      // Batch by batch, recording the totals actually sent: a commit that fails leaves its
      // days looking dirty, which is exactly right, and one that succeeds records what went
      // over the wire rather than whatever the table says by the time it returns.
      for (const batch of chunk(writes, WRITES_PER_COMMIT)) {
        // eslint-disable-next-line no-await-in-loop -- ordered, and each commit is atomic.
        await commit(batch);
        // eslint-disable-next-line no-await-in-loop
        await recordPushed(batch.map((write) => [write.date, write.um]));
      }
    }

    // --- the board row (contract §3, the public row) --------------------------------
    let allTime = 0;
    for (const value of totals.values()) allTime += value;
    const fields = await identityFields(uid, account, settings, today);
    await writeBoardRow(uid, fields, {
      // Our own keys, alongside our own totals. Without these the combine step upstream
      // cannot tell this week's distance from a stale week's, and neither can the phone.
      weekKey: fields.weekKey,
      weekUm: totalThisWeek(totals, today),
      monthKey: fields.monthKey,
      monthUm: totalThisMonth(totals, today),
      totalUm: allTime,
      updatedAt: new Date(),
    });

    // Step 2: re-read, combine every source under the current keys, write the three fields
    // the board ranks on. Racing another client here is harmless by design -- the loser's
    // combined figure is briefly stale and the next sync from either side corrects it.
    const row = await getDocument(`users/${uid}`);
    await commit([{
      path: `users/${uid}`,
      data: { ...fields, ...combinedTotals(row?.sources, fields.weekKey, fields.monthKey) },
      updateMask: [...Object.keys(fields), 'weekUm', 'monthUm', 'totalUm'],
    }]);

    return await recordSuccess();
  } catch (error) {
    const transient = error instanceof FirestoreError && error.transient;
    await recordFailure(error.message ?? String(error), transient);
    return syncStatus();
  }
}

/**
 * Opting in publishes *everything*, not just today: the local store is the complete history,
 * and a board that starts at zero on the day you signed up is a board that lies. Clearing
 * the pushed fingerprints makes the next sync a full backfill -- contract §3, "Backfill on
 * first sign-in is a `set` per day".
 */
export async function backfillEverything() {
  await clearPushed();
}

/**
 * Opting out withdraws the `web` source and nothing else. Contract §5, "Opting out with
 * several clients", in its stated order:
 *
 * 1. Delete every `{date}__web` day document. **First**, and explicitly: deleting a parent
 *    document in Firestore does not remove its subcollections, so skipping this leaves the
 *    private ledger orphaned -- still stored, still billed, and no longer reachable by any
 *    path the client knows how to build.
 * 2. Delete `users/{uid}` only if we are the last remaining writer. Otherwise remove just
 *    `sources.web` and re-roll the combined totals from what is left.
 *
 * The day documents are found by *listing the collection* rather than by walking local
 * history, so a user who wiped their local data can still withdraw what was published.
 * Finally the pushed fingerprints are cleared, so a later re-opt-in backfills instead of
 * resuming from a cursor that describes documents this function just deleted.
 */
export async function optOutRemote() {
  const account = await currentAccount();
  if (!account) return { deleted: 0 };
  await loadConfig();
  const uid = account.uid;

  const row = await getDocument(`users/${uid}`);

  // Step 1, unconditionally -- even when the board row is already gone, because that is
  // precisely the state in which orphaned day documents are unreachable.
  const ids = await listDocumentIds(`users/${uid}/days`);
  const mine = ids.filter((id) => id.endsWith(`__${SOURCE}`));
  for (const batch of chunk(mine, WRITES_PER_COMMIT)) {
    // eslint-disable-next-line no-await-in-loop
    await commit(batch.map((id) => ({ path: `users/${uid}/days/${id}`, delete: true })));
  }

  // Step 2.
  if (row && boardRowIsOursAlone(row)) {
    await commit([{ path: `users/${uid}`, delete: true }]);
  } else if (row) {
    // A field named in the mask but absent from the body is deleted, so this removes
    // `sources.web` and leaves `sources.android` untouched.
    await commit([{ path: `users/${uid}`, data: {}, updateMask: [`sources.${SOURCE}`] }]);
    const after = await getDocument(`users/${uid}`);
    const today = todayKey();
    await commit([{
      path: `users/${uid}`,
      data: combinedTotals(after?.sources, weekKey(today), monthKey(today)),
      updateMask: ['weekUm', 'monthUm', 'totalUm'],
    }]);
  }

  await clearPushed();
  await setMeta(META_STATE, {
    lastSyncedAt: Date.now(), lastError: null, failures: 0, nextAttemptAt: 0,
  });
  return { deleted: mine.length };
}
