# ThumbTrek site (Vercel)

Static marketing site plus the signed-in dashboard. No bundler, no `package.json`, no build
step: plain ES modules, the Firebase JS SDK loaded from `https://www.gstatic.com/firebasejs/`
at a pinned version, and files served exactly as they sit in this folder.

```
web/
  index.html            the marketing page
  styles.css            the design system: tokens, buttons, sections, the contour panel
  assets/mark.svg
  vercel.json           cleanUrls, headers, and the /i/<code> rewrite
  app/
    index.html          the dashboard          → /app
    invite.html         the invite page        → /app/invite, and /i/<code> via a rewrite
    app.css             dashboard styles, built on styles.css tokens
    firebase-config.example.js   copy to firebase-config.js (see below)
    firebase.js         SDK loading, config discovery, auth, the offline cache
    identity.js         friend codes and anonymous handles (contract §4)
    stats.js            streaks, buckets, landmarks, badges (port of stats/Stats.kt)
    board.js            leaderboard scoring, the µm/pixels fallback, the per-source split
    data.js             every Firestore read
    friends.js          the two-edge friendship protocol — the only writes on the site
    charts.js           inline-SVG charts with a real data table behind them
    dom.js              element helpers
    main.js             the dashboard controller
    invite.js           the invite-page controller
    test/               node --test, no dependencies
```

## Routes

| URL             | File                 | Notes                                                   |
|-----------------|----------------------|---------------------------------------------------------|
| `/`             | `index.html`         | marketing page                                           |
| `/app`          | `app/index.html`     | dashboard; signed-out visitors get a real page, not a redirect |
| `/app/invite`   | `app/invite.html`    | invite page, reachable directly with `?code=…`           |
| `/i/<code>`     | → `/app/invite`      | rewrite, so the pretty URL stays in the address bar      |

`/i/<code>` is the link `social/Identity.kt` has always put on the phone's share sheet, so
those URLs are already out in the world. The rewrite in `vercel.json` passes the capture
through as `?code=`, and the page also reads it straight off the path.

## Deploy on Vercel

1. Import the GitHub repo in [Vercel](https://vercel.com/new).
2. Set **Root Directory** to `web`.
3. Framework preset: **Other** (no build command).
4. Output directory: `.` (or leave blank — `vercel.json` already points at this folder).
5. Deploy.

Or from this folder:

```bash
npx vercel --yes
```

## Firebase setup

The dashboard needs a Firebase **Web App** registered in the `thumb-trek` project. There
isn't one yet, so `web/app/firebase-config.js` does not exist in this repo and the dashboard
shows a setup panel instead of a broken sign-in button. Three things to do, once.

### 1. Register a Web App and copy the config

Firebase console → **Project settings** (gear, top left) → **General** → *Your apps* →
**Add app** → the `</>` (Web) icon. Give it a nickname (`ThumbTrek web`); Firebase Hosting is
**not** needed, the site lives on Vercel. The next screen shows a `firebaseConfig` object.

Copy `web/app/firebase-config.example.js` to `web/app/firebase-config.js` and fill it in:

| Field               | Where it comes from                                                        |
|---------------------|-----------------------------------------------------------------------------|
| `apiKey`            | Project settings → General → *Your apps* → the Web app's SDK snippet         |
| `authDomain`        | same snippet — `thumb-trek.firebaseapp.com`                                  |
| `projectId`         | Project settings → General → *Project ID* (`thumb-trek`)                     |
| `storageBucket`     | same snippet (unused by this page, kept so the object matches the console)   |
| `messagingSenderId` | Project settings → Cloud Messaging → *Sender ID*, also in the snippet        |
| `appId`             | the snippet only — it is created by registering the Web app, and until then it does not exist |

**Commit the filled-in file.** Firebase web config values are public by design: they are
shipped inside every web client that loads the page, and Google's docs say so plainly. They
are an address, not a credential. This is the difference from `app/google-services.json`,
which is gitignored because it also carries OAuth client identifiers bound to the release
signing key. What actually protects the data is `firestore.rules` plus the authorised-domain
list below; an `apiKey` on its own opens nothing.

There is deliberately no secret-injection step and no environment variable for this. A build
step to hide a public value would be complexity bought with nothing.

### 2. Authorise the domain, or Google sign-in refuses to run

Firebase console → **Authentication** → **Settings** → **Authorized domains** → **Add
domain**. Add every host the dashboard is served from:

- the production domain (`thumbtrek.adityamer.dev`),
- the Vercel project domain (`<project>.vercel.app`),
- `localhost` is authorised by default, which covers local testing.

Vercel's per-deployment preview URLs (`<project>-<hash>-<scope>.vercel.app`) each have their
own hostname and are *not* covered by the project domain, so sign-in fails on a preview
deployment unless that exact host is added too. Test auth on the production domain, or add
the preview host you care about. Without this, `signInWithPopup` fails with
`auth/unauthorized-domain`, which the page reports in those words.

Also make sure **Authentication → Sign-in method → Google** is enabled. The Android app uses
the same provider, so it already is if the phone can sign in.

### 3. Deploy the rules and indexes

```bash
firebase deploy --only firestore:rules,firestore:indexes
```

The dashboard's global week and month boards need the composite indexes in
`firestore.indexes.json` (`weekKey ASC, weekUm DESC` and `monthKey ASC, monthUm DESC`).
Without them those queries fail with `failed-precondition`; the page says so and points at
the console link in the browser console, which offers to create the index for you.

### 4. Invite links open the app (already done — verify after deploy)

`web/.well-known/assetlinks.json` carries the release certificate's SHA-256, so
`https://thumbtrek.adityamer.dev/.well-known/assetlinks.json` verifies the `https://thumbtrek.adityamer.dev/i/`
app link declared in `AndroidManifest.xml` (`autoVerify`). Confirm it after deploying:
the file must load with `Content-Type: application/json` and no redirect. Until the
production domain serves it, that filter stays inert — which is fine, because the invite
page also fires a `thumbtrek://i/<code>` custom-scheme link that needs no server at all.
If the keystore is ever replaced, re-run `keytool -list -v` and update the fingerprint;
`assetlinks.example.json` next to it is the blank template.

### A note on response headers

No `Content-Security-Policy` is set. Firebase Auth's popup flow loads
`apis.google.com`, navigates to `accounts.google.com`, and talks to
`identitytoolkit.googleapis.com`, `securetoken.googleapis.com` and
`firestore.googleapis.com`, and a CSP that gets any one of those wrong breaks sign-in
silently. Writing one that cannot be verified against a live project would be worse than not
having it. When a real Firebase Web App exists, add one and test the whole sign-in round trip
against it before shipping.

For the same reason `Cross-Origin-Opener-Policy` is left unset: `same-origin` severs the
opener relationship that `signInWithPopup` uses to detect the popup closing.

## Tests

Plain `node --test`, no dependencies, no config:

```bash
node --test web/app/test/stats.test.js web/app/test/identity.test.js web/app/test/board.test.js
```

They cover the ported pure logic — streaks, the landmark comparison, the daily/weekly/monthly
buckets, friend-code normalisation and the `*Um` / `*Pixels` ranking fallback — and they
assert the same values the Kotlin unit tests assert, because the point of the port is that
the phone and the browser agree. The identity test recomputes the spec's derivation through
`node:crypto` rather than trusting `identity.js` to check itself.

`node --check <file>` parses any single file. Node 22.7+ detects ES module syntax in a `.js`
file without a `package.json`, which is why this folder can be both buildless and testable.

## What the dashboard does and does not do

It is **read-only over distance**. It never writes a score, a day document or a board row —
`docs/sync-protocol.md` §5 says nothing reaches Firestore until the user opts in, and §3 says
a client may only write its own source. This page measures nothing, so it owns no source and
has nothing to publish. Opting in, opting out and the anonymous-handle switch all stay in the
app, which is also the only client that can safely run the multi-client opt-out sequence.

The one thing it writes is the friendship graph: the two pending/accepted edges, and the
`friendCodes/{code}` index entry — and the index entry only once a board row already exists,
because that row *is* the opt-in.

Distances are rendered from `users/{uid}/days/{date}__{source}`, which is the only place
day-by-day history exists; the board row holds three running totals and no past. Leaderboard
rows that carry no `*Um` at all are ranked through a stated nominal density (see `board.js`)
but are shown with a dash rather than a distance: no browser knows the density of the phone
that logged those pixels, and a number derived from a guess would be a distance the dashboard
invented.
