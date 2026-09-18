"""View-model: pure data-prep mirroring the Android Dashboard + History tabs.

All GTK-free so it is unit-testable headless. The native window (ui.py)
renders these structures and nothing else — no arithmetic lives in widgets.
"""

from __future__ import annotations

import calendar as _calendar
from dataclasses import dataclass, field
from datetime import date, timedelta

from . import stats

M_PER_PX = 0.0254 / 96.0  # same 96-dpi basis as the browser client
#: History ranges, verbatim labels from Screens.kt RANGES.
HISTORY_RANGES = ("7 days", "8 weeks", "6 months")


def parse_day(value: str) -> date:
    y, m, d = (int(p) for p in value.split("-"))
    return date(y, m, d)


@dataclass
class Dashboard:
    today: date
    today_m: float
    limit_m: float
    over_limit: bool
    nudge_level: int
    streak: int
    clean_streak: int
    total_m: float
    week_m: float
    month_m: float
    best_day: date | None
    best_day_m: float
    landmark_line: str
    week_rows: list = field(default_factory=list)  # (label, metres, is_today)
    app_split: list = field(default_factory=list)  # (app, metres), today, desc
    day_m: dict = field(default_factory=dict)
    first_day: date | None = None


def dashboard(store, cfg: dict, today: date | None = None) -> Dashboard:
    today = today or date.today()
    days_px = {parse_day(d): px for d, px in store.days().items()}
    day_m = {d: px * M_PER_PX for d, px in days_px.items()}
    first_day = min(day_m) if day_m else None
    limit = float(cfg.get("daily_limit_m", 100.0))
    today_m = day_m.get(today, 0.0)
    split = store.day_split(today.isoformat())
    trailing = [(today - timedelta(days=i)) for i in range(6, -1, -1)]
    week_rows = [(d.strftime("%b %-d"), day_m.get(d, 0.0), d == today) for d in trailing]
    best = stats.personal_record(days_px)
    return Dashboard(
        today=today, today_m=today_m, limit_m=limit,
        over_limit=limit > 0 and today_m > limit,
        nudge_level=stats.limit_nudge_level(today_m, limit),
        streak=stats.trek_streak([d for d, px in days_px.items() if px > 0], today),
        clean_streak=stats.limit_streak(day_m, limit, today, first_day),
        total_m=sum(day_m.values()),
        week_m=stats.total_this_week(days_px, today) * M_PER_PX,
        month_m=stats.total_this_month(days_px, today) * M_PER_PX,
        best_day=best[0] if best else None,
        best_day_m=best[1] * M_PER_PX if best else 0.0,
        landmark_line=stats.comparison(today_m),
        week_rows=week_rows,
        app_split=[(app, px * M_PER_PX)
                   for app, px in sorted(split.items(), key=lambda kv: -kv[1])],
        day_m=day_m, first_day=first_day)


@dataclass
class History:
    index: int = 0
    buckets: list = field(default_factory=list)  # (key, label, metres)
    period_m: float = 0.0
    day_delta: int | None = None
    week_delta: int | None = None


def history(days_px: dict, index: int, today: date) -> History:
    if index not in (0, 1, 2):
        index = 0
    if index == 1:
        raw = stats.weekly_buckets(days_px, 8, today)
    elif index == 2:
        raw = stats.monthly_buckets(days_px, 6, today)
    else:
        raw = stats.daily_buckets(days_px, 7, today)
    buckets = [(b.key, b.label, b.pixels * M_PER_PX) for b in raw]
    return History(
        index=index, buckets=buckets, period_m=sum(m for _, _, m in buckets),
        day_delta=stats.day_over_day_delta(days_px, today) if index == 0 else None,
        week_delta=stats.week_over_week_delta(days_px, today) if index == 0 else None)


@dataclass
class TrendPoint:
    key: str
    label: str
    metres: float


@dataclass
class AppTrend:
    package: str
    points: list = field(default_factory=list)  # TrendPoint, 14 daily slots
    total_m: float = 0.0


def app_series(store, today: date, days: int = 14) -> list[AppTrend]:
    """Per-app daily series over the trends window, busiest first (Stats.kt)."""
    window = [today - timedelta(days=i) for i in range(days - 1, -1, -1)]
    slots = {d.isoformat(): i for i, d in enumerate(window)}
    labels = [d.strftime("%b %-d") for d in window]
    by_app: dict[str, list[float]] = {}
    for pkg, day_str, px in store.all_rows():
        slot = slots.get(day_str)
        if slot is None:
            continue
        by_app.setdefault(pkg, [0.0] * days)[slot] += px * M_PER_PX
    trends = [AppTrend(pkg, [TrendPoint(window[i].isoformat(), labels[i], vals[i])
                             for i in range(days)], sum(vals))
              for pkg, vals in by_app.items() if sum(vals) > 0]
    return sorted(trends, key=lambda t: (-t.total_m, t.package))


def best_week(day_m: dict, today: date) -> tuple[str, float]:
    """Highest 7-day-bucket week in the last 52 ISO weeks + its metres."""
    weeks = stats.weekly_buckets(
        {d: int(m / M_PER_PX) for d, m in day_m.items()}, 52, today)
    best = max(weeks, key=lambda b: b.pixels, default=None)
    if best is None or best.pixels <= 0:
        return "", 0.0
    return best.label, best.pixels * M_PER_PX


def month_grid(day_m: dict, first_day: date | None, limit: float, today: date
               ) -> list[list[tuple[int | None, str]]]:
    """Weeks of (day number|None, state): clean|over|pred|future|blank."""
    weeks = []
    for week in _calendar.monthcalendar(today.year, today.month):
        row = []
        for day_no in week:
            if day_no == 0:
                row.append((None, "blank"))
                continue
            day = date(today.year, today.month, day_no)
            if day > today:
                state = "future"
            elif first_day is None or day < first_day:
                state = "pred"
            else:
                state = "clean" if stats.is_clean_day(day_m.get(day, 0.0), limit) else "over"
            row.append((day_no, state))
        weeks.append(row)
    return weeks


def app_trends(store, today: date, days: int = 14, top: int = 5) -> list[tuple[str, float]]:
    totals: dict[str, int] = {}
    for pkg, day_str, px in store.all_rows():
        if (today - parse_day(day_str)).days < days:
            totals[pkg] = totals.get(pkg, 0) + px
    return [(pkg, px * M_PER_PX)
            for pkg, px in sorted(totals.items(), key=lambda kv: -kv[1])[:top]]


def recent_days(day_m: dict, count: int = 10) -> list[tuple[date, float]]:
    return [(d, m) for d, m in sorted(day_m.items(), reverse=True)[:count] if m > 0]


def feed_day(store, today: date) -> dict[str, float]:
    """Today's metres per social feed (all four, zero-filled), aggregating
    the extension's per-domain ledger. Unlinked browser time stays under the
    browser's own bucket — it cannot be attributed to a feed."""
    from .feeds import FEEDS, feed_name
    totals = {feed: 0.0 for feed, _, _ in FEEDS}
    for app, px in store.day_split(today.isoformat()).items():
        name = feed_name(app)
        if name in totals:
            totals[name] += px * M_PER_PX
    return totals
