/**
 * CSV export, matching `share/DataExporter.kt`.
 *
 * Same shape: a header line, then one line per site per day, oldest first, with the label
 * quoted and internal quotes doubled. The Kotlin's own comment says labels "never contain
 * quotes or commas ... but be strict anyway rather than trusting that forever", and a
 * user-typed site label here really can contain both, so the escaping earns its keep.
 *
 * ## The one deliberate difference: the third column is `um`, not `pixels`
 *
 * Android's export writes `date,app,pixels` because raw pixels are what its tracker
 * accumulates. This client has no pixels to export -- contract §1 is explicit that a CSS
 * pixel is not comparable to a device pixel and that "pixels stay local to whichever client
 * measured them". Emitting µm under a column called `pixels` would produce a file that
 * looks mergeable with the phone's and silently is not: the same header, the same shape,
 * numbers 265x apart.
 *
 * So the row shape is identical and the header is honest. Anyone joining the two exports
 * sees the mismatch in the header, which is exactly where they should see it.
 */

import { siteName } from './domain.js';

const escape = (text) => `"${String(text).replace(/"/g, '""')}"`;

/** @param rows `{ date, domain, um }`, and `labels` a Map of user-supplied site names. */
export function toCsv(rows, labels = new Map()) {
  const lines = ['date,site,um'];
  for (const row of rows) {
    lines.push(`${row.date},${escape(siteName(row.domain, labels))},${row.um}`);
  }
  // Trailing newline, like the Kotlin's per-row append leaves.
  return `${lines.join('\n')}\n`;
}

export const CSV_FILENAME = 'thumbtrek_export.csv';
