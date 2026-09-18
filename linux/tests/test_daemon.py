import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import daemon
from thumbtrek.cli import PX_PER_NOTCH
from thumbtrek.store import Store


class DaemonLoopTest(unittest.TestCase):
    def test_batches_reach_the_ledger(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = Store(Path(tmp.name) / "t.db")
        self.addCleanup(store.close)
        cfg = {"tracked_apps": ["firefox"], "custom_apps": []}
        reads = {"n": 0}

        def fake_read(timeout_s):
            reads["n"] += 1
            if reads["n"] > 2:
                raise KeyboardInterrupt
            return 10

        with mock.patch.object(daemon, "read_notches", fake_read), \
             mock.patch.object(daemon, "focused_app", lambda: "firefox"), \
             mock.patch.object(daemon.config_mod, "load", lambda: cfg), \
             mock.patch.object(daemon.time, "sleep", lambda s: None):
            with self.assertRaises(KeyboardInterrupt):
                daemon.run_forever(store, cfg, _browsers=[])
        self.assertEqual(2 * 10 * int(PX_PER_NOTCH),
                         store.day_total(date.today().isoformat()))

    def test_untracked_apps_are_dropped(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        store = Store(Path(tmp.name) / "t.db")
        self.addCleanup(store.close)
        cfg = {"tracked_apps": ["firefox"], "custom_apps": []}
        reads = {"n": 0}

        def fake_read(timeout_s):
            reads["n"] += 1
            if reads["n"] > 1:
                raise KeyboardInterrupt
            return 10  # focused app below is zen: untracked, must be dropped

        with mock.patch.object(daemon, "read_notches", fake_read), \
             mock.patch.object(daemon, "focused_app", lambda: "zen"), \
             mock.patch.object(daemon.config_mod, "load", lambda: cfg), \
             mock.patch.object(daemon.time, "sleep", lambda s: None):
            with self.assertRaises(KeyboardInterrupt):
                daemon.run_forever(store, cfg, _browsers=[])
        self.assertEqual(0, store.day_total(date.today().isoformat()))


if __name__ == "__main__":
    unittest.main()
