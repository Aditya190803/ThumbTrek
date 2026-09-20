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
