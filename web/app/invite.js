// /i/<code> — the link Identity.kt has always promised.
//
// `inviteLink(code)` on the phone produces https://thumbtrek.adityamer.dev/i/<code> and hands it to
// the share sheet, so those links are already out in the world in people's chats. This page
// is what they land on. vercel.json rewrites /i/(.*) here, keeping the pretty URL in the
// address bar — which matters, because the code in the bar is the fallback when everything
// else fails.
//
// It does three things, in descending order of how likely they are to work:
//   1. If you are signed in, send the friend request from here.
//   2. If you are not, offer sign-in, and remember the code across the round trip.
//   3. Otherwise show the code big enough to read out loud, with the steps for the phone.

import { announce, byId, show } from './dom.js';
import { NotConfiguredError, explainError, firebase, signIn, watchAuth } from './firebase.js';
import { sendRequest } from './friends.js';
import { FRIEND_CODE_LENGTH, formatFriendCode, friendCode, normalizeFriendCode } from './identity.js';

/**
 * The code, from the rewritten path or from the query Vercel passes alongside it.
 *
 * Both are checked because the rewrite keeps /i/<code> in the address bar while handing the
 * capture to the destination as ?code=, and a hand-typed /app/invite?code=… should work too.
 * normalizeFriendCode does the rest: it already accepts a whole invite URL, lower case, and
 * the grouping dash, which is exactly what people paste.
 */
function codeFromLocation() {
  const fromQuery = new URLSearchParams(location.search).get('code');
  return normalizeFriendCode(fromQuery || location.pathname);
}

const code = codeFromLocation();
const valid = code.length === FRIEND_CODE_LENGTH;

const state = { user: null, sent: false };

function render() {
  byId('code').textContent = valid ? formatFriendCode(code) : '————————';
  byId('code-inline').textContent = valid ? formatFriendCode(code) : 'the code';
  show(byId('invalid'), !valid);
  show(byId('add-here'), valid);

  // Chrome for Android launches an installed app straight from an intent: URL, and
  // MainActivity parses the last path segment as a friend code (`thumbtrek://i/<code>`),
  // so the app opens with the code already on its way to the Social tab. A mangled code
  // is dropped on the app side rather than prefilled, so only a valid one rides along.
  // The fallback URL is what everyone without the app gets, including every desktop
  // browser.
  const fallback = 'https://github.com/Aditya190803/ThumbTrek/releases/latest';
  byId('open-app').href =
    valid
      ? `intent://i/${code}#Intent;scheme=thumbtrek;package=com.thumbtrek.app;` +
        `S.browser_fallback_url=${encodeURIComponent(fallback)};end`
      : `intent://open#Intent;scheme=thumbtrek;package=com.thumbtrek.app;` +
        `S.browser_fallback_url=${encodeURIComponent(fallback)};end`;
}

function renderAuth() {
  const stateLine = byId('add-state');
  const addButton = byId('add-button');
  const signinButton = byId('signin-button');

  if (state.sent) {
    stateLine.textContent =
      'Request sent. They have to accept it before either of you appears on the other’s ' +
      'board — that is the same two-way handshake the app uses.';
    show(addButton, false);
    show(signinButton, false);
    return;
  }
  if (state.user) {
    stateLine.textContent = `Signed in as ${state.user.displayName || 'you'}. One tap and the request is on its way.`;
    show(addButton, true);
    show(signinButton, false);
  } else {
    stateLine.textContent =
      'Sign in with the Google account you use on the phone and you can send the request ' +
      'straight from here.';
    show(addButton, false);
    show(signinButton, true);
  }
}

function reportError(message) {
  const node = byId('add-error');
  node.textContent = message;
  show(node, Boolean(message));
}

byId('copy-code').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(code);
    byId('copy-code').textContent = 'Copied';
    announce('Friend code copied.');
    setTimeout(() => {
      byId('copy-code').textContent = 'Copy code';
    }, 1800);
  } catch {
    announce('Copying was blocked. The code is on screen — it is eight characters.');
  }
});

byId('add-button').addEventListener('click', async () => {
  const button = byId('add-button');
  button.disabled = true;
  reportError('');
  try {
    await sendRequest(state.user.uid, code);
    state.sent = true;
    announce('Trek request sent.');
  } catch (error) {
    reportError(explainError(error));
    announce(explainError(error));
  } finally {
    button.disabled = false;
    renderAuth();
  }
});

byId('signin-button').addEventListener('click', async () => {
  try {
    await signIn();
  } catch (error) {
    reportError(explainError(error));
  }
});

async function boot() {
  render();
  try {
    await firebase();
  } catch (error) {
    // No Firebase here is not a dead end: the code, the steps and the app link all still
    // work, so the page degrades to the printed-invitation version of itself.
    byId('add-state').textContent =
      error instanceof NotConfiguredError
        ? 'Adding trekkers from the web is not set up on this deployment yet. The code above ' +
          'still works in the app.'
        : explainError(error);
    show(byId('add-button'), false);
    show(byId('signin-button'), false);
    return;
  }

  await watchAuth(async (user) => {
    state.user = user;
    // Guard against the code in the link being your own — the app refuses that too, and
    // finding out after a round trip through Google is a worse way to learn it.
    if (user && valid && code === (await friendCode(user.uid))) {
      state.user = null;
      byId('add-state').textContent =
        "That is your own code. Send the link to somebody else — it is how they find you.";
      show(byId('add-button'), false);
      show(byId('signin-button'), false);
      return;
    }
    renderAuth();
  });
}

renderAuth();
boot();
