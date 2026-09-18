"""Sync model — Python port of app/.../social/SyncModel.kt (docs/sync-protocol.md).

New source slug: ``linux``. Old clients keep working because combine() sums
every slug it sees (SyncModel.kt already does this); the per-source weekKey
staleness rule applies unchanged.
"""

from __future__ import annotations

from dataclasses import dataclass, field

SOURCE_LINUX = "linux"
SOURCE_ANDROID = "android"
SOURCE_WEB = "web"

BATCH_LIMIT = 500
MAX_APP_KEYS = 64
MICROMETRES_PER_INCH = 25_400.0
MICROMETRES_PER_METRE = 1_000_000.0

# One mouse-wheel notch ~= this many CSS px (matches browser path; configurable).
WHEEL_NOTCH_PX = 40.0
CSS_DPI = 96.0


def pixels_to_micrometres(pixels: int, density_dpi: int) -> int:
    if density_dpi <= 0:
        return 0
    return round(pixels / density_dpi * MICROMETRES_PER_INCH)


def css_pixels_to_micrometres(css_px: float) -> int:
    return round(css_px / CSS_DPI * MICROMETRES_PER_INCH)


def wheel_notches_to_micrometres(notches: int, notch_px: float = WHEEL_NOTCH_PX) -> int:
    return css_pixels_to_micrometres(notches * notch_px)


def micrometres_to_meters(um: int) -> float:
    return um / MICROMETRES_PER_METRE


def rank_um(um, legacy_pixels: int, density_dpi: int) -> int:
    return um if um is not None else pixels_to_micrometres(legacy_pixels, density_dpi)


@dataclass
class SourceTotals:
    source: str
    week_key: str | None = None
    week_um: int = 0
    month_key: str | None = None
    month_um: int = 0
    total_um: int = 0
    updated_at: int | None = None

    def week_um_in(self, week_key: str) -> int:
        return self.week_um if self.week_key == week_key else 0

    def month_um_in(self, month_key: str) -> int:
        return self.month_um if self.month_key == month_key else 0


@dataclass
class CombinedTotals:
    week_um: int = 0
    month_um: int = 0
    total_um: int = 0


def combine(sources, week_key: str, month_key: str) -> CombinedTotals:
    return CombinedTotals(
        week_um=sum(s.week_um_in(week_key) for s in sources),
        month_um=sum(s.month_um_in(month_key) for s in sources),
        total_um=sum(s.total_um for s in sources),
    )


def parse_sources(raw: dict | None) -> list[SourceTotals]:
    if not raw:
        return []
    out = []
    for slug, fields in raw.items():
        if not isinstance(slug, str) or not isinstance(fields, dict):
            continue
        out.append(SourceTotals(
            source=slug,
            week_key=fields.get("weekKey") if isinstance(fields.get("weekKey"), str) else None,
            week_um=int(fields.get("weekUm") or 0),
            month_key=fields.get("monthKey") if isinstance(fields.get("monthKey"), str) else None,
            month_um=int(fields.get("monthUm") or 0),
            total_um=int(fields.get("totalUm") or 0),
            updated_at=fields.get("updatedAt"),
        ))
    return sorted(out, key=lambda s: s.source)


def source_label(source: str) -> str:
    return {"android": "Phone", "web": "Browser", "linux": "Desktop"}.get(source, source[:1].upper() + source[1:])


@dataclass
class DayLedger:
    date: str
    um: int
    apps: dict = field(default_factory=dict)

    def document_id(self, source: str) -> str:
        return f"{self.date}__{source}"


def day_ledgers_um(by_day: dict[str, dict[str, int]]) -> list[DayLedger]:
    """Group {date: {app: um}} into ledgers, capping apps at 64 like the rules."""
    ledgers = []
    for day in sorted(by_day):
        apps = {k: v for k, v in by_day[day].items() if v > 0}
        ranked = sorted(apps.items(), key=lambda kv: (-kv[1], kv[0]))[:MAX_APP_KEYS]
        ledgers.append(DayLedger(date=day, um=sum(apps.values()), apps=dict(ranked)))
    return ledgers


def dirty_days(ledgers: list[DayLedger], pushed: dict[str, int]) -> list[DayLedger]:
    return [ledger for ledger in ledgers if pushed.get(ledger.date) != ledger.um]


def synced_ago(then, now_ms: int | None = None):
    import time
    if not then:
        return None
    now_ms = now_ms if now_ms is not None else int(time.time() * 1000)
    age = now_ms - int(then)
    minute, hour, day = 60_000, 3_600_000, 86_400_000
    if age < 2 * minute:
        return "just now"
    if age < hour:
        return f"{age // minute} min ago"
    if age < day:
        return f"{age // hour} h ago"
    if age < 2 * day:
        return "yesterday"
    return f"{age // day} days ago"
