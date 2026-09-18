import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import ext_install
from thumbtrek.browsers import (EXTENSION_ID, Browser, ENGINE_CHROMIUM,
                                ENGINE_FIREFOX)


def _browser(**over):
    base = dict(id="native:firefox", name="Firefox", engine=ENGINE_FIREFOX,
                source="native", launch=["firefox"], app_slugs=("firefox",))
    base.update(over)
    return Browser(**base)


class ExtInstallTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        self.patchers = [
            mock.patch.dict(os.environ, {
                "HOME": self.home.name,
                "THUMBTREK_DATA_HOME": str(Path(self.home.name) / "data"),
                "THUMBTREK_CONFIG_HOME": str(Path(self.home.name) / "cfg"),
            }),
        ]
        for p in self.patchers:
            p.start()
        self.addCleanup(self.home.cleanup)
        for p in self.patchers:
            self.addCleanup(p.stop)

    def test_extension_id_matches_pinned_constant(self):
        self.assertEqual(EXTENSION_ID, ext_install.extension_id())
        self.assertRegex(EXTENSION_ID, r"^[a-p]{32}$")

    def test_stage_copies_manifest(self):
        staged = ext_install.stage_extension()
        self.assertTrue((staged / "manifest.json").exists())
        self.assertTrue((staged / "background" / "service-worker.js").exists())

    def test_stage_firefox_uses_scripts_variant(self):
        ff = _browser(engine=ENGINE_FIREFOX)
        staged = ext_install.stage_extension(ff)
        manifest = json.loads((staged / "manifest.json").read_text())
        self.assertEqual({"scripts": ["background/shared.js"]}, manifest["background"])
        self.assertNotIn("key", manifest)

    def test_addon_ids_per_engine(self):
        from thumbtrek.browsers import ENGINE_CHROMIUM, EXTENSION_ID, FIREFOX_ID
        self.assertEqual(EXTENSION_ID,
                         ext_install.extension_addon_id(_browser(engine=ENGINE_CHROMIUM)))
        self.assertEqual(FIREFOX_ID,
                         ext_install.extension_addon_id(_browser(engine=ENGINE_FIREFOX)))
        self.assertEqual(EXTENSION_ID, ext_install.extension_addon_id(None))

    def test_firefox_manifest_allows_extension(self):
        from thumbtrek.browsers import FIREFOX_ID
        manifest_path, wrapper = ext_install.install_native_host(_browser())
        payload = json.loads(manifest_path.read_text())
        self.assertEqual(["native-messaging-hosts"], [manifest_path.parent.name])
        self.assertEqual([FIREFOX_ID], payload["allowed_extensions"])
        self.assertNotIn("allowed_origins", payload)
        self.assertIn("--browser", wrapper.read_text())

    def test_chromium_manifest_allows_origin(self):
        from thumbtrek.browsers import KNOWN
        chrome = next(k for k in KNOWN if k.config_dir == "google-chrome")
        browser = Browser(id="flatpak:com.google.Chrome", name="Google Chrome",
                          engine=ENGINE_CHROMIUM, source="flatpak",
                          launch=["flatpak", "run", "com.google.Chrome"],
                          known=chrome, app_slugs=chrome.app_slugs)
        manifest_path, _ = ext_install.install_native_host(browser)
        payload = json.loads(manifest_path.read_text())
        self.assertEqual([f"chrome-extension://{EXTENSION_ID}/"], payload["allowed_origins"])
        self.assertIn("com.google.Chrome", str(manifest_path))
        self.assertEqual(1, ext_install.remove_native_host(browser))
        self.assertFalse(manifest_path.exists())

    def test_pick_browser_preselect_and_empty(self):
        self.assertIsNone(ext_install.pick_browser([]))
        browsers = [_browser(), _browser(id="native:brave", name="Brave")]
        self.assertEqual("Brave", ext_install.pick_browser(browsers, preselect=2).name)
        self.assertIsNone(ext_install.pick_browser(browsers, preselect=9))

    def test_guides_and_argv(self):
        staged = Path("/tmp/staged-ext")
        ff = _browser()
        self.assertIn("about:debugging", ext_install.guide(ff, staged))
        self.assertIsNone(ext_install.oneshot_argv(ff, staged))
        self.assertEqual(["firefox", "about:debugging#/runtime/this-firefox"],
                         ext_install.open_page_argv(ff))

    def test_install_refuses_nonexecutable_host(self):
        with mock.patch.dict(os.environ, {"THUMBTREK_NATIVE_HOST": "/nonexistent/host"}):
            with self.assertRaises(RuntimeError) as ctx:
                ext_install.install_native_host(_browser())
        self.assertIn("not executable", str(ctx.exception))

    def test_ping_round_trips_like_the_browser(self):
        import stat as _stat
        import struct as _struct
        import subprocess as _subprocess
        directory = Path(self.home.name) / "nm"
        directory.mkdir(parents=True, exist_ok=True)
        wrapper = directory / "fake-host.sh"
        wrapper.write_text(
            "#!/bin/sh\n"
            "python3 -c \"import sys,struct; "
            "d=sys.stdin.buffer.read(); "
            "sys.stdout.buffer.write(struct.pack('<I',12)+b'{\\\"ok\\\": true}')\"\n")
        wrapper.chmod(wrapper.stat().st_mode | _stat.S_IXUSR)
        (directory / "com.thumbtrek.native.json").write_text(
            __import__("json").dumps({"path": str(wrapper)}))
        with mock.patch.object(ext_install, "nm_host_dirs", lambda browser: [directory]):
            result = ext_install.ping_host(_browser())
        self.assertTrue(result["ok"], result)
        self.assertEqual(str(wrapper), result["wrapper"])

    def test_ping_reports_missing_manifest(self):
        with mock.patch.object(ext_install, "nm_host_dirs", lambda browser: []):
            result = ext_install.ping_host(_browser())
        self.assertFalse(result["ok"])
        self.assertIn("no bridge manifest", result["error"])


if __name__ == "__main__":
    unittest.main()
