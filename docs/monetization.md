# Monetization: what is built, and what needs an account

Premium is five features. All five are built on every client that can carry them, and all
five are free for everyone until Premium goes on sale.

| Feature | Android | Extension | Dashboard |
|---|---|---|---|
| One limit across your phone and your browser (§7) | Settings, toggle | Options, toggle | Today oval reads it |
| A separate limit for each app or site | Settings, per app | Options, per site | (no measuring here) |
| Weekly and monthly reports | History, plus a weekly notification | Options | History, Report |
| Private groups and challenges (§8) | Friends, Groups | publishes progress | Friends, Groups |
| Backup and restore of your full history | Settings, Data | Options, Data | (no local data) |

Section numbers refer to [`sync-protocol.md`](sync-protocol.md).

## The switch

Two values decide whether anything is held back, and both are `false` today:

- `BILLING_ENFORCED` in `extension/lib/limits.js`, `web/app/limits.js` (byte-identical) and
  `app/src/main/java/com/thumbtrek/app/stats/Stats.kt`.
- `billingEnforced()` in [`firestore.rules`](../firestore.rules).

Flip them together, and only once an entitlement can actually be bought. With the rules
enforcing and no backend writing entitlements, every Premium write is refused for everyone.

## What needs your accounts

None of this can be done from the repository.

1. **Pick the price.** ₹99 a month and ₹799 a year are placeholders on the site, the
   dashboard, the extension and the app. Search for `₹99` and `₹799` to change them.
2. **Payments.**
   - Android on Google Play has to use Play Billing for digital goods. That needs a Play
     Console developer account, a merchant profile, the app published to at least a closed
     testing track, and subscription products created in the console.
   - The extension and the dashboard can use a web processor (Razorpay or Stripe, for
     example). That needs a merchant account and a KYC'd business or individual.
3. **The entitlement backend.** A server you run (Cloud Functions on the same Firebase
   project is the shortest path) that:
   - verifies Play purchase tokens with the Google Play Developer API, and web payments with
     the processor's webhook;
   - writes `entitlements/{uid} = { premium: true, source, expiresAt }`. Clients cannot write
     this collection; the rules allow the owner to read it and nobody to write it;
   - clears it on expiry, refund or cancellation.
   Cloud Functions need the Firebase project on the Blaze (pay as you go) plan.
4. **Deploy the rules.** `firebase deploy --only firestore:rules` with the
   [Firebase CLI](https://firebase.google.com/docs/cli), signed in to the project. The new
   rules (settings/limits, groups, groupCodes, entitlements) have not been run against the
   emulator yet: run `firebase emulators:start --only firestore` and exercise create, join,
   leave and delete before deploying to production. Group lookups use a single-field
   `array-contains` query, which needs no composite index.
5. **Store listings.**
   - Play Console: listing text, screenshots, the data safety form (it should match
     [`web/privacy.html`](../web/privacy.html)), and the accessibility service declaration,
     which Google reviews by hand.
   - Chrome Web Store: a developer account (one-time fee), listing, screenshots and the
     privacy practices form.
6. **Terms.** [`web/terms.html`](../web/terms.html) says Premium is not on sale. Before the
   first charge, add the price, the billing period, renewal, cancellation and refunds.

## When billing ships

In the clients, a Premium feature the account does not have keeps its normal row and gains
a "Premium" chip that opens the sheet (DESIGN.md, Premium). The sheet's button starts the
purchase. Nothing that is free today becomes Premium.
