import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import stats


class StatsTest(unittest.TestCase):
    def test_streak_ends_today_or_yesterday(self):
        today = date(2026, 9, 18)
        self.assertEqual(3, stats.trek_streak(
            [date(2026, 9, 16), date(2026, 9, 17), date(2026, 9, 18)], today))
        self.assertEqual(2, stats.trek_streak(
            [date(2026, 9, 16), date(2026, 9, 17)], today))
        self.assertEqual(0, stats.trek_streak([date(2026, 9, 10)], today))

    def test_limit_streak_no_infinite_past(self):
        today = date(2026, 9, 18)
        first = date(2026, 9, 18)
        self.assertEqual(1, stats.limit_streak({}, 100.0, today, first))
        self.assertEqual(0, stats.limit_streak(
            {today: 101.0}, 100.0, today, first))

    def test_week_month_keys(self):
        self.assertEqual("2026-W38", stats.week_key(date(2026, 9, 18)))
        self.assertEqual("2026-09", stats.month_key(date(2026, 9, 18)))

    def test_landmark_parity_count(self):
        self.assertEqual(21, len(stats.LANDMARKS))
        self.assertIn("the Burj Khalifa", stats.comparison(900.0))

    def test_badge_parity_count(self):
        earned_all = stats.badges(1_000_000.0, 9_000.0, 365, 30)
        self.assertEqual(25, len(earned_all))
        self.assertTrue(all(b.earned for b in earned_all))

    def test_format_distance(self):
        self.assertEqual("340 m", stats.format_distance(340.4))
        self.assertEqual("1.2 km", stats.format_distance(1200.0))


if __name__ == "__main__":
    unittest.main()
