# ThumbTrek — Product Requirements Document

**Version:** 0.2 (Draft)  
**Owner:** Slowpoke  
**Last updated:** August 2, 2026

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

## 8. Key Risks & Open Questions

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

## 10. Rough Roadmap

| Phase | Scope |
|---|---|
| MVP | Tracking engine + dashboard + local stats (no social) |
| v1.1 | Friend leaderboards + streaks |
| v1.2 | Global leaderboard + shareable stat cards (for Instagram, ironically) |
| v2 | Explore desktop/browser extension companion for web scrolling |

---

## 11. Open Questions for the Team

1. Do we want opt-in only tracking per app, or all-or-nothing?
2. Should leaderboard be public by default or fully opt-in?
3. Monetization: ads, premium stats tier, or fully free for v1?
