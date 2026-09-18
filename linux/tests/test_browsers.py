import os
import stat
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import browsers
from thumbtrek.browsers import detect


def _stub_exe(directory: Path, name: str) -> None:
    target = directory / name
    target.write_text("#!/bin/sh\n")
    target.chmod(target.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


class DetectTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(os.environ.get("TMPDIR", "/tmp")) / f"tt-browsers-{os.getpid()}"
        (self.tmp / "bin").mkdir(parents=True, exist_ok=True)
        (self.tmp / "desktops").mkdir(parents=True, exist_ok=True)
        (self.tmp / "appimages").mkdir(parents=True, exist_ok=True)
        self._orig_run = browsers._run
        self._orig_which = browsers.shutil.which
        browsers._run = lambda argv, timeout=10: ""  # no flatpak/snap unless stated
        browsers.shutil.which = lambda name: None  # isolate from host PATH

    def tearDown(self):
        browsers._run = self._orig_run
        browsers.shutil.which = self._orig_which
        import shutil as _shutil
        _shutil.rmtree(self.tmp, ignore_errors=True)

    def test_path_binaries_detected(self):
        _stub_exe(self.tmp / "bin", "firefox")
        _stub_exe(self.tmp / "bin", "brave")
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        by_name = {b.name for b in found}
        self.assertIn("Firefox", by_name)
        self.assertIn("Brave", by_name)
        ff = next(b for b in found if b.name == "Firefox")
        self.assertEqual("native", ff.source)
        self.assertEqual("firefox", ff.engine)

    def test_channel_variants_share_the_stem(self):
        _stub_exe(self.tmp / "bin", "zen-bin")
        _stub_exe(self.tmp / "bin", "brave-browser-beta")
        _stub_exe(self.tmp / "bin", "firefox-developer-edition")
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        by_id = {b.id: b for b in found}
        self.assertIn("native:zen-bin", by_id)
        self.assertEqual("Zen Browser", by_id["native:zen-bin"].name)
        self.assertIn("native:brave-browser-beta", by_id)
        self.assertIn("native:firefox-developer-edition", by_id)

    def test_new_engines(self):
        for binary, name in (("waterfox", "Waterfox"), ("whale", "Whale"),
                             ("thorium", "Thorium"),
                             ("ungoogled-chromium", "Ungoogled Chromium")):
            _stub_exe(self.tmp / "bin", binary)
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        self.assertEqual({name for _, name in
                          (("waterfox", "Waterfox"), ("whale", "Whale"),
                           ("thorium", "Thorium"),
                           ("ungoogled-chromium", "Ungoogled Chromium"))},
                         {b.name for b in found if b.name in
                          {"Waterfox", "Whale", "Thorium", "Ungoogled Chromium"}})
        engines = {b.name: b.engine for b in found}
        self.assertEqual("firefox", engines["Waterfox"])
        self.assertEqual("chromium", engines["Whale"])

    def test_slug_matches(self):
        self.assertTrue(browsers.slug_matches("brave-browser-beta", ("brave-browser",)))
        self.assertTrue(browsers.slug_matches("Firefox", ("firefox",)))
        self.assertFalse(browsers.slug_matches("kitty", ("firefox",)))
        self.assertFalse(browsers.slug_matches("", ("firefox",)))

    def test_browser_from_config(self):
        good = browsers.browser_from_config(
            {"name": "My Browser", "engine": "chromium",
             "launch": ["/opt/mine/mine", "--profile", "x"]})
        self.assertIsNotNone(good)
        self.assertEqual("custom:my-browser", good.id)
        self.assertEqual(("mine",), good.app_slugs)
        explicit = browsers.browser_from_config(
            {"name": "Z", "engine": "firefox", "launch": ["z"],
             "slugs": ["Zeta", "zeta-beta"]})
        self.assertEqual(("zeta", "zeta-beta"), explicit.app_slugs)
        for bad in ({}, {"name": "x"}, {"name": "x", "engine": "safari",
                                        "launch": ["x"]},
                    {"name": "x", "engine": "chromium", "launch": []},
                    {"name": "x", "engine": "chromium", "launch": "x"},
                    "nope", None):
            self.assertIsNone(browsers.browser_from_config(bad))

    def test_desktop_entry_without_path_binary(self):
        (self.tmp / "desktops" / "zen.desktop").write_text(
            "[Desktop Entry]\nName=Zen Browser\nType=Application\n"
            "Exec=/opt/zen/zen %U\nCategories=Network;WebBrowser;\n")
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        zen = [b for b in found if b.name == "Zen Browser"]
        self.assertEqual(1, len(zen))
        self.assertEqual(["/opt/zen/zen"], zen[0].launch)

    def test_flatpak_and_snap(self):
        browsers._run = lambda argv, timeout=10: (
            "com.brave.Browser\tBrave Browser\norg.mozilla.firefox\tFirefox\n"
            if argv[0] == "flatpak"
            else "Name  Version\nchromium  1.0\n")
        browsers.shutil.which = lambda name: (
            "/usr/bin/flatpak" if name == "flatpak"
            else "/snap/bin/chromium" if name == "chromium" else None)
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        sources = {(b.name, b.source) for b in found}
        self.assertIn(("Brave", "flatpak"), sources)
        self.assertIn(("Firefox", "flatpak"), sources)
        self.assertIn(("Chromium", "snap"), sources)
        brave = next(b for b in found if (b.name, b.source) == ("Brave", "flatpak"))
        self.assertEqual(["flatpak", "run", "com.brave.Browser"], brave.launch)

    def test_appimage_by_name_only_never_executed(self):
        fake = self.tmp / "appimages" / "Vivaldi-7.0.AppImage"
        fake.write_text("definitely not executed")
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        viv = [b for b in found if b.name == "Vivaldi"]
        self.assertEqual(1, len(viv))
        self.assertEqual("appimage", viv[0].source)

    def test_known_tarball_locations(self):
        import shutil as _shutil
        home = Path(self.tmp) / "home"
        zen_dir = home / "zen"
        zen_dir.mkdir(parents=True)
        _stub_exe(zen_dir, "zen")
        with mock.patch.dict(os.environ, {"HOME": str(home)}):
            found = browsers._detect_known_paths()
        zen = [b for b in found if b.name == "Zen Browser"]
        self.assertEqual(1, len(zen))
        self.assertEqual([str(zen_dir / "zen")], zen[0].launch)
        _shutil.rmtree(self.tmp, ignore_errors=True)

    def test_scan_report_shape(self):
        report = browsers.scan_report()
        self.assertIn("path_dirs", report)
        self.assertIn("hits", report)
        self.assertIn("known_locations", report["hits"])
        self.assertIsInstance(report["flatpak_present"], bool)
        _stub_exe(self.tmp / "bin", "firefox")
        (self.tmp / "desktops" / "firefox.desktop").write_text(
            "[Desktop Entry]\nName=Firefox\nType=Application\n"
            f"Exec={self.tmp}/bin/firefox %u\nCategories=Network;WebBrowser;\n")
        found = detect(path_dirs=[str(self.tmp / "bin")],
                       desktop_dirs=[str(self.tmp / "desktops")],
                       appimage_dirs=[str(self.tmp / "appimages")],
                       include_known_paths=False)
        ff = [b for b in found if b.name == "Firefox"]
        self.assertEqual(1, len(ff))  # same launch target, not two entries


if __name__ == "__main__":
    unittest.main()
