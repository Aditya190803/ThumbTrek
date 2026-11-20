# Publishing on Google Play

The code side is done on two lines:

- `main` (sideloaded APK, keeps the self-updater) carries everything both builds share: the
  Accessibility disclosure, account deletion in the app and on the web, targetSdk 36,
  Crashlytics, and the rules tests.
- `release/play` is `main` without the updater, with R8 on, and with a workflow that builds a
  signed `.aab` on `play-v*` tags. Its README section explains how to keep it in sync.

Everything below needs the owner's accounts, so it can't be done from the repository.

## Before the first upload

1. **Contact email.** `/delete-account` uses aditya@adityamer.dev. Play Console's developer
   contact email should be the same address.
2. **Deploy the site** so `/privacy` and `/delete-account` are live. Both URLs go into Play
   Console.
3. **Deploy the Firestore rules** if they changed: `firebase deploy --only firestore:rules`.
   `scripts/rules-test` runs them against the emulator first.
4. **Crashlytics.** Open Crashlytics once in the Firebase console for project `thumb-trek`, so
   it starts accepting reports. The `GOOGLE_SERVICES_JSON` secret has to be set: the Play
   workflow refuses to build without it.
5. **Play App Signing.** When creating the app, choose to use the **existing release key** as
   the app signing key (Play Console offers the PEPK upload flow for that). The Play build and
   the sideloaded APK then share a signature, so people can switch between them without
   uninstalling, and the Firebase SHA-1 already registered keeps working. If Play generates a
   new key instead, add Play's app signing SHA-1 (Console → Setup → App signing) to Firebase,
   or Google Sign-In fails in the Play build with "No credentials available"; see
   `firebase-release-sha.md`.

## Play Console

6. **Developer account.** A new personal account must run a closed test with at least 12
   testers who stay opted in for 14 days before it can apply for production.
7. **Accessibility API declaration** (App content → Sensitive permissions). Say what the
   service does in the words of the in-app disclosure (`ui/TrackingConsent.kt`): it receives
   scroll events from user-chosen apps and keeps only the distance; it never reads screen
   content or performs actions. Attach a short screen recording that shows the disclosure,
   Agree, turning the service on, and distance appearing on Today. `isAccessibilityTool` stays
   unset. ThumbTrek isn't a disability tool, and claiming it is gets apps removed.
8. **Accessibility review for payment apps.** Separately, request Google Play's enhanced
   accessibility review through the issue tracker linked from the Play Integrity "app access
   risk" docs. Only reviewed services are left out of the verdict that Google Pay and bank
   apps check. Without it, Play installs read as `KNOWN_CONTROLLING` instead of
   `UNKNOWN_CONTROLLING`. Many payment apps only block the unknown kind, but that's their
   decision. The "Pause for payments" button stays either way.
9. **Data safety form.** It must match `web/privacy.html`:
   - Collected, optional, only after sign-in and opting in: name and profile photo (Google
     account), user IDs, and app activity (daily scroll distance per app).
   - Collected automatically: crash logs and diagnostics (Crashlytics).
   - Not collected: location, contacts, messages, screen content, browsing history.
   - Encrypted in transit: yes. Users can request deletion: yes, with the `/delete-account` URL.
10. **App access.** Tracking needs no login, but Friends does. Give reviewers a note that any
    Google account works, or a test account.
11. **Listing.** Short and full description, icon (512 px), feature graphic (1024×500), at
    least 2 phone screenshots, content rating questionnaire, target audience (13+; not
    designed for children), and the privacy policy URL.

## Every release

12. Bump `versionCode` and `versionName` on `main`, rebase `release/play` onto it, then
    `git tag -a play-vX.Y.Z` on `release/play` and push the tag. Download the `.aab` from the
    workflow run and upload it to the track in Play Console.

## Later: Premium on Play

Play Billing products, a merchant profile, and the entitlement backend in
`monetization.md`. Play requires Play Billing for digital goods sold in the Android app.
