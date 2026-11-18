# Gates: ThumbTrek visual redesign

Scope: Clean, playful Android interface across all four tabs, launcher identity, widget and sharing.

- [x] G1: The redesigned Android app compiles and existing unit tests pass.
  CHECK: .\gradlew.bat assembleDebug testDebugUnitTest
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: exit=0; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Adi\Projects\ThumbTrek; path=2c4214fb4462/52 entries; output=45 actionable tasks: 16 executed, 29 up-to-date | Configuration cache entry reused.

- [x] G2: Android lint finds no release-blocking UI or resource errors.
  CHECK: .\gradlew.bat lintDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: exit=0; shell=C:\WINDOWS\system32\cmd.exe; cwd=C:\Users\Adi\Projects\ThumbTrek; path=2c4214fb4462/52 entries; output=29 actionable tasks: 17 executed, 12 up-to-date | Configuration cache entry stored.

- [ ] G3: All four tabs are visually reviewed in light and dark themes, including empty and populated states and a small-screen layout.
  EVIDENCE: pending

- [ ] G4: Launcher icon, widget and exported share card match the new identity and render correctly.
  EVIDENCE: pending


## First-run onboarding

- [x] O1: Setup defaults, limit validation, and existing stats pass unit tests; app builds and lint has no errors.
  CHECK: .\gradlew.bat assembleDebug testDebugUnitTest lintDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: PowerShell, C:/Users/Adi/Projects/ThumbTrek, exit 0; assembleDebug testDebugUnitTest lintDebug: BUILD SUCCESSFUL. SetupPolicyTest and existing tests pass.
- [x] O2: Fresh emulator install supports resumable setup, app selection, optional permissions/sign-in, custom limit and no-limit paths.
  EVIDENCE: python scripts/verify-onboarding.py --adb C:/Users/Adi/android-sdk/platform-tools/adb.exe, exit 0, ONBOARDING CHECKS PASSED. Fresh setup, custom cap, app opt-out, permission return, draft persistence, account skip, setup replay and baseline persistence verified on emulator-5554. Screenshots in app/build/onboarding-review.
- [x] O3: Existing install preserves data and limit; no-limit dashboard/history/settings contain no clean-day judgments.
  EVIDENCE: Smoke test verified existing-install settings migration (275 m and selected app), no-limit dashboard and blank Settings editor. History clean-month section and clean badges are conditional on a non-null cap; widget progress is hidden and nudge worker returns without a cap. Phone Dev build installed, app data cleared at user request, and welcome launched via ADB.


## Track redesign (athletics track identity, all four surfaces)

- [x] T1: Android builds, unit tests pass (including the new lap-oval tests), lint has no errors.
  CHECK: ./gradlew assembleDebug testDebugUnitTest lintDebug
  EXPECT: BUILD SUCCESSFUL
  EVIDENCE: exit 0, BUILD SUCCESSFUL; 107 unit tests, 0 failures; lint 0 errors, 65 warnings.
- [x] T2: Web and extension tests pass.
  CHECK: node --test web/app/test/*.test.js; (cd extension && npm test)
  EVIDENCE: web 68 pass, 0 fail; extension 129 pass, 0 fail.
- [x] T3: Today, History, Friends, Settings and the Premium sheet reviewed on an emulator (1080x2400) in dark, and Today, Settings and Friends in light, with seeded history.
  EVIDENCE: screenshots taken over adb from the Dev build with 12 days of seeded data.
- [ ] T4: Onboarding, the home-screen widget, the share card, the launcher icon on a real launcher, and large font scale are reviewed on a device.
  EVIDENCE: pending. scripts/verify-onboarding.py was not rerun after the redesign.
- [ ] T5: Dashboard reviewed signed in against real Firestore data, and in the light theme in a browser.
  EVIDENCE: pending. Reviewed with synthetic data in dark only.


## Premium features, cleanup and site hardening

- [x] P1: Android builds with the bumped dependencies; unit tests pass; lint has no errors.
  CHECK: ./gradlew assembleDebug testDebugUnitTest lintDebug
  EVIDENCE: exit 0, BUILD SUCCESSFUL; 128 unit tests, 0 failures; lint 0 errors, 64 warnings (48 of them "newer version available", which needs AGP 8.9+ and compileSdk 36).
- [x] P2: Web and extension tests pass, including the byte-identity check on the shared modules.
  CHECK: node --test web/app/test/*.test.js; (cd extension && npm test)
  EVIDENCE: web 75 pass, 0 fail; extension 148 pass, 0 fail.
- [x] P3: Dashboard Today (shared limit), History (report) and Friends (groups, open board, owner challenge form) render with synthetic data; extension options (site limits, report, backup) and side panel (site limits on lanes and in Other) render in a headless browser.
  EVIDENCE: headless Chrome screenshots, dark theme, 1280 wide and 480 wide.
- [x] P4: The site, privacy, terms, dashboard and invite pages load under the vercel.json Content-Security-Policy with no violations, and the Firebase SDK loads on /app.
  EVIDENCE: served locally with the declared headers; no console output; setup notice stayed hidden.
- [ ] P5: firestore.rules for settings/limits, groups, groupCodes and entitlements exercised in the emulator.
  EVIDENCE: pending. No Firebase CLI on the build machine.
- [ ] P6: Android Settings (shared limit, per-app limits, weekly report, backup), the groups sheet and the oval widget reviewed on a device or emulator.
  EVIDENCE: pending. Compiled, unit-tested and linted only.
