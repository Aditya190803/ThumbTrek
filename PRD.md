# ThumbTrek — Product Requirements Document

**Version:** 0.0.1 (Shipped)
**Owner:** Slowpoke  
**Last updated:** August 13, 2026

---

## 1. Summary

ThumbTrek is a Strava-style Android app for scrolling. It tracks how far your thumb travels (in meters/km) across Instagram, YouTube, X, and Reddit, converts that into a "trek" — fun real-world distance comparisons — and gives users streaks, leaderboards, and daily/weekly stats. It turns a mindless habit into a trackable journey.

---

## 2. Problem Statement

People scroll for hours a day without any sense of the physical/attention cost. Existing screen-time tools show *time*, not *distance* or *effort*, and aren't fun or shareable. ThumbTrek reframes scrolling as an "activity" — like a run or a ride — making the habit visible, gamified, and social.

---

## 3. Goals

- Accurately track scroll distance per app, per day
- Convert distance into meaningful, fun comparisons
- Give users a reason to check the app daily (streaks, stats, leaderboards)
- Ship an MVP fast on Android, with clear path to iOS later

### Non-Goals (v1)

- No content moderation, blocking, or "digital wellbeing" nagging
- No iOS support in v1 (technical constraints — see Risks)
- No in-app scrolling of its own content (it's a passive tracker, not a feed)

---

## 4. Target Users

- Gen Z / young millennial social media users who enjoy gamified self-tracking (Strava, Duolingo, Snapchat streaks audience)
- People who scroll a lot and find self-aware humor about it relatable
- Not targeted at people seeking serious screen-time reduction tools (different use case/tone)

---

## 5. Core Features (MVP)

### 5.1 Scroll Tracking Engine

- Background Accessibility Service listens for scroll events in supported apps: Instagram, YouTube, X, Reddit
- Converts scroll pixels → meters using device screen density
- Aggregates totals per app, per session, per day

### 5.2 Dashboard (Home Screen)

- Today's total distance scrolled (all apps combined), framed as "today's trek"
- Per-app breakdown (bar or ring chart)
- Fun comparison ("Your thumb trekked 1.2x the height of the Burj Khalifa today")
- Current streak (consecutive days with tracked activity), framed as "trek streak"

### 5.3 History & Stats

- Daily/weekly/monthly view of scroll distance
- Per-app trends over time
- Personal records ("longest scroll day")

### 5.4 Leaderboards (Social)

- Opt-in friend leaderboard (invite via link/contacts)
- Weekly leaderboard reset
- Global leaderboard (optional, anonymized usernames)

### 5.5 Notifications

- Daily summary notification ("You trekked 340m today — 12% more than yesterday")
- Streak reminders (optional, off by default to avoid becoming another nagging app)

### 5.6 Browser Extension

Desktop scrolling is the other half of the habit, and unlike iOS it is actually measurable.

- MV3 extension measuring scroll distance on the same four feeds by default, plus any domain
  the user adds, or everything
- Fully functional with **no account**: today's trek, per-site breakdown, streak, records,
  badges and history all computed locally from IndexedDB
- Signing in is optional and only buys two things: sync to the same account as the phone, and
  the leaderboard

### 5.7 Web Dashboard

- The signed-in view at `/app`: totals, history charts, per-site and per-app breakdowns,
  streak, records, badges, the leaderboard, and friend management
- Shows the **per-source split** — how much of the trek was thumb and how much was browser
- Read-only. The dashboard never originates distance; it renders what the clients measured

---

## 6. Out of Scope for MVP

- Blocking/limiting app usage
- Support for apps beyond IG, YouTube, X, Reddit
- iOS app
- Detailed content-type tracking (e.g., Reels vs. Feed)

---

## 7. Technical Approach

| Component | Approach |
|---|---|
| Scroll detection | Android `AccessibilityService`, listening for `TYPE_VIEW_SCROLLED` events, filtered by target package names |
| Distance calculation | pixels ÷ `DisplayMetrics.densityDpi` × 0.0254 = meters |
| Storage | Local Room database (timestamp, package, pixel delta) |
| Background scheduling | WorkManager for daily rollups/notifications |
| Leaderboard backend | Lightweight cloud backend (Firebase or similar) for friend/global leaderboard sync |
| Min SDK | Android 8.0+ (API 26), to keep Accessibility API behavior consistent |
| Package name | `com.thumbtrek.app` |

---

## 8. Key Risks

- **Play Store policy risk:** Accessibility Services are heavily scrutinized by Google; app will need a clear, honest justification in the Play Console disclosure. Risk of rejection or delisting if not framed carefully.
- **Battery/performance:** Must be event-driven only (no polling) to avoid battery drain complaints.
- **iOS feasibility:** iOS sandboxing makes cross-app scroll tracking effectively impossible without private APIs — likely permanently Android-only, or web-extension-based for desktop.
- **Accuracy across apps:** Different apps implement scroll views differently (RecyclerView, Compose, WebView-based feeds); may need per-app calibration/testing.
- **Privacy positioning:** Even though no content is read, "an app that watches your screen" needs very clear privacy messaging to build trust.

---

## 9. Success Metrics (post-launch)

- D1 / D7 / D30 retention
- Daily Active Users opening the dashboard
- % of users who enable leaderboard/social features
- Crash-free sessions (accessibility services are prone to OEM-specific bugs)

---

## 10. Release Scope

Version 0.0.1 shipped the tracking engine, per-app controls, dashboard/history charts,
streaks, notifications, friend/global leaderboards, anonymous handles, invite sharing,
and shareable stat cards.

The current scope adds the desktop half that §8 flagged as the realistic alternative to iOS:
a browser extension, cross-device sync, and a web dashboard. See
[`docs/sync-protocol.md`](docs/sync-protocol.md) for the contract the three clients share.

---

## 11. Shipped Product Decisions

1. Each supported app can be enabled or disabled independently; all four start enabled.
2. Leaderboard publishing is fully opt-in and supports anonymous handles.
3. Version 0.0.1 is free and contains no ads or premium tier.
4. **Every client is offline-first.** An account is never required to track, and never
   required to see your own numbers. It buys sync and the leaderboard, nothing else.
5. **Distances sync in micrometres, not pixels.** Pixels are device-relative — a denser
   screen logged more of them for identical physical travel, so the board had a latent bias
   toward dense phones and could not have absorbed a browser's CSS pixel at all.
6. **Browser and phone distance are combined on the leaderboard, but stored separately.**
   Open question worth revisiting: a mouse wheel is not a thumb, so a heavy desktop user can
   out-scroll a phone user cheaply. Keeping `sources` split means the board can be changed to
   rank phone-only, or to show two boards, without a migration or any data loss.
7. **The streak that matters rewards scrolling less, not more.** A single daily limit
   (default 100 m) defines a *clean day*: finish at or under it — zero included — and the
   clean-days counter advances; go over and it resets to zero the same day. The original
   activity streak stays beside it, so nothing already shipped (badges, reminders, boards)
   changes meaning. The limit is per-device and local-only in v1; a combined cross-device
   limit is a follow-up once phone + browser totals reconcile in one place.
8. **The limit itself is the paywall, never the streak — but not yet.** Limits and streaks
   stay free for the first month while clean-streak retention is measured; billing is
   decided on that data. The weekly quota + Premium machinery is already built and keeps
   recording edits behind a `BILLING_ENFORCED = false` flag, so switching enforcement on
   later needs no migration. A broken streak can't be bought back — only tomorrow under
   the limit starts a new run.
