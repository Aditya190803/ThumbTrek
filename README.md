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

## Release signing

Release builds were previously unsigned — only `assembleDebug` produced something
installable, signed with the throwaway debug key. `app/build.gradle.kts` now reads real
credentials from a gitignored `keystore.properties` at the repo root, falling back to
environment variables so CI can supply them. If neither exists nothing breaks: the signing
config is simply not created and a fresh clone still builds debug.

### One-time: create the keystore

Run this once, from the repo root. The result never leaves your machine and is gitignored.

```
keytool -genkeypair -v \
  -keystore thumbtrek-release.jks \
  -storetype PKCS12 \
  -alias thumbtrek \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -dname "CN=ThumbTrek, OU=ThumbTrek, O=ThumbTrek, L=Unknown, ST=Unknown, C=IN"
```

`keytool` asks for a password twice (store, then key — press Enter at the key prompt to
reuse the store password). Then write `keystore.properties` next to it:

```
storeFile=thumbtrek-release.jks
storePassword=<the password you just typed>
keyAlias=thumbtrek
keyPassword=<the same password>
```

Read the certificate fingerprints back out — you need the SHA-1 for Firebase:

```
keytool -list -v -keystore thumbtrek-release.jks -alias thumbtrek
```

**Back the `.jks` up somewhere outside this repo.** Lose it and you can never ship another
update to an installed copy: Android refuses to replace an app with an APK signed by a
different key, so the only recovery is uninstall-and-reinstall, which wipes local history.

### One-time: GitHub Actions secrets

`.github/workflows/release.yml` builds and publishes signed releases. Create these under
**Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the `.jks` (command below) |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `thumbtrek` |
| `KEY_PASSWORD` | the key password |
| `GOOGLE_SERVICES_JSON` | *(optional)* contents of the real `app/google-services.json`, so release builds can sign in to Firebase |

```
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("thumbtrek-release.jks")) | Set-Clipboard

# macOS / Linux
base64 -w0 thumbtrek-release.jks | pbcopy
```

Or with the `gh` CLI, which reads the value from stdin:

```
base64 -w0 thumbtrek-release.jks | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

### R8 and resource shrinking are off, on purpose

`app/proguard-rules.pro` exists and carries keep rules for Room, Firebase/Firestore,
Compose, WorkManager, the accessibility service and the updater — but `isMinifyEnabled`
and `isShrinkResources` are both `false`. Those rules have never been proven by an actual
shrunk build, and on a sideloaded self-updating app a bad R8 config is unrecoverable: the
broken build is also the one that has to ship its own replacement. Flip both flags
together, run `assembleRelease`, and smoke-test tracking, sign-in, the leaderboard, the
widget and one end-to-end self-update before trusting it.

## Moving an existing install to the release key — read first

The copy on your phone right now was installed from a **debug**-signed APK. The first
release-signed build has a different signature, and Android refuses to install it over the
top (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, shown as "App not installed"). Two consequences,
both worth reading twice:

1. **You must uninstall ThumbTrek first, and uninstalling deletes the Room database — all
   local scroll history, streaks and records go with it.** Export before you uninstall:
   **Settings → Export data (CSV)**, and move the file off the device. Anything already
   synced to Firestore (weekly/monthly leaderboard rows) survives, because it lives on the
   server; the day-by-day local history does not.
2. **Google Sign-In will break until you register the new key.** Take the SHA-1 from
   `keytool -list -v` (or from the "Verify the APK is actually signed" step in the release
   workflow log) and add it in Firebase Console → **Project settings → Your apps → SHA
   certificate fingerprints**, then re-download `google-services.json` — the same as step 8
   of the Firebase setup above, but for the release key this time. Keep the debug SHA-1
   registered as well, so debug builds keep working.

Order of operations:

```
1. ThumbTrek → Settings → Export data → save the CSV off-device
2. Add the release SHA-1 in Firebase, re-download google-services.json
3. Uninstall ThumbTrek from the phone
4. Install the release-signed APK
```

**From then on every APK must be signed with that same key** — every self-update, every
hand-built sideload, forever. An APK signed with anything else will not install over it.
That is exactly why the workflow signs from a repository secret rather than from whatever
key happens to sit on the machine doing the build.

## Automatic updates

ThumbTrek is not on the Play Store, so it updates itself. Everything lives in
`app/src/main/java/com/thumbtrek/app/update/` and could be deleted wholesale without
touching anything else.

### How it works

1. `UpdateWorker` (WorkManager, every 6 hours, requires a connection and a not-low
   battery) asks the GitHub Releases API for the newest release of this repo.
2. It reads that release's `update.json` asset — `versionCode`, `versionName`, `apkUrl`,
   `sha256`, size and notes — and compares `versionCode` against the installed build.
3. If newer, and the phone is on **unmetered** Wi-Fi, it downloads the APK into
   app-private storage, hashing as it streams. A SHA-256 mismatch deletes the file, and
   nothing unverified is ever handed to the installer.
4. A quiet notification says the update is downloaded and verified. Tapping it opens
   `UpdateInstallActivity` (invisible) which commits the APK to `PackageInstaller`.
5. Android shows its install confirmation. You tap Install.

Settings also has a manual path: a "check for updates" button, an automatic-check switch,
download progress, release notes, and the one-time permission prompt. The composable is
`update/UpdateUi.kt` — `UpdateSettingsContent()` to drop into an existing settings card,
or `UpdateCard()` standalone — backed by `update/UpdateViewModel.kt`.

The periodic check arms itself on process start via App Startup
(`update/UpdateStartupInitializer.kt`, merged into WorkManager's existing
`InitializationProvider` in the manifest), so it runs whether or not the settings card is
ever opened. `ExistingPeriodicWorkPolicy.KEEP` makes repeat calls free, and WorkManager
keeps the schedule across reboots. Turning the switch off cancels the work; turning it
back on re-enqueues it.

### The honest limit

**Fully silent background updating is not possible for a normal sideloaded app.** Android
reserves that for device-owner and system/privileged installers. What is automated is
everything up to the last tap: noticing, downloading, verifying. The final install is a
system-owned confirmation dialog, and there is no supported way around it.

Two smaller gates sit on top of that:

- **"Install unknown apps"** — an API 26+ per-app grant. The updater asks for it once from
  Settings and deep-links straight to the right screen; after that Android remembers.
- On **Android 12+** an app holding `UPDATE_PACKAGES_WITHOUT_USER_ACTION` may request no
  confirmation when updating *itself*. The updater asks. The system often refuses anyway —
  most relevantly when the installed copy was put there by hand rather than by ThumbTrek —
  so the first self-update after a manual sideload will still prompt, and later ones may
  not. Nothing depends on this working.

### Privacy

The update check talks to `api.github.com` and `github.com`, and nowhere else. It is an
anonymous GET: no account, no token, no device identifier, no scroll data, no analytics.
GitHub sees an IP address and a `ThumbTrek/<version>` User-Agent — the same as opening the
releases page in a browser. Unauthenticated GitHub API calls are rate-limited to 60/hour
per IP, comfortably above a six-hourly check.

### Cutting a release

```
# 1. Bump both in app/build.gradle.kts — the updater compares versionCode:
#      versionCode = 3
#      versionName = "0.2.0"
# 2. Commit, then tag. The annotation message becomes the in-app release notes.
git tag -a v0.2.0 -m "Fixes X, adds Y"
git push origin v0.2.0
```

The workflow builds the signed APK, verifies it really is signed (and prints the
certificate SHA-1), generates `update.json`, and publishes both as release assets. A
`versionCode` that is not higher than the installed one is invisible to the updater.
Running the workflow from **Actions → Release → Run workflow** does the same build and
uploads the artifacts without publishing, which is how to test the signing setup before
cutting a real release.
