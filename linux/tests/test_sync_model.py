import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import sync_model as sm


class SyncModelTest(unittest.TestCase):
    def test_wheel_units_match_browser_css_dpi(self):
        browser = round(40 / 96 * 25400)
        self.assertEqual(browser, sm.wheel_notches_to_micrometres(1))
        self.assertEqual(0, sm.pixels_to_micrometres(100, 0))  # corrupt dpi guard

    def test_combine_ignores_stale_sources_and_sums_unknown(self):
        sources = [
            sm.SourceTotals("android", week_key="2026-W38", week_um=100,
                            month_key="2026-09", month_um=500, total_um=900),
            sm.SourceTotals("web", week_key="2026-W36", week_um=9999,
                            month_key="2026-09", month_um=10, total_um=20),
            sm.SourceTotals("linux", week_key="2026-W38", week_um=50,
                            month_key="2026-09", month_um=60, total_um=70),
        ]
        combined = sm.combine(sources, "2026-W38", "2026-09")
        self.assertEqual(150, combined.week_um)  # stale web week drops out
        self.assertEqual(570, combined.month_um)
        self.assertEqual(990, combined.total_um)  # totals always sum

    def test_dirty_days_and_64_key_cap(self):
        ledgers = sm.day_ledgers_um(
            {"2026-09-18": {f"app{i}": 1000 for i in range(70)}})
        self.assertEqual(70_000, ledgers[0].um)  # um stays whole
        self.assertEqual(64, len(ledgers[0].apps))  # breakdown capped
        self.assertEqual([], sm.dirty_days(ledgers, {"2026-09-18": 70_000}))
        self.assertEqual(1, len(sm.dirty_days(ledgers, {})))

    def test_day_document_id_embeds_source(self):
        self.assertEqual("2026-09-18__linux",
                         sm.DayLedger("2026-09-18", 1, {}).document_id("linux"))


if __name__ == "__main__":
    unittest.main()
