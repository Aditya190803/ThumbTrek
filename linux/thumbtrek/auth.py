"""Google sign-in for the Linux desktop client (RFC 8252 native-app flow).

Android signs in via Credential Manager and the web via popup; neither
exists on a Linux desktop. The Google-approved path here is authorization
code + PKCE (S256) through the system browser with a loopback redirect
(http://127.0.0.1, ephemeral port), ending in the same Firebase
accounts:signInWithIdp exchange the extension uses — so the uid, friend
code and board identity match every other client exactly.

Requires a Google Cloud OAuth client of type **Desktop** (a Web or Android
client ID will not work here; see README). Secrets live in config.json
(mode 0600); tokens never reach logs; the loopback server binds 127.0.0.1
only, serves exactly one request, and shuts down.
"""

from __future__ import annotations

import base64
import hashlib
import http.server
import json
import secrets
import threading
import time
import urllib.parse
import urllib.request

GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"
FIRESTORE_IDP_URL = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"
SCOPES = ("openid", "email", "profile")
TIMEOUT_S = 30


class AuthError(RuntimeError):
    pass


def new_pkce_pair(verifier: str | None = None) -> tuple[str, str]:
    """PKCE code_verifier + S256 challenge. A passed verifier is used verbatim
    (it must already be unpadded base64url, e.g. the RFC 7636 Appendix B
    vector); otherwise 32 random bytes are encoded. Either way the challenge
    is BASE64URL(SHA256(verifier_ascii))."""
    if verifier is None:
        verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge


def new_state() -> str:
    return secrets.token_urlsafe(24)


def build_auth_url(client_id: str, redirect_uri: str, state: str,
                   challenge: str, scopes=SCOPES) -> str:
    if not client_id or not client_id.strip():
        raise AuthError("a Desktop-type Google OAuth client ID is required")
    query = urllib.parse.urlencode({
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "scope": " ".join(scopes),
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    })
    return f"{GOOGLE_AUTH_URL}?{query}"


class _CallbackHandler(http.server.BaseHTTPRequestHandler):
    expected_state: str = ""
    result: dict | None = None

    def log_message(self, *args):  # never log query strings; they hold secrets
        pass

    def do_GET(self):
        query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        code = (query.get("code") or [None])[0]
        error = (query.get("error") or [None])[0]
        state = (query.get("state") or [None])[0]
        if state != type(self).expected_state:
            type(self).result = {"error": "state mismatch — not the login we started"}
        elif error:
            type(self).result = {"error": f"google refused: {error}"}
        elif not code:
            type(self).result = {"error": "no authorization code returned"}
        else:
            type(self).result = {"code": code}
        body = b"<html><body><p>Signed in - you can close this tab.</p></body></html>"
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
        threading.Thread(target=self.server.shutdown, daemon=True).start()


def wait_for_code(expected_state: str, server=None, timeout_s: int = 300) -> tuple[str, str]:
    """Serve one loopback callback. Returns (code, redirect_uri)."""
    handler_cls = type("Callback", (_CallbackHandler,), {"expected_state": expected_state,
                                                          "result": None})
    owned = server is None
    if owned:
        server = http.server.HTTPServer(("127.0.0.1", 0), handler_cls)
    else:
        server.RequestHandlerClass = handler_cls
    redirect_uri = f"http://127.0.0.1:{server.server_address[1]}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    thread.join(timeout_s)
    try:
        server.shutdown()
    except Exception:
        pass
    server.server_close()
    result = handler_cls.result
    if not result:
        raise AuthError("timed out waiting for the browser login")
    if "error" in result:
        raise AuthError(result["error"])
    return result["code"], redirect_uri


def _post_json(url: str, payload: dict) -> dict:
    body = urllib.parse.urlencode(payload).encode()
    request = urllib.request.Request(url, data=body,
                                     headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            return json.load(response)
    except OSError as exc:
        raise AuthError(f"token request failed: {exc}") from exc


def exchange_code(client_id: str, code: str, verifier: str, redirect_uri: str) -> str:
    """Authorization code (+PKCE) → Google ID token. The exchange is where
    PKCE is verified; without the original verifier the code is useless."""
    payload = _post_json(GOOGLE_TOKEN_URL, {
        "grant_type": "authorization_code", "code": code, "client_id": client_id,
        "redirect_uri": redirect_uri, "code_verifier": verifier})
    if payload.get("error"):
        raise AuthError(f"google refused the code: {payload.get('error_description') or payload['error']}")
    id_token = payload.get("id_token")
    if not id_token:
        raise AuthError("google did not return an ID token (openid scope required)")
    return id_token


def firebase_sign_in(api_key: str, google_id_token: str, request_uri: str) -> dict:
    """Google ID token → Firebase session. Identical exchange to the
    extension (lib/auth.js), so uid/friend-code identity is shared."""
    if not api_key:
        raise AuthError("THUMBTREK_FIREBASE_API_KEY is required to sync")
    body = json.dumps({
        "postBody": f"id_token={urllib.parse.quote(google_id_token, safe='')}"
                    f"&providerId=google.com",
        "requestUri": request_uri,
        "returnSecureToken": True,
        "returnIdpCredential": True,
    }).encode()
    request = urllib.request.Request(
        f"{FIRESTORE_IDP_URL}?key={api_key}", data=body,
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            payload = json.load(response)
    except OSError as exc:
        raise AuthError(f"firebase sign-in failed: {exc}") from exc
    if not payload.get("localId") or not payload.get("refreshToken"):
        raise AuthError("firebase rejected the Google credential")
    return {"uid": payload["localId"],
            "id_token": payload.get("idToken", ""),
            "id_token_expiry": int(time.time()) + int(payload.get("expiresIn", 3600)) - 300,
            "refresh_token": payload["refreshToken"]}


def client_id_from_env() -> str:
    import os
    client_id = os.environ.get("THUMBTREK_GOOGLE_CLIENT_ID", "")
    if not client_id:
        raise AuthError("set THUMBTREK_GOOGLE_CLIENT_ID to a Desktop-type OAuth client ID")
    return client_id


def sign_in(cfg: dict, api_key: str, client_id: str | None = None,
            opener=None, timeout_s: int = 300) -> dict:
    """Full desktop sign-in: browser → loopback code → tokens → Firebase →
    persisted session. Returns the updated config dict."""
    import webbrowser
    from . import config as config_mod
    client_id = client_id or client_id_from_env()
    opener = opener or webbrowser.open
    verifier, challenge = new_pkce_pair()
    state = new_state()
    # Bind first so the redirect URI carries the real ephemeral port.
    server = http.server.HTTPServer(("127.0.0.1", 0), _CallbackHandler)
    redirect_uri = f"http://127.0.0.1:{server.server_address[1]}"
    opener(build_auth_url(client_id, redirect_uri, state, challenge))
    try:
        code, _ = wait_for_code(state, server=server, timeout_s=timeout_s)
    finally:
        try:
            server.server_close()
        except Exception:
            pass
    google_id_token = exchange_code(client_id, code, verifier, redirect_uri)
    session = firebase_sign_in(api_key, google_id_token, redirect_uri)
    cfg.update(session)
    config_mod.save(cfg)
    return cfg


def sign_out(cfg: dict) -> dict:
    from . import config as config_mod
    for key in ("uid", "id_token", "id_token_expiry", "refresh_token"):
        cfg[key] = None if key != "id_token_expiry" else 0
    config_mod.save(cfg)
    return cfg


def fresh_id_token(cfg: dict, api_key: str) -> str:
    """Live Firebase ID token, refreshing first when close to expiry."""
    from .sync import refresh_id_token
    if cfg.get("id_token") and cfg.get("id_token_expiry", 0) > time.time() + 60:
        return cfg["id_token"]
    if not cfg.get("refresh_token"):
        raise AuthError("not signed in")
    from . import config as config_mod
    id_token, refresh_token, expiry = refresh_id_token(cfg["refresh_token"])
    cfg.update({"id_token": id_token, "refresh_token": refresh_token,
                "id_token_expiry": expiry})
    config_mod.save(cfg)
    return id_token
