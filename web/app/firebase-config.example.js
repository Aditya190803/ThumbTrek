// Copy this file to `firebase-config.js` and fill in the real values.
//
// ---------------------------------------------------------------------------------------
// These values are PUBLIC and are meant to be committed once real.
// ---------------------------------------------------------------------------------------
// Unlike app/google-services.json (which is gitignored because it also carries OAuth client
// identifiers tied to the release signing key), a Firebase *web* config is shipped verbatim
// inside every browser that loads the page — it is an address, not a secret. Google's own
// docs say so. What actually protects the data is firestore.rules plus the Authorized
// domains list in Firebase Auth; an apiKey on its own opens nothing.
//
// So: commit the filled-in firebase-config.js. Do not build a secret-injection step for it.
// It is deliberately a separate file from the rest of the app so that this repo can ship a
// working dashboard without a Firebase Web App existing yet — see web/README.md for which
// console screen produces each value.
//
// The placeholders below are recognised by firebase.js, which shows a setup panel instead
// of a broken sign-in button while they are still in place.

export const firebaseConfig = {
  apiKey: 'YOUR_WEB_API_KEY',
  authDomain: 'thumb-trek.firebaseapp.com',
  projectId: 'thumb-trek',
  storageBucket: 'thumb-trek.firebasestorage.app',
  messagingSenderId: 'YOUR_SENDER_ID',
  appId: 'YOUR_WEB_APP_ID',
};
