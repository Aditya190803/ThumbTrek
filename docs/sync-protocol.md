# ThumbTrek sync protocol

The contract every client implements: the Android app, the browser extension, and the web
dashboard. Read this before touching any of them — the three only interoperate because they
agree on the unit, the document layout, and who is allowed to write what.

---

## 1. The unit is micrometres, not pixels

Pixels are not comparable across devices. `pixelsToMeters` divides by `densityDpi`, so a
560 dpi phone logs 1.75× the pixels of a 320 dpi phone for the *same physical thumb travel*.
The board ranks on raw pixels today, which means it has always quietly favoured dense
screens. A browser, whose CSS pixel has no relationship to either, would make it nonsense.

So **everything that crosses the network is an integer count of micrometres (µm)**, named
with a `Um` suffix. Pixels stay local to whichever client measured them.

| Client    | Conversion to µm                       | Why                                                        |
|-----------|----------------------------------------|------------------------------------------------------------|
| Android   | `round(pixels / densityDpi * 25400)`   | `densityDpi` is the real physical density.                  |
| Browser   | `round(cssPixels / 96 * 25400)`        | A CSS pixel is *defined* as 1/96 in (CSS Values §5.2).      |

Integers, not floats: Firestore compares and sorts them exactly, and µm keeps a full metre
at 1e6 — far below any precision limit. Never send a float distance.

### Back-compat

The existing `weekPixels` / `monthPixels` / `totalPixels` fields stay on the board document
and the Android app keeps writing them. They are legacy: nothing new reads them for ranking.
They exist so a phone running an old build still appears on the board instead of vanishing.
Rank on `*Um` when present, fall back to `*Pixels` only for rows that have no `*Um` at all.

---

## 2. Sources

A `source` identifies which kind of client produced a measurement. It is a short lowercase
slug, and it never contains a `.` (it is used inside Firestore dotted field paths).

| Source    | Written by                |
|-----------|---------------------------|
| `android` | The Android app           |
| `web`     | The browser extension     |

A user may run both. Each source owns its own numbers and **must never write another
source's**. That single rule is what makes concurrent clients safe.

---

## 3. Firestore layout

### `users/{uid}` — the public board row

Readable by any signed-in user; that *is* the global leaderboard. It holds distances and a
name the owner chose, nothing else. Existing fields keep their meaning.

```
users/{uid} = {
  displayName, photoUrl, friendCode,          // unchanged
  weekKey, weekPixels,                        // legacy, Android only
  monthKey, monthPixels,                      // legacy, Android only
  totalPixels,                                // legacy, Android only

  weekUm, monthUm, totalUm,                   // combined across sources — what ranks
  sources: {                                  // per-source split
    android: { weekKey, weekUm, monthKey, monthUm, totalUm, updatedAt },
    web:     { weekKey, weekUm, monthKey, monthUm, totalUm, updatedAt },
  },
}
```

`weekKey` / `monthKey` still gate the weekly and monthly boards: a row whose `weekKey` is
not the current one reads as zero for that period rather than dropping off.

**Every source carries its own `weekKey` / `monthKey`, and this is load-bearing.** The
row-level key cannot do the job once a row has several writers: if the extension last synced
in `2026-W36` and the phone syncs in `2026-W37`, the phone stamps the row with W37 while
`sources.web.weekUm` still holds last week's distance. Summing blindly inflates the combined
weekly figure until that client happens to sync again — a leaderboard that pays out for
stopping. So when combining, **a source contributes its `weekUm` only if its own `weekKey`
equals the current week**, and zero otherwise; likewise for the month. This is the same rule
`toEntry()` already applies at row level, pushed down one layer.

**Writing it.** Every client knows its own complete history locally, so a sync is:

1. `update` (merge) `sources.<me>` with its own three totals plus `updatedAt`. Only its own
   key — the dotted path `sources.android.weekUm` leaves `sources.web` untouched.
2. Re-read the document, sum every source's `weekUm` / `monthUm` / `totalUm`, and write the
   three combined fields together with `weekKey` / `monthKey`.

Step 2 racing another client is harmless: the loser's combined figure is briefly stale and
the next sync from either side corrects it. The per-source numbers, which are the real
record, are never wrong.

> **The first write of a row must be complete.** `validScore()` asserts `displayName`,
> `photoUrl`, `friendCode`, `weekKey` and `monthKey` are all strings, and under a merging
> write the rule sees the *post-merge* document. So a merge carrying only `sources.<me>`
> succeeds against an existing row and is rejected against a missing one. Every client must
> therefore write the full identity row on its first sync (or whenever the row may have been
> deleted by an opt-out elsewhere) rather than assuming another client created it. Treat
> "permission denied on a `sources`-only merge" as "the row is gone — write it whole".

### `users/{uid}/days/{date}__{source}` — the private day ledger

One document per day per source. This is what the web dashboard charts, and what makes
history survive a reinstall. It is **private** — only the owner reads it, so the board keeps
exposing nothing but three numbers and a name.

```
users/{uid}/days/2026-09-06__android = {
  date:   "2026-09-06",       // yyyy-MM-dd, the client's local timezone
  source: "android",          // must match the doc-id suffix
  um:     1840000,            // that source's total for that day
  apps: {                     // per-app / per-site breakdown, µm
    "com.instagram.android": 900000,
    "youtube.com":           940000,
  },
  updatedAt,
}
```

The doc id embeds the source so each document is wholly owned by one writer and can be
`set` outright. No two clients ever touch the same document, so there are no partial-update
races and no dotted-field-path escaping for app keys that contain dots.

`apps` keys are the natural identifier for the source: an Android package name
(`com.instagram.android`) or a registrable domain (`youtube.com`). At most 64 keys per doc.

Clients only need to push days they changed. Backfill on first sign-in is a `set` per day.

### Unchanged collections

`users/{uid}/friends/{friendUid}`, `friendCodes/{code}`, and `archive/{weekKey}/scores/{uid}`
keep their current shape and semantics, with one addition: friend edges gain optional
`name` (≤64) and `photo` (≤512) carrying the WRITER's real Google identity. Edges are
readable only by their owner, so this is how friends see each other's real names while
the public board row stays anonymous — the global handle never leaks through this
channel. Old edges without them read through unchanged. The archive stores `pixels`; it
gains an optional `um` field written alongside, ranked on when present.

---

## 4. Identity

One Google account, one `uid`, one friend code — shared by every client. The derivations in
`social/Identity.kt` are the spec and must be reimplemented exactly:

- `friendCode(uid)` = first 8 bytes of `SHA-256("thumbtrek:code:" + uid)`, each masked
  `& 0x1F` and indexed into the Crockford alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ`.
- `anonymousHandle(uid)` = `SHA-256("thumbtrek:handle:" + uid)`, then
  `ADJ[b0 % 16] + " " + NOUN[b1 % 10] + " #" + ((b2<<8 | b3) % 10000)`, zero-padded to 4.

A client that computes either differently splits the user's identity in half. There are unit
tests for both; port them.

---

## 5. Offline and opt-in

Both rules from the app carry over to every client, unchanged:

- **Offline-first.** Tracking, history, streaks, records and badges all work with no account.
  Local storage is the source of truth; the network is a mirror. Signing in is only ever
  required to sync across devices and to see the leaderboard.
- **Opt-in.** Nothing is written to Firestore until the user turns the leaderboard on.
  Signing in by itself publishes no score.

### Opting out with several clients

Opting out on one device must withdraw *that device's* data without destroying another's.
A client turning the leaderboard off:

1. Deletes its own `{date}__{source}` day documents. Deleting the parent document does **not**
   remove a subcollection in Firestore, so this has to be explicit or the private ledger is
   orphaned and unreachable.
2. Then, only if it is the last remaining writer, deletes `users/{uid}`. Otherwise it removes
   just its own `sources.<me>` entry, drops any legacy fields it owns, and re-rolls the
   combined totals from the sources that are left.

Deleting the whole row unconditionally — which is what the single-writer app used to do —
would wipe out a still-active extension's score, which is exactly the clobber this protocol
exists to prevent. Also clear the local "already pushed" marker, so a later re-opt-in
backfills history rather than resuming from a cursor the server no longer agrees with.

---

## 6. Auth per client

| Client     | Mechanism                                                                              |
|------------|----------------------------------------------------------------------------------------|
| Android    | Credential Manager → Google ID token → `signInWithCredential`. Already implemented.      |
| Web        | Firebase JS SDK, `signInWithPopup(GoogleAuthProvider)`.                                  |
| Extension  | `identity.launchWebAuthFlow` → Google ID token → Firebase Auth REST `accounts:signInWithIdp` → Firestore REST. |

The extension deliberately uses the REST APIs rather than the Firebase JS SDK: the SDK's
popup sign-in cannot run in an MV3 service worker, and REST keeps the extension dependency-
free and buildless. Refresh the ID token with the `securetoken.googleapis.com` endpoint; it
expires after an hour and a background sync will outlive it.
