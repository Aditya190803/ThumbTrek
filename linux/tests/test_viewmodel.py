import os
import sys
import tempfile
import unittest
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import viewmodel as vm
from thumbtrek.store import Store

PX_100M = 100_000  # 100 m in CSS px at 96 dpi... see below


def _seed(tmp: str) -> Store:
    store = Store(Path(tmp) / "t.db")
    today = date.today()
    # 1 CSS px == 25400/96 µm; store pixels directly like the daemon does.
    store.accumulate("firefox", today.isoformat(), 37_795)   # ~10 m
    store.accumulate("kitty", (today - timedelta(days=1)).isoformat(), 377_952)  # ~100 m
    store.accumulate("<script>alert(1)</script>", today.isoformat(), 3_780)  # hostile name
    return store


class ViewModelTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.store = _seed(self.tmp.name)
        self.addCleanup(self.store.close)
        self.cfg = {"tracked_apps": ["firefox"], "custom_apps": [],
                    "daily_limit_m": 100.0, "leaderboard_opt_in": False}
        self.today = date.today()

    def test_dashboard_mirrors_android_sections(self):
        d = vm.dashboard(self.store, self.cfg, self.today)
        self.assertAlmostEqual(d.today_m, (37_795 + 3_780) * vm.M_PER_PX, places=2)
        self.assertEqual(100.0, d.limit_m)
        self.assertFalse(d.over_limit)
        self.assertEqual(0, d.nudge_level)
        self.assertEqual(2, d.streak)  # today + yesterday
        self.assertEqual(7, len(d.week_rows))  # trailing 7 days, like dailyBuckets(7)
        self.assertTrue(d.week_rows[-1][2])  # today is highlighted last
        apps = dict(d.app_split)
        self.assertIn("firefox", apps)
        self.assertIn("kitty", {a for a, _ in vm.app_trends(self.store, self.today)})
        self.assertTrue(d.landmark_line)

    def test_history_ranges_and_deltas(self):
        days_px = {vm.parse_day(d): px for d, px in self.store.days().items()}
        for index in (0, 1, 2):
            hist = vm.history(days_px, index, self.today)
            self.assertGreater(hist.period_m, 0)
        self.assertEqual(("7 days", "8 weeks", "6 months"), vm.HISTORY_RANGES)
        hist = vm.history(days_px, 99, self.today)
        self.assertEqual(7, len(hist.buckets))
        day = vm.history(days_px, 0, self.today)
        self.assertIsNotNone(day.day_delta)
        month = vm.history(days_px, 2, self.today)
        self.assertIsNone(month.day_delta)

    def test_month_grid_states(self):
        d = vm.dashboard(self.store, self.cfg, self.today)
        grid = vm.month_grid(d.day_m, d.first_day, d.limit_m, self.today)
        self.assertEqual(7, len(grid[0]))
        flat = {day_no: state for week in grid for day_no, state in week if day_no}
        self.assertEqual("clean", flat[self.today.day])  # ~11 m < 100 m limit
        yesterday = self.today - timedelta(days=1)
        if yesterday.month == self.today.month:
            self.assertEqual("clean", flat[yesterday.day])  # ~100 m is at the limit

    def test_recent_days_and_hostile_names_untouched(self):
        d = vm.dashboard(self.store, self.cfg, self.today)
        log = vm.recent_days(d.day_m)
        self.assertTrue(log and log[0][0] == self.today)
        # View-model returns raw names; the Gtk layer must use set_text (see
        # test_ui.py's no-markup guard) so nothing here is pre-escaped.
        self.assertIn("<script>alert(1)</script>", [a for a, _ in d.app_split])


if __name__ == "__main__":
    unittest.main()
