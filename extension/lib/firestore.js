/**
 * Firestore over REST, in about as little code as the job allows.
 *
 * No Firebase SDK: contract §6. Beyond the service-worker problem, the SDK is ~200KB of
 * bundled JS that would need a build step this extension does not have, to wrap an HTTP API
 * with four calls in it.
 *
 * ## The typed-value dance
 *
 * The REST API does not take JSON documents, it takes *typed* ones:
 * `{ um: 1840000 }` is `{ fields: { um: { integerValue: "1840000" } } }`. Two traps live in
 * there, and both are the kind that pass review and fail in production:
 *
 *  - `integerValue` is a **string**. Firestore integers are int64 and JSON numbers are
 *    doubles, so the wire format quotes them. Sending `{ integerValue: 1840000 }` unquoted
 *    is rejected; sending `{ doubleValue: 1840000 }` stores a *double*, which
 *    `firestore.rules`'s `value is int` then refuses -- a permission-denied that looks like
 *    an auth problem and is really a type problem. Contract §1: "Never send a float
 *    distance."
 *  - `updateMask.fieldPaths` uses dots for nesting, which is exactly what makes
 *    `sources.web` a merge into one key of the `sources` map rather than a replacement of
 *    the whole map. It is also why a source slug "never contains a `.`" (contract §2).
 *
 * A field named in the mask but absent from the body is **deleted**. That is how opting out
 * removes `sources.web` without touching `sources.android`.
 */

import { freshIdToken } from './auth.js';
import { loadConfig } from './config.js';

const BASE = 'https://firestore.googleapis.com/v1';

/** A Firestore call that failed. `permissionDenied` is load-bearing -- see sync.js. */
export class FirestoreError extends Error {
  constructor(message, status, body) {
    super(message);
    this.name = 'FirestoreError';
    this.status = status;
    this.body = body;
  }

  get permissionDenied() {
    return this.status === 403 || this.body?.error?.status === 'PERMISSION_DENIED';
  }

  /** Worth retrying later on an alarm rather than telling the user something is wrong. */
  get transient() {
    return this.status === 0 || this.status === 429 || this.status >= 500;
  }
}

/** JS value -> Firestore typed value. Integers stay integers; nothing here is a float. */
export function toValue(value) {
  if (value === null || value === undefined) return { nullValue: null };
  if (typeof value === 'string') return { stringValue: value };
  if (typeof value === 'boolean') return { booleanValue: value };
  if (typeof value === 'number') {
    if (!Number.isInteger(value)) {
      // Refuse rather than silently write a double the rules will bounce. Every number
      // that reaches Firestore from this client is a µm count, and those are integers.
      throw new TypeError(`Refusing to write the non-integer ${value} — distances are µm.`);
    }
    return { integerValue: String(value) };
  }
  if (value instanceof Date) return { timestampValue: value.toISOString() };
  if (Array.isArray(value)) return { arrayValue: { values: value.map(toValue) } };
  return { mapValue: { fields: toFields(value) } };
}

export function toFields(object) {
  const fields = {};
  for (const [key, value] of Object.entries(object)) fields[key] = toValue(value);
  return fields;
}

/** Firestore typed value -> JS. Integers come back as numbers; µm totals stay exact well */
/** past Number.MAX_SAFE_INTEGER's worth of scrolling (9e15 µm is nine billion kilometres). */
export function fromValue(value) {
  if (!value || typeof value !== 'object') return null;
  if ('stringValue' in value) return value.stringValue;
  if ('integerValue' in value) return Number(value.integerValue);
  if ('doubleValue' in value) return value.doubleValue;
  if ('booleanValue' in value) return value.booleanValue;
  if ('timestampValue' in value) return value.timestampValue;
  if ('nullValue' in value) return null;
  if ('arrayValue' in value) return (value.arrayValue.values ?? []).map(fromValue);
  if ('mapValue' in value) return fromFields(value.mapValue.fields ?? {});
  return null;
}

export function fromFields(fields) {
  const out = {};
  for (const [key, value] of Object.entries(fields ?? {})) out[key] = fromValue(value);
  return out;
}

async function request(path, init = {}) {
  const token = await freshIdToken();
  const url = path.startsWith('http') ? path : `${BASE}/${path}`;
  let response;
  try {
    response = await fetch(url, {
      ...init,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
        ...(init.headers ?? {}),
      },
    });
  } catch (error) {
    // Offline. Status 0 marks it transient so the caller backs off instead of surfacing
    // "sync failed" for what is really "the laptop lid was shut".
    throw new FirestoreError(`Could not reach Firestore: ${error.message}`, 0, null);
  }
  const body = response.status === 204 ? {} : await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new FirestoreError(
      body?.error?.message ?? `Firestore returned ${response.status}`,
      response.status,
      body,
    );
  }
  return body;
}

/** `projects/{p}/databases/(default)/documents` — the prefix every document name carries. */
export async function documentsRoot() {
  const config = await loadConfig();
  return `projects/${config.projectId}/databases/(default)/documents`;
}

/** Reads one document. Returns null for a 404, which is a normal state, not an error. */
export async function getDocument(relativePath) {
  const root = await documentsRoot();
  try {
    const body = await request(`${root}/${relativePath}`);
    return fromFields(body.fields);
  } catch (error) {
    if (error instanceof FirestoreError && error.status === 404) return null;
    throw error;
  }
}

/**
 * Applies writes atomically. `writes` are `{ path, data, updateMask }` for an upsert or
 * `{ path, delete: true }` for a delete.
 *
 * One `:commit` rather than N `PATCH`es: a day's worth of backfill is one round trip and
 * one all-or-nothing outcome, so a partial failure cannot leave the dirty set claiming
 * days that were never written.
 */
export async function commit(writes) {
  if (writes.length === 0) return;
  const root = await documentsRoot();
  const payload = writes.map((write) => {
    const name = `${root}/${write.path}`;
    if (write.delete) return { delete: name };
    const entry = { update: { name, fields: toFields(write.data ?? {}) } };
    // No updateMask means "replace the document", which is what a day doc wants: it is
    // wholly owned by this source, so `set` outright is safe (contract §3).
    if (write.updateMask) entry.updateMask = { fieldPaths: write.updateMask };
    return entry;
  });
  await request(`${root}:commit`, { method: 'POST', body: JSON.stringify({ writes: payload }) });
}

/**
 * Document ids in a collection, paginated.
 *
 * `mask.fieldPaths=source` asks for one short field instead of the whole document, so
 * listing two years of day documents to find which are ours transfers a few kilobytes
 * rather than every per-site breakdown ever written. The ids are all we read; the mask
 * exists purely to keep the response small.
 */
export async function listDocumentIds(collectionPath, pageSize = 300) {
  const root = await documentsRoot();
  const ids = [];
  let pageToken;
  do {
    const query = new URLSearchParams({
      pageSize: String(pageSize),
      'mask.fieldPaths': 'source',
    });
    if (pageToken) query.set('pageToken', pageToken);
    // eslint-disable-next-line no-await-in-loop -- pagination is inherently sequential.
    const body = await request(`${root}/${collectionPath}?${query}`);
    for (const doc of body.documents ?? []) ids.push(doc.name.split('/').pop());
    pageToken = body.nextPageToken;
  } while (pageToken);
  return ids;
}
