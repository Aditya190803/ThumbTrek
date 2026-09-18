import sys
import tempfile
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import feeds
from thumbtrek import viewmodel as vm
from thumbtrek.store import Store


class FeedsTest(unittest.TestCase):
    def test_names_mirror_phone_and_extension(self):
        self.assertEqual("Instagram", feeds.feed_name("instagram.com"))
        self.assertEqual("YouTube", feeds.feed_name("youtube.com"))
        self.assertEqual("YouTube", feeds.feed_name("youtu.be"))
        self.assertEqual("X", feeds.feed_name("x.com"))
        self.assertEqual("Reddit", feeds.feed_name("reddit.com"))
        # Android package keys resolve too (shared ledger, either writer).
        self.assertEqual("X", feeds.feed_name("com.twitter.android"))

    def test_custom_labels_win_unknowns_pass_through(self):
        self.assertEqual("Work", feeds.feed_name("crm.internal", {"crm.internal": "Work"}))
        self.assertEqual("firefox", feeds.feed_name("firefox"))
        self.assertEqual("example.com", feeds.feed_name("example.com"))

    def test_rank_orders_feeds_first(self):
        keys = ["kitty", "reddit.com", "firefox", "instagram.com"]
        self.assertEqual(["instagram.com", "reddit.com", "firefox", "kitty"],
                         sorted(keys, key=lambda k: (feeds.feed_rank(k), k)))

    def test_feed_day_aggregates_domains(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / "t.db")
            today = date.today().isoformat()
            store.accumulate("youtube.com", today, 37795)
            store.accumulate("youtu.be", today, 37795)
            store.accumulate("firefox", today, 1000)
            totals = vm.feed_day(store, date.today())
            store.close()
        self.assertAlmostEqual(totals["YouTube"], 2 * 37795 * vm.M_PER_PX, places=4)
        self.assertEqual(0.0, totals["Instagram"])
        self.assertEqual(set(totals), {"Instagram", "YouTube", "X", "Reddit"})


if __name__ == "__main__":
    unittest.main()
