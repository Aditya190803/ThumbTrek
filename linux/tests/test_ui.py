"""Native-window smoke test. Skipped wherever GTK cannot init (e.g. CI
without a display) — viewmodel tests carry the headless coverage instead."""

import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

LINUX = Path(__file__).resolve().parents[1]


def _gtk_available() -> bool:
    try:
        import gi
        gi.require_version("Gtk", "4.0")
        gi.require_version("Adw", "1")
        from gi.repository import Gtk
        return bool(Gtk.init_check())
    except (ImportError, ValueError):
        return False


@unittest.skipUnless(_gtk_available(), "GTK4/Adwaita unavailable")
class NativeWindowTest(unittest.TestCase):
    def test_no_pango_markup_anywhere(self):
        # Structural XSS guard: hostile app names reach labels only via
        # Gtk.Label(label=...) plain text, never set_markup. Adw row
        # title/subtitle props are plain text by API contract.
        for module in ("ui", "widgets", "viewmodel", "charts", "theme"):
            src = (LINUX / "thumbtrek" / f"{module}.py").read_text()
            self.assertNotIn("set_markup", src, module)

    def test_window_builds_four_tabs(self):
        home = tempfile.TemporaryDirectory()
        self.addCleanup(home.cleanup)
        with mock.patch.dict(os.environ, {
                "THUMBTREK_DATA_HOME": str(Path(home.name) / "data"),
                "THUMBTREK_CONFIG_HOME": str(Path(home.name) / "cfg")}):
            from thumbtrek.store import Store
            from thumbtrek.ui import App, MainWindow
            from thumbtrek.charts import Gauge
            store = Store()
            store.accumulate("firefox", "2026-09-18", 37795)
            store.close()
            app = App()
            window = MainWindow(app)
            try:
                names = set()
                for name in ("trek", "history", "social", "settings"):
                    child = window.stack.get_child_by_name(name)
                    assert child is not None, name
                    names.add(window.stack.get_page(child).get_name())
                self.assertEqual({"trek", "history", "social", "settings"}, names)
                gauges = []

                def walk(widget):
                    if isinstance(widget, Gauge):
                        gauges.append(widget)
                    child = widget.get_first_child()
                    while child is not None:
                        walk(child)
                        child = child.get_next_sibling()

                walk(window)
                self.assertTrue(gauges, "hero dial missing")

                texts = []

                def collect(widget):
                    from gi.repository import Gtk as _Gtk
                    if isinstance(widget, _Gtk.Label):
                        texts.append(widget.get_text())
                    child = widget.get_first_child()
                    while child is not None:
                        collect(child)
                        child = child.get_next_sibling()

                collect(window)
                for expected in ("BROWSER EXTENSION", "SOCIAL FEEDS", "SOCIAL",
                                 "See how your week stacks up.", "DAILY LIMIT",
                                 "TODAY'S TREK"):
                    self.assertIn(expected, texts, expected)
                    self.assertIn(expected, texts, expected)
            finally:
                window.destroy()


if __name__ == "__main__":
    unittest.main()
