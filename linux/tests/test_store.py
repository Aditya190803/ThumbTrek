import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek.store import Store


class StoreTest(unittest.TestCase):
    def test_accumulate_upserts_one_row_per_app_per_day(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / "t.db")
            store.accumulate("firefox", "2026-09-18", 40)
            store.accumulate("firefox", "2026-09-18", 80)
            self.assertEqual(120, store.day_total("2026-09-18"))
            self.assertEqual({"firefox": 120}, store.day_split("2026-09-18"))
            out = Path(tmp) / "export.csv"
            store.export_csv(out)
            self.assertIn("2026-09-18", out.read_text())
            store.close()

    def test_nonpositive_drops(self):
        with tempfile.TemporaryDirectory() as tmp:
            store = Store(Path(tmp) / "t.db")
            store.accumulate("firefox", "2026-09-18", 0)
            self.assertEqual(0, store.day_total("2026-09-18"))
            store.close()


if __name__ == "__main__":
    unittest.main()
