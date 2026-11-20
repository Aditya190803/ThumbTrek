// Backup and restore of the full history, as one JSON file. The same format the phone
// writes (app/.../share/Backup.kt), with source "web" and registrable domains as keys.
// Distances are micrometres, which is what this extension stores anyway.

export const BACKUP_FORMAT = 'thumbtrek-backup';
export const BACKUP_VERSION = 1;
export const BACKUP_SOURCE = 'web';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Why a file could not be restored, in words a person can act on. */
export class BackupError extends Error {
  constructor(message) {
    super(message);
    this.name = 'BackupError';
  }
}

export function backupFilename(today) {
  return `thumbtrek-backup-${today}.json`;
}

/**
 * @param rows     `{ date, domain, um }[]`, as `db.allRows()` returns them
 * @param settings the settings object; only the parts worth carrying are kept
 */
export function buildBackup(rows, settings, exportedAt) {
  const byDate = new Map();
  for (const row of rows) {
    if (!(row.um > 0)) continue;
    if (!byDate.has(row.date)) byDate.set(row.date, {});
    byDate.get(row.date)[row.domain] = row.um;
  }
  return {
    format: BACKUP_FORMAT,
    version: BACKUP_VERSION,
    source: BACKUP_SOURCE,
    exportedAt,
    days: [...byDate.keys()].sort().map((date) => ({ date, apps: byDate.get(date) })),
    settings: {
      ...(typeof settings?.dailyLimitM === 'number' ? { limitMeters: settings.dailyLimitM } : {}),
      appLimits: { ...(settings?.siteLimits ?? {}) },
      customApps: { ...(settings?.customSites ?? {}) },
    },
  };
}

/**
 * Reads a backup file. Strict about what it is and lenient about what is in it: a file from
 * somewhere else is refused with a reason, while one odd row in a real backup is skipped
 * instead of costing the user everything else in it.
 */
export function parseBackup(text) {
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new BackupError('That file is not a ThumbTrek backup.');
  }
  if (!json || json.format !== BACKUP_FORMAT) {
    throw new BackupError('That file is not a ThumbTrek backup.');
  }
  if (Number(json.version) > BACKUP_VERSION) {
    throw new BackupError(
      'That backup was made by a newer version of ThumbTrek. Update the extension, then try again.',
    );
  }
  if (json.source !== BACKUP_SOURCE) {
    throw new BackupError(
      'That backup is from the Android app. Restore it from the app, in Settings.',
    );
  }
  const rows = [];
  for (const day of Array.isArray(json.days) ? json.days : []) {
    if (!day || !DATE.test(day.date ?? '') || typeof day.apps !== 'object' || !day.apps) continue;
    for (const [domain, um] of Object.entries(day.apps)) {
      if (domain && Number.isInteger(um) && um > 0) rows.push({ date: day.date, domain, um });
    }
  }
  const positive = (object) => Object.fromEntries(
    Object.entries(object && typeof object === 'object' ? object : {})
      .filter(([, value]) => typeof value === 'number' && value > 0),
  );
  const settings = json.settings ?? {};
  return {
    rows,
    settings: {
      limitMeters: typeof settings.limitMeters === 'number' && settings.limitMeters > 0
        ? settings.limitMeters : null,
      siteLimits: positive(settings.appLimits),
      customSites: Object.fromEntries(
        Object.entries(settings.customApps && typeof settings.customApps === 'object'
          ? settings.customApps : {})
          .filter(([, label]) => typeof label === 'string' && label.trim()),
      ),
    },
  };
}

/** What a restore brought in, for the line the page shows afterwards. */
export function restoreSummary(rows) {
  const dates = [...new Set(rows.map((row) => row.date))].sort();
  return { days: dates.length, from: dates[0] ?? null, to: dates[dates.length - 1] ?? null };
}
