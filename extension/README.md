# ThumbTrek — browser extension

The browser companion to the Android app. It measures how far your thumb (or your wheel, or
your trackpad) travels through a feed, converts it to metres, and shows the same streaks,
records and badges the phone does — from the same numbers, in the same words.

**Manifest V3. No build step, no npm, no dependencies.** What is in this directory is what
the browser loads.

---

## Install it

1. Open `chrome://extensions` (or `edge://extensions`, `brave://extensions`).
2. Turn on **Developer mode**.
3. **Load unpacked** → pick this `extension/` directory.

That is the whole install. Measurement, history, streaks, records, badges, the popup, the
site list and CSV export all work immediately, with no account and no network — permanently,
not as a trial. Sync is the only thing that needs the configuration below.

---

## What it measures, and how

Every scroll on a page is a distance in CSS pixels, and a CSS pixel is *defined* as 1/96 of
an inch (CSS Values §5.2). That single fact is what makes a browser comparable with a phone
at all: `round(cssPixels / 96 * 25400)` gives micrometres, which is the unit everything
crossing the network uses (`docs/sync-protocol.md` §1). Raw pixels never leave this client,
because a 560dpi phone's pixel and a CSS pixel are not the same length.

Four things in `content/measure-core.js` are less obvious than they look:

- **Sub-element scrolling.** A `window.scrollY` listener measures *nothing* on YouTube or X,
  where the feed lives in an inner scroll container and the document never moves. `scroll`
  does not bubble from elements but it does capture, so one capture-phase listener on
  `window` sees the page and every inner container, present and future.
- **The first sample of any scroller is a baseline, never a distance.** Otherwise a restored
  scroll position banks the whole scroll height of the feed you have not scrolled yet.
- **A jump over ~3 viewport heights is dropped, not clamped.** That is an anchor link, a
  "back to top", a history restore, or a virtualised list re-seating itself. Clamping would
  still bank three screens the thumb never travelled. The baseline is updated either way, so
  the next real scroll measures from where the page actually is.
- **Batching.** Pixels are accumulated locally and flushed every five seconds, plus on
  `visibilitychange` and `pagehide`. Never a message per scroll event.
- **YouTube Shorts is a pager, not a scroller.** `/shorts/<id>` never moves any scroll
  container — the video swaps through history navigation — so the scroll listener sees
  nothing and a Shorts session would read as zero. Each *new* Short banks one viewport
  height through the accumulator's `add()`, detected via history patching plus
  wheel/touch/key/popstate listeners (counting URL changes, not wheel deltas, so a bounce
  that changes nothing banks nothing). The web equivalent of Android's `PAGED` path.

Distance is filed under the **registrable domain** — `youtube.com`, not `www.youtube.com` —
so one site is one row. `twitter.com` folds into `x.com`.

### Storage

IndexedDB, keyed by `(date, domain)`, accumulating micrometres. It mirrors the phone's Room
`daily_scroll` table and it is the source of truth: the network is a mirror of it, never the
other way round. `chrome.storage.local` holds settings and auth tokens only.

---

## Configuring sync (optional)

Sync is off until you do this, and the options page says so in plain words rather than
failing quietly.

### 1. `config.js`

```sh
cp config.example.js config.js
```

Then fill in four values. `config.js` is gitignored — not because these are secrets (a
Firebase web API key is a public identifier; the security boundary is `firestore.rules`) but
because a committed copy would be silently stale for the next checkout.

| Value | Where it comes from |
|---|---|
| `projectId` | Firebase console → **Project settings** → General → "Project ID". For this project: `thumb-trek`. |
| `apiKey` | Firebase console → **Project settings** → General → **Your apps** → the Web app → SDK setup and configuration → `apiKey`. |
| `appId` | Same screen, `appId`. Looks like `1:1234567890:web:abcdef0123456789`. |
| `oauthClientId` | Google Cloud console → **APIs & Services** → **Credentials** → OAuth 2.0 Client IDs. See below. |

> **No Firebase Web App exists in the `thumb-trek` project yet.** `apiKey` and `appId` are
> minted when one is registered — the **`</>`** button on that Project settings screen. Until
> then those two values genuinely do not exist and cannot be guessed.

### 2. The extension id is pinned — register this redirect URI once

`launchWebAuthFlow` redirects to `https://<extension-id>.chromiumapp.org/`, and that URI has
to be registered against the OAuth client *before* sign-in will work. An unpacked extension's
id is normally derived from its path, so it would change on every machine — which is why
this repo pins it: `manifest.json` carries a committed `key` (public half of
`thumbtrek-extension.pem`, which lives next to the release keystore, gitignored, backed up
the same way). Every checkout of this repo loads as the same extension:

```
ID:            hnpbmamhbbhafanpjjedajgddbholoed
Redirect URI:  https://hnpbmamhbbhafanpjjedajgddbholoed.chromiumapp.org/
```

Register that URI once (step 3) and every install — yours, a tester's, any machine — signs
in with zero per-machine setup. (The Chrome Web Store will assign a *different*, permanent
id at publish time; that URI gets added alongside, same step, and the pinned key is what
keeps pre-store testers stable until then.)

If the private key is ever lost, generate a new one the same way and update the manifest:

```sh
# New private key (gitignored — back it up with the release keystore).
openssl genrsa -out thumbtrek-extension.pem 2048
# The public half goes in manifest.json as "key".
openssl rsa -in thumbtrek-extension.pem -pubout -outform DER | openssl base64 -A
```

A new key means a new id, so the redirect URI below has to be re-registered too.

### 3. The OAuth client

Google Cloud console → **APIs & Services** → **Credentials** → **Create credentials** →
**OAuth client ID**.

Pick **Web application** (not "Chrome Extension"), and under **Authorised redirect URIs** add:

```
https://<extension-id>.chromiumapp.org/
```

> **On the "Chrome Extension" client type.** That type exists for
> `chrome.identity.getAuthToken`, which is Chrome-only, requires an `oauth2` block in the
> manifest, and returns an *access* token — not the ID token Firebase's
> `accounts:signInWithIdp` needs, so it would mean an extra exchange. `launchWebAuthFlow`
> works on Firefox too and returns an `id_token` directly, and it authenticates against a
> **Web application** client keyed by the redirect URI. If you create a Chrome Extension
> client instead, sign-in fails with `redirect_uri_mismatch`.

Finally, in Firebase console → **Authentication** → **Sign-in method**, make sure **Google**
is enabled and that this client id is listed among its authorised clients.

### 4. Firestore

`firestore.rules` and `firestore.indexes.json` at the repo root are already what the server
needs; deploy them if they are not live yet. The extension only writes:

- `users/{uid}/days/{date}__web` — its own day documents, and
- `users/{uid}.sources.web` plus the recomputed combined totals.

It never writes `sources.android`, another source's day documents, or the legacy `*Pixels`
fields. That single rule is what makes a phone and a browser syncing at the same second safe.

### Why REST and not the Firebase JS SDK

`docs/sync-protocol.md` §6: the SDK's popup sign-in cannot run in an MV3 service worker (no
`window`, no opener, no DOM), and the SDK is ~200KB of bundled JavaScript that would need a
build step this extension does not have — to wrap an HTTP API with four calls in it. The
ID token expires after an hour and a background sync on a 30-minute alarm outlives it, so
every request refreshes through `securetoken.googleapis.com` on the way in.

---

## Firefox

Everything here works on Firefox with **one manifest change**, deliberately not shipped as a
second manifest file:

```json
"background": { "scripts": ["background/service-worker.js"], "type": "module" }
```

Firefox implements MV3 background as an event page, not a service worker. Chrome rejects
`background.scripts` in MV3 and Firefox (before 121) rejects `background.service_worker`, so
there is no single spelling that satisfies both; the code itself is identical either way.

You will also need `browser_specific_settings.gecko.id`, and Firefox's
`identity.getRedirectURL()` returns a `*.extensions.allizom.org` URL rather than
`*.chromiumapp.org` — so that redirect URI has to be added to the OAuth client too.

The `browser`/`chrome` namespace difference is already handled, in `lib/browser.js`.

---

## Tests

```sh
node --test "test/*.test.js"
```

94 tests, no dependencies, node's built-in runner. They cover the pure logic: the µm
conversion and Java-compatible rounding, the streak and bucket and comparison functions
(expectations lifted from `app/src/test/.../StatsTest.kt`), the friend code and anonymous
handle (checked against an independent oracle, not against themselves), domain attribution,
the day-document rollup and its 64-key cap, the combine rule and its stale-key gating, and
the scroll accumulator — loaded into a `node:vm` exactly as the browser loads it, so what is
tested is the file that ships.

`test/rules.test.js` transcribes `validDay()` and `validScore()` from `firestore.rules` and
runs the real write payloads through them, because a rules rejection arrives over REST as a
bare `PERMISSION_DENIED` that looks identical to being signed out.

### Regenerating the icons

```sh
node tools/make-icons.mjs
```

Chrome will not accept an SVG for `icons`, so the mark is rasterised from the same geometry
as `web/assets/mark.svg` rather than committed as four hand-made binaries that can drift
from it.

---

## Privacy

- Nothing leaves the device until you turn the leaderboard on. Signing in by itself publishes
  nothing (`docs/sync-protocol.md` §5).
- What is published is three distances and a name you chose. Your per-site, per-day history
  goes to a subcollection only you can read.
- Turning the leaderboard off deletes this browser's day documents and its `sources.web`
  entry, and re-rolls the combined totals without it — the phone's row survives.
- The extension reads no page content. It reads one number per scroll event (a scroll
  offset) and the hostname of the tab. Untracked sites are discarded in the service worker
  and never stored or sent.
- The two fonts are bundled, so opening the popup does not tell a CDN that you did.

## Known limits

- **Registrable domains are resolved with a short suffix table, not the Public Suffix List.**
  A site under an unlisted two-level suffix is filed one label short. See the reasoning in
  `lib/domain.js`; nothing about measurement accuracy depends on it, only the label.
- **Browser zoom is ignored.** A CSS pixel is 1/96in at 100%; at 150% the extension
  undercounts rather than inventing distance.
- **Horizontal scrolling is worth zero**, matching Android's `ScrollPath.HORIZONTAL`. Feeds
  are vertical, and counting carousels would let a story reel outrank a day of real
  scrolling.
- **CSV's third column is `um`, not `pixels`.** The row shape matches the phone's export
  exactly; the header does not, because a µm count under a column called `pixels` produces a
  file that looks mergeable with the phone's and is off by a factor of 265.
