# ThumbTrek

Strava for scrolling. Tracks how far your thumb travels in Instagram, YouTube, X and Reddit,
turns it into trek stats, streaks, charts, shareable cards, and weekly friend/global leaderboards.

Version **0.0.1** is the first public Android release.

## What ships

- Per-app opt-in tracking for Instagram, YouTube, X, and Reddit
- Dashboard ring chart, seven-day history, per-app trends, streaks, and records
- Fully opt-in social publishing, friend codes/invites, anonymous handles, and friend/global boards
- Daily summaries, optional streak reminders, and PNG stat-card sharing
- Local-first Room storage; scroll data only reaches Firebase after leaderboard opt-in

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
7. Google Sign-In needs your signing key's SHA-1 registered in
   **Project settings → Your apps → SHA certificate fingerprints**:
   ```
   ./gradlew signingReport    # copy the debug SHA-1
   ```
   Re-download `google-services.json` after adding it (it now includes the Android OAuth client).

## How tracking works

An `AccessibilityService` listens only for `TYPE_VIEW_SCROLLED` events from the four tracked
apps (the system filters by package name, so nothing else wakes it). Pixel deltas are batched
in memory and upserted into Room every few seconds as one row per app per day. Meters are
computed at display time: `pixels / densityDpi * 0.0254`.

No screen content is ever read — the service config requests no node-lookup flags.

## Data model

- **Local (Room)**: `daily_scroll(packageName, date, pixels)`
- **Cloud (Firestore)**: `users/{uid}` stores the opted-in weekly score and public identity;
  `users/{uid}/friends/{friendUid}` stores friendship edges; `friendCodes/{code}` resolves invites.
  The weekly reset is just `weekKey` (ISO week, e.g. `2026-W31`) changing — old docs stop
  matching the leaderboard query. Scores sync when the Social tab is opened/refreshed.
