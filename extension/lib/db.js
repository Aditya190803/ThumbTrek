/**
 * IndexedDB: the source of truth.
 *
 * Contract §5 is explicit that local storage is the truth and the network is a mirror --
 * today's total, the streak, records, badges and history all have to work with no account
 * and no network, forever. So this store mirrors the phone's Room `daily_scroll` table:
 * one row per site per day, accumulated, keyed by `(date, domain)`.
 *
 * ## Why IndexedDB and not chrome.storage.local
 *
 * `chrome.storage.local` is a whole-value key/value store: accumulating into it means
 * read-modify-write of the entire object on every flush, with no transaction around it, so
 * two flushes landing together lose one of them. IndexedDB gives a real readwrite
 * transaction over a single row, which is precisely the `accumulate()` the Room DAO does.
 * `chrome.storage.local` keeps settings, where whole-value writes are the right shape.
 *
 * ## Stores
 *
 *  - `days`   -- keyPath `['date', 'domain']`, the composite primary key from Room.
 *                `{ date, domain, um }`. µm, integer, never a float (contract §1).
 *  - `pushed` -- keyPath `date`. `{ date, um }`: what the server was last told each day's
 *                total was. Which days need pushing is then a *fingerprint comparison*
 *                (`SyncModel.dirtyDays`) rather than a flag someone has to remember to set:
 *                rows only ever accumulate, so no per-site figure inside a day can change
 *                without changing the day's total. An empty store means "push everything",
 *                which is exactly what a first opt-in and a re-opt-in both want.
 *  - `meta`   -- keyPath `key`. Sync bookkeeping that must survive a browser restart.
 */

const DB_NAME = 'thumbtrek';
const DB_VERSION = 1;

export const STORE_DAYS = 'days';
export const STORE_PUSHED = 'pushed';
export const STORE_META = 'meta';

let handle = null;

function open() {
  if (handle) return handle;
  handle = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(STORE_DAYS)) {
        const days = db.createObjectStore(STORE_DAYS, { keyPath: ['date', 'domain'] });
        // Every read is either "one day" or "all days"; a date index serves the first
        // without scanning, and a cursor over the primary key serves the second in order.
        days.createIndex('date', 'date', { unique: false });
      }
      if (!db.objectStoreNames.contains(STORE_PUSHED)) {
        db.createObjectStore(STORE_PUSHED, { keyPath: 'date' });
      }
      if (!db.objectStoreNames.contains(STORE_META)) {
        db.createObjectStore(STORE_META, { keyPath: 'key' });
      }
    };
    request.onsuccess = () => {
      // A second tab upgrading the schema would otherwise leave this connection blocking
      // it forever. Dropping the handle makes the next call reopen at the new version.
      request.result.onversionchange = () => {
        request.result.close();
        handle = null;
      };
      resolve(request.result);
    };
    request.onerror = () => reject(request.error);
  });
  return handle;
}

/**
 * Runs `body` inside one transaction and resolves with whatever `body` passed to `done`.
 *
 * Resolving on `oncomplete` rather than on the last request's `onsuccess` is the point:
 * a value read out of a request that has succeeded is not yet durable, and a readwrite
 * transaction can still abort after every request in it has fired. The caller gets its
 * answer only once the transaction has actually committed.
 */
function run(storeNames, mode, body) {
  return open().then((db) => new Promise((resolve, reject) => {
    const tx = db.transaction(storeNames, mode);
    let result;
    tx.oncomplete = () => resolve(result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error ?? new Error('transaction aborted'));
    body(tx, (value) => { result = value; });
  }));
}

const promise = (request) => new Promise((resolve, reject) => {
  request.onsuccess = () => resolve(request.result);
  request.onerror = () => reject(request.error);
});

/**
 * Add `um` to `(date, domain)`, creating the row if it is new.
 *
 * This is `ScrollDao.accumulate` -- one transaction, so two concurrent flushes from two
 * tabs both land instead of one overwriting the other's read. Nothing here marks the day
 * for sync: what needs pushing is derived by comparing totals against the `pushed`
 * fingerprints, so the measurement path cannot forget to raise a flag.
 */
export async function accumulate(date, domain, um) {
  if (!(um > 0)) return;
  await run([STORE_DAYS], 'readwrite', (tx) => {
    const days = tx.objectStore(STORE_DAYS);
    const get = days.get([date, domain]);
    get.onsuccess = () => {
      const existing = get.result;
      days.put({ date, domain, um: (existing?.um ?? 0) + um });
    };
  });
}

/** Every row for one day, as `{ domain, um }`, biggest first. */
export async function rowsForDate(date) {
  const rows = await run([STORE_DAYS], 'readonly', (tx, done) => {
    const request = tx.objectStore(STORE_DAYS).index('date').getAll(IDBKeyRange.only(date));
    request.onsuccess = () => done(request.result);
  });
  return (rows ?? []).sort((a, b) => b.um - a.um || (a.domain < b.domain ? -1 : 1));
}

/** Every row ever, oldest first. Used by CSV export and by sync's day-document builder. */
export async function allRows() {
  const rows = await run([STORE_DAYS], 'readonly', (tx, done) => {
    const request = tx.objectStore(STORE_DAYS).getAll();
    request.onsuccess = () => done(request.result);
  });
  return (rows ?? []).sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1
    : a.domain < b.domain ? -1 : a.domain > b.domain ? 1 : 0));
}

/**
 * `Map<dateKey, totalUm>` over all of history -- what every stats function takes.
 * Aggregated with a cursor rather than `getAll()` so the whole table is never materialised
 * as objects just to be summed.
 */
export async function dayTotals() {
  return run([STORE_DAYS], 'readonly', (tx, done) => {
    const totals = new Map();
    const request = tx.objectStore(STORE_DAYS).openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) return done(totals);
      const { date, um } = cursor.value;
      totals.set(date, (totals.get(date) ?? 0) + um);
      cursor.continue();
    };
  });
}

/** `Map<dateKey, um>`: what the server was last told. Empty means "nothing pushed yet". */
export async function pushedTotals() {
  const rows = await run([STORE_PUSHED], 'readonly', (tx, done) => {
    const request = tx.objectStore(STORE_PUSHED).getAll();
    request.onsuccess = () => done(request.result);
  });
  return new Map((rows ?? []).map((row) => [row.date, row.um]));
}

/**
 * Record the totals a commit actually published, as `[date, um]` pairs.
 *
 * The µm written is the one that was *sent*, not the one that is in the table now: a scroll
 * landing mid-sync raises the local total, and stamping that as pushed would mean the
 * server never hears about it. Recording what went over the wire leaves the day looking
 * dirty again on the next pass, which is correct, because it is.
 */
export async function recordPushed(entries) {
  if (entries.length === 0) return;
  await run([STORE_PUSHED], 'readwrite', (tx) => {
    const store = tx.objectStore(STORE_PUSHED);
    for (const [date, um] of entries) store.put({ date, um });
  });
}

/**
 * Forget everything the server was told. Opting out calls this so a later re-opt-in
 * backfills the whole history instead of resuming from a cursor that describes documents
 * the opt-out just deleted (contract §5, "Opting out with several clients").
 */
export async function clearPushed() {
  await run([STORE_PUSHED], 'readwrite', (tx) => {
    tx.objectStore(STORE_PUSHED).clear();
  });
}

export async function getMeta(key, fallback = null) {
  const row = await run([STORE_META], 'readonly', (tx, done) => {
    const request = tx.objectStore(STORE_META).get(key);
    request.onsuccess = () => done(request.result);
  });
  return row === undefined || row === null ? fallback : row.value;
}

export async function setMeta(key, value) {
  await run([STORE_META], 'readwrite', (tx) => {
    tx.objectStore(STORE_META).put({ key, value });
  });
}

/**
 * The local wipe behind the options page's destructive action. Empties the measurement
 * stores rather than deleting the database, so an open connection in another tab keeps
 * working instead of blocking on a delete that never completes.
 */
export async function wipeLocalData() {
  await run([STORE_DAYS, STORE_PUSHED, STORE_META], 'readwrite', (tx) => {
    tx.objectStore(STORE_DAYS).clear();
    tx.objectStore(STORE_PUSHED).clear();
    tx.objectStore(STORE_META).clear();
  });
}
