"""Stats engine — exact Python port of app/.../stats/Stats.kt.

Kept verbatim (landmark table, badge ladder, streak semantics, ISO week keys)
so the Linux client honors the badge-parity contract in the README.
Stdlib only: datetime (ISO calendar), no third-party deps.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, timedelta

DEFAULT_DAILY_LIMIT_M = 100.0
MIN_DAILY_LIMIT_M = 10.0
MAX_DAILY_LIMIT_M = 10_000.0
FREE_LIMIT_EDITS_PER_WEEK = 1
BILLING_ENFORCED = False
LIMIT_WARN_FRACTION = 0.8


def pixels_to_meters(pixels: int, density_dpi: int) -> float:
    return pixels / density_dpi * 0.0254


def format_distance(meters: float) -> str:
    if meters < 1000:
        return f"{round(meters)} m"
    km = f"{meters / 1000:.2f}".rstrip("0").rstrip(".")
    return f"{km} km"


def trek_streak(active_dates, today: date | None = None) -> int:
    days = set(active_dates)
    today = today or date.today()
    cursor = today if today in days else today - timedelta(days=1)
    streak = 0
    while cursor in days:
        streak += 1
        cursor -= timedelta(days=1)
    return streak


def is_clean_day(meters: float, limit_meters: float) -> bool:
    return limit_meters > 0 and meters <= limit_meters


def limit_streak(day_meters: dict, limit_meters: float,
                 today: date | None = None, first_day: date | None = None) -> int:
    if limit_meters <= 0:
        return 0
    today = today or date.today()
    if first_day is None and day_meters:
        first_day = min(day_meters)
    streak, cursor = 0, today
    while True:
        if first_day is not None and cursor < first_day:
            break
        if (day_meters.get(cursor, 0.0)) > limit_meters:
            break
        streak += 1
        if first_day is None:
            break
        cursor -= timedelta(days=1)
    return streak


def free_limit_edits_left(edit_week_key, edit_count: int,
                          today: date | None = None,
                          billing_enforced: bool = BILLING_ENFORCED):
    if not billing_enforced:
        return 2**31 - 1
    if edit_week_key != week_key(today or date.today()):
        return FREE_LIMIT_EDITS_PER_WEEK
    return max(0, FREE_LIMIT_EDITS_PER_WEEK - edit_count)


def limit_nudge_level(today_meters: float, limit_meters: float) -> int:
    if limit_meters <= 0 or today_meters <= 0:
        return 0
    if today_meters > limit_meters:
        return 2
    if today_meters >= limit_meters * LIMIT_WARN_FRACTION:
        return 1
    return 0


def _monday(today: date) -> date:
    return today - timedelta(days=today.weekday())


def total_this_week(days: dict, today: date | None = None) -> int:
    today = today or date.today()
    monday = _monday(today)
    return sum(v for d, v in days.items() if monday <= d <= today)


def total_this_month(days: dict, today: date | None = None) -> int:
    today = today or date.today()
    return sum(v for d, v in days.items() if (d.year, d.month) == (today.year, today.month))


def week_key(day: date | None = None) -> str:
    day = day or date.today()
    year, week, _ = day.isocalendar()
    return f"{year}-W{week:02d}"


def month_key(day: date | None = None) -> str:
    day = day or date.today()
    return f"{day.year}-{day.month:02d}"


def personal_record(days: dict):
    if not days:
        return None
    best = max(days, key=lambda d: days[d])
    return best, days[best]


@dataclass
class Bucket:
    key: str
    label: str
    pixels: int


def _day_label(day: date) -> str:
    return day.strftime("%b %-d")


def daily_buckets(days: dict, day_count: int = 7, today: date | None = None):
    today = today or date.today()
    if day_count <= 0:
        return []
    return [Bucket(key=str(today - timedelta(days=i)), label=_day_label(today - timedelta(days=i)),
                   pixels=days.get(today - timedelta(days=i), 0))
            for i in range(day_count - 1, -1, -1)]


def weekly_buckets(days: dict, week_count: int = 8, today: date | None = None):
    today = today or date.today()
    if week_count <= 0:
        return []
    monday = _monday(today)
    out = []
    for back in range(week_count - 1, -1, -1):
        start = monday - timedelta(weeks=back)
        end = start + timedelta(days=6)
        total = sum(v for d, v in days.items() if start <= d <= end)
        out.append(Bucket(key=week_key(start), label=_day_label(start), pixels=total))
    return out


def monthly_buckets(days: dict, month_count: int = 6, today: date | None = None):
    today = today or date.today()
    if month_count <= 0:
        return []
    out = []
    y, m = today.year, today.month
    months = []
    for _ in range(month_count):
        months.append((y, m))
        m -= 1
        if m == 0:
            m, y = 12, y - 1
    for y, m in reversed(months):
        total = sum(v for d, v in days.items() if (d.year, d.month) == (y, m))
        label = date(y, m, 1).strftime("%b")
        out.append(Bucket(key=f"{y}-{m:02d}", label=label, pixels=total))
    return out


def percent_delta(current: int, previous: int):
    if previous <= 0:
        return None
    return round((current - previous) * 100.0 / previous)


def day_over_day_delta(days: dict, today: date | None = None):
    today = today or date.today()
    return percent_delta(days.get(today, 0), days.get(today - timedelta(days=1), 0))


def week_over_week_delta(days: dict, today: date | None = None):
    today = today or date.today()
    monday = _monday(today)
    cur = sum(v for d, v in days.items() if monday <= d <= today)
    prev = sum(v for d, v in days.items()
               if monday - timedelta(weeks=1) <= d <= today - timedelta(weeks=1))
    return percent_delta(cur, prev)


@dataclass
class Landmark:
    meters: float
    label: str


LANDMARKS = [
    Landmark(0.18, "a banana"),
    Landmark(0.3, "a sneaker"),
    Landmark(1.0, "a doorway"),
    Landmark(1.8, "a queen-size bed"),
    Landmark(4.5, "a parked car"),
    Landmark(12.0, "a school bus"),
    Landmark(19.0, "a bowling lane"),
    Landmark(28.0, "a basketball court"),
    Landmark(93.0, "the Statue of Liberty"),
    Landmark(105.0, "a football pitch"),
    Landmark(269.0, "the Titanic"),
    Landmark(330.0, "the Eiffel Tower"),
    Landmark(830.0, "the Burj Khalifa"),
    Landmark(1_609.0, "a mile"),
    Landmark(2_737.0, "the Golden Gate Bridge"),
    Landmark(5_000.0, "a 5K run"),
    Landmark(8_849.0, "Mount Everest"),
    Landmark(21_097.0, "a half marathon"),
    Landmark(42_195.0, "a marathon"),
    Landmark(100_000.0, "a 100K ultramarathon"),
    Landmark(384_400_000.0, "the Moon"),
]


def comparison(meters: float) -> str:
    if meters <= 0.0:
        return "No trek yet — go scroll something."
    landmark = LANDMARKS[0]
    for candidate in LANDMARKS:
        if meters >= candidate.meters:
            landmark = candidate
    return f"That's {meters / landmark.meters:.1f}× {landmark.label}."


@dataclass
class Badge:
    id: str
    emoji: str
    label: str
    detail: str
    earned: bool
    progress: float


def _badge(id_, emoji, label, value, target, detail) -> Badge:
    progress = 1.0 if target <= 0 else min(1.0, max(0.0, value / target))
    return Badge(id_, emoji, label, detail, progress >= 1.0, progress)


def badges(total_meters: float, best_day_meters: float, streak: int, clean_streak: int = 0):
    return [
        _badge("first_trek", "👣", "First steps", total_meters, 1.0, "Complete your first trek"),
        _badge("banana", "🍌", "Banana", total_meters, 100.0, "Trek 100 m in total"),
        _badge("kilometer", "🎯", "Kilometer club", total_meters, 1_000.0, "Trek 1 km in total"),
        _badge("bridge", "🌉", "Golden Gate", total_meters, 2_737.0, "Trek 2.7 km in total"),
        _badge("fivek", "🏅", "5K", total_meters, 5_000.0, "Trek 5 km in total"),
        _badge("burj_day", "🏙️", "Burj day", best_day_meters, 830.0,
               "Trek 830 m — the Burj Khalifa — in a single day"),
        _badge("tenk", "🥉", "10K", total_meters, 10_000.0, "Trek 10 km in total"),
        _badge("half_marathon", "🥈", "Half marathon", total_meters, 21_097.0, "Trek 21.1 km in total"),
        _badge("everest", "🏔️", "Everest", best_day_meters, 8_849.0, "Trek 8.8 km in a single day"),
        _badge("marathon", "🏃", "Marathon thumb", total_meters, 42_195.0, "Trek 42.2 km in total"),
        _badge("ultra", "🥇", "Ultra", total_meters, 50_000.0, "Trek 50 km in total"),
        _badge("century", "💯", "Century", total_meters, 100_000.0, "Trek 100 km in total"),
        _badge("quarter_million", "🚀", "Quarter-million", total_meters, 250_000.0, "Trek 250 km in total"),
        _badge("half_million", "⭐", "Half-million", total_meters, 500_000.0, "Trek 500 km in total"),
        _badge("million", "🌟", "Thousand-K club", total_meters, 1_000_000.0, "Trek 1,000 km in total"),
        _badge("streak_3", "🔥", "Warm-up", float(streak), 3.0, "Keep a 3-day streak"),
        _badge("streak_7", "📅", "Week trekker", float(streak), 7.0, "Keep a 7-day streak"),
        _badge("streak_14", "🌱", "Two-week habit", float(streak), 14.0, "Keep a 14-day streak"),
        _badge("streak_30", "🗓️", "Monthly mover", float(streak), 30.0, "Keep a 30-day streak"),
        _badge("streak_100", "💎", "Centurion streak", float(streak), 100.0, "Keep a 100-day streak"),
        _badge("streak_365", "👑", "Year-round trekker", float(streak), 365.0, "Keep a 365-day streak"),
        _badge("clean_3", "🧼", "Clean slate", float(clean_streak), 3.0,
               "Stay under your limit 3 days in a row"),
        _badge("clean_7", "🛡️", "Under control", float(clean_streak), 7.0,
               "Stay under your limit 7 days in a row"),
        _badge("clean_14", "🧘", "Steady mind", float(clean_streak), 14.0,
               "Stay under your limit 14 days in a row"),
        _badge("clean_30", "🏵️", "Month of restraint", float(clean_streak), 30.0,
               "Stay under your limit 30 days in a row"),
    ]
