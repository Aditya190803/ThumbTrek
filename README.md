# ThumbTrek

Strava for scrolling. Tracks how far your thumb travels in Instagram, YouTube, X and Reddit,
turns it into trek stats, streaks, charts, shareable cards, and weekly friend/global leaderboards.

Version **0.1.0**.

## What ships

- Per-app opt-in tracking for Instagram, YouTube, X, and Reddit — plus any other installed app you add in Settings
- Dashboard ring chart, seven-day history, per-app trends, streaks, records, and achievement badges
- Per-app scroll calibration (a multiplier for apps that report pixels oddly)
- Fully opt-in social publishing, friend codes/invites with a request→accept flow, anonymous handles
- Weekly, monthly, and all-time boards; friends and global tabs; server-paginated global board; last week's podium
- Friend management: accept/decline requests, remove friends, local nudges for new requests and rank slips
- Daily summaries, optional streak reminders, PNG stat-card sharing, and full CSV data export
- Home-screen widget showing today's trek, week total, and streak
- Local-first Room storage; scroll data only reaches Firebase after leaderboard opt-in
- Redesigned UI: custom display/body type, a flat bordered card system instead of Material's
  default shadowed cards, and a new checkpoint-ring app icon
- Denser landmark comparisons and more achievement badges (21 of each), so long stretches no
  longer read as a large multiple of the same object
- Fixed scroll tracking on X and YouTube, whose Compose-based feeds weren't populating the
  accessibility event field the tracker relied on

## Build

Requires JDK 17+ and the Android SDK (platform 35). Then:

```
./gradlew assembleDebug testDebugUnitTest
```

## Firebase setup (required for the Social tab)

`app/google-services.json` in this repo is a **placeholder** — the app compiles with it,
but sign-in will fail until you swap in a real one:

1. [Firebase Console](https://console.firebase.google.com) → create a project (any name).
2. Add an Android app with package name `com.thumbtrek.app`.
3. Download the generated `google-services.json` and replace `app/google-services.json`.
4. **Authentication → Sign-in method → enable Google.**
5. **Firestore Database → Create database** (production mode, any region).
6. **Firestore → Rules**: paste the contents of [`firestore.rules`](firestore.rules) and publish.
7. **Firestore → Indexes**: create these two composite indexes (or click the link in the
   first leaderboard error — Firestore will offer to build them):
   - `users`: `weekKey` Ascending, `weekPixels` Descending
   - `users`: `monthKey` Ascending, `monthPixels` Descending
8. Google Sign-In needs your signing key's SHA-1 registered in
   **Project settings → Your apps → SHA certificate fingerprints**:
   ```
   ./gradlew signingReport    # copy the debug SHA-1
   ```
   Re-download `google-services.json` after adding it (it now includes the Android OAuth client).

## How tracking works

An `AccessibilityService` listens only for `TYPE_VIEW_SCROLLED` events and checks each one
against your tracked-app set (an in-memory read) before counting a pixel. The four built-in
feeds are on by default; Settings lets you add any other installed app or switch any of them
off. Pixel deltas are batched in memory, multiplied by each app's calibration factor, and
upserted into Room every few seconds as one row per app per day. Meters are computed at
display time: `pixels / densityDpi * 0.0254`.

No screen content is ever read — the service config requests no node-lookup flags.

## How the social layer works

- Adding by friend code writes two *pending* edges; accepting flips both to *accepted*.
- Edges written before requests existed have no status field and read as accepted, so no
  migration was needed.
- The weekly/monthly "reset" needs no server job: when the stored `weekKey`/`monthKey`
  rolls over, old docs simply stop matching the query.
- Each sync also freezes a copy of your weekly row into `archive/{weekKey}/scores`, which
  is where last week's podium comes from.
- Social notifications are computed locally at refresh time (new request, rank slip) — no
  push infrastructure.

## Data model

- **Local (Room)**: `daily_scroll(packageName, date, pixels)`
- **Cloud (Firestore)**: `users/{uid}` stores the opted-in weekly score and public identity;
  `users/{uid}/friends/{friendUid}` stores friendship edges; `friendCodes/{code}` resolves invites.
  The weekly reset is just `weekKey` (ISO week, e.g. `2026-W31`) changing — old docs stop
  matching the leaderboard query. Scores sync when the Social tab is opened/refreshed.
