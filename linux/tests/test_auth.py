import io
import json
import os
import sys
import tempfile
import unittest
import urllib.request
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import auth


class FakeResponse:
    def __init__(self, payload: dict):
        self._body = json.dumps(payload).encode()

    def read(self, *args):
        return self._body

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class AuthTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.env = mock.patch.dict(os.environ, {
            "THUMBTREK_DATA_HOME": self.tmp.name,
            "THUMBTREK_CONFIG_HOME": self.tmp.name,
            "THUMBTREK_FIREBASE_API_KEY": "fake-key"})
        self.env.start()
        self.addCleanup(self.env.stop)

    def test_pkce_rfc7636_vector(self):
        _verifier, challenge = auth.new_pkce_pair(
            "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        self.assertEqual("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)

    def test_pkce_defaults_random_and_url_safe(self):
        verifier, challenge = auth.new_pkce_pair()
        self.assertGreaterEqual(len(verifier), 43)
        self.assertNotIn("=", verifier + challenge)
        self.assertNotIn("+", verifier + challenge)
        self.assertNotIn("/", verifier + challenge)

    def test_auth_url_has_pkce_without_secret(self):
        url = auth.build_auth_url("client.apps.googleusercontent.com",
                                  "http://127.0.0.1:9", "state-1", "challenge-1")
        self.assertIn("code_challenge=challenge-1", url)
        self.assertIn("code_challenge_method=S256", url)
        self.assertIn("response_type=code", url)
        self.assertIn("scope=openid", url)
        self.assertNotIn("verifier", url)
        with self.assertRaises(auth.AuthError):
            auth.build_auth_url("  ", "http://127.0.0.1:9", "s", "c")

    def test_wait_for_code_times_out_cleanly(self):
        with self.assertRaises(auth.AuthError) as ctx:
            auth.wait_for_code("state-nobody-uses", timeout_s=1)
        self.assertIn("timed out", str(ctx.exception))

    def test_wait_for_code_accepts_and_rejects_state(self):
        import http.server
        import threading
        import urllib.parse
        server = http.server.HTTPServer(("127.0.0.1", 0),
                                        type("H", (auth._CallbackHandler,),
                                             {"expected_state": "good", "result": None}))
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            urllib.request.urlopen(
                f"http://127.0.0.1:{port}/?code=abc&state=evil", timeout=5).read()
            thread.join(5)
            self.assertIn("mismatch", server.RequestHandlerClass.result["error"])
        finally:
            server.shutdown()
            server.server_close()

    def test_exchange_and_firebase(self):
        def fake_urlopen(request, timeout=None):
            url = request.full_url
            if "oauth2.googleapis.com" in url:
                return FakeResponse({"id_token": "google-jwt"})
            if "signInWithIdp" in url:
                return FakeResponse({"localId": "uid-1", "idToken": "fb-jwt",
                                     "refreshToken": "fb-refresh", "expiresIn": "3600"})
            raise AssertionError(url)

        with mock.patch.object(auth.urllib.request, "urlopen", fake_urlopen):
            jwt = auth.exchange_code("cid", "code", "verifier", "http://127.0.0.1:9")
            self.assertEqual("google-jwt", jwt)
            session = auth.firebase_sign_in("fake-key", jwt, "http://127.0.0.1:9")
        self.assertEqual("uid-1", session["uid"])
        self.assertEqual("fb-refresh", session["refresh_token"])
        self.assertGreater(session["id_token_expiry"], 0)

    def test_full_sign_in_loopback(self):
        import threading
        import urllib.parse
        import urllib.request

        real_urlopen = urllib.request.urlopen

        def fake_urlopen(request, timeout=None):
            url = request.full_url if hasattr(request, "full_url") else request
            if "127.0.0.1" in url:  # the "user" calling back: really deliver it
                return real_urlopen(request, timeout=timeout)
            if "oauth2.googleapis.com" in url:
                return FakeResponse({"id_token": "google-jwt"})
            if "signInWithIdp" in url:
                return FakeResponse({"localId": "uid-9", "idToken": "fb-jwt",
                                     "refreshToken": "fb-refresh", "expiresIn": "3600"})
            raise AssertionError(url)

        opened = {}

        def fake_opener(url):
            # The "user" approves in the background, like a real browser:
            # parse the real redirect + state out of the generated URL and
            # call it back while sign_in is already serving.
            parts = urllib.parse.urlparse(url)
            query = urllib.parse.parse_qs(parts.query)
            redirect = query["redirect_uri"][0]
            state = query["state"][0]
            self.assertNotIn("code_verifier", url)

            def approve():
                urllib.request.urlopen(
                    f"{redirect}?code=authcode-1&state={state}", timeout=10).read()

            threading.Thread(target=approve, daemon=True).start()
            return True

        from thumbtrek import config as config_mod
        with mock.patch.object(auth.urllib.request, "urlopen", fake_urlopen):
            cfg = auth.sign_in(config_mod.load(), "fake-key", "desktop-client-id",
                               opener=fake_opener, timeout_s=20)
        self.assertEqual("uid-9", cfg["uid"])
        self.assertEqual("fb-refresh", cfg["refresh_token"])
        # Persisted to the 0600 config file.
        self.assertEqual("uid-9", config_mod.load()["uid"])
        # Sign-out clears the session.
        auth.sign_out(cfg)
        self.assertIsNone(config_mod.load()["uid"])

    def test_fresh_id_token_uses_cache_then_refresh(self):
        from thumbtrek import config as config_mod
        cfg = config_mod.load()
        cfg.update({"id_token": "cached", "id_token_expiry": 2**40,
                    "refresh_token": "r"})
        self.assertEqual("cached", auth.fresh_id_token(cfg, "fake-key"))
        cfg["id_token_expiry"] = 0
        with mock.patch("thumbtrek.sync.refresh_id_token",
                        return_value=("new", "r2", 123)) as refresh:
            self.assertEqual("new", auth.fresh_id_token(cfg, "fake-key"))
            refresh.assert_called_once_with("r")
        with self.assertRaises(auth.AuthError):
            auth.fresh_id_token({"refresh_token": None}, "fake-key")

    def test_cli_signin_needs_client_id(self):
        import io
        from contextlib import redirect_stdout
        from thumbtrek.cli import cmd_signin, cmd_signout
        with mock.patch.dict(os.environ, {}, clear=False):
            os.environ.pop("THUMBTREK_GOOGLE_CLIENT_ID", None)
            buf = io.StringIO()
            with redirect_stdout(buf):
                self.assertEqual(1, cmd_signin())
            self.assertIn("Sign-in failed", buf.getvalue())
        buf = io.StringIO()
        with redirect_stdout(buf):
            self.assertEqual(0, cmd_signout())
        self.assertIn("Signed out", buf.getvalue())


if __name__ == "__main__":
    unittest.main()
