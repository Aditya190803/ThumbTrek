"""Cairo chart widgets — geometry ported from app/.../ui/Charts.kt.

TrekGauge: open-bottom survey dial (start 130°, sweep 280°, 13 outside
ticks), per-app slices with 2.5° gaps, dashed unwalked-trail arc when empty.
HistoryBars: rounded columns on sunken tracks, slate, moss highlight, tap to
select. WeekPulse, TrendLines, ProgressRing, Rail likewise mirror their
Kotlin twins. No text is drawn here — axis labels are Gtk widgets.
"""

from __future__ import annotations

import math

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import Gtk  # noqa: E402

from .theme import series_color  # noqa: E402

GAUGE_START = 130.0
GAUGE_SWEEP = 280.0
GAUGE_TICKS = 12


def _rgb(hex_color: str) -> tuple[float, float, float]:
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) / 255.0 for i in (0, 2, 4))


def _set(cr, hex_color: str, alpha: float = 1.0) -> None:
    cr.set_source_rgba(*_rgb(hex_color), alpha)


def _round_rect(cr, x: float, y: float, w: float, h: float, r: float) -> None:
    r = min(r, w / 2.0, h / 2.0)
    cr.new_path()
    cr.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    cr.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    cr.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    cr.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    cr.close_path()


class Rail(Gtk.DrawingArea):
    """The one bar primitive: sunken track, filled portion, min cap radius."""

    def __init__(self, fraction: float = 0.0, color: str = "#1E6B3E",
                 track: str = "#E6E3D6", height: int = 6):
        super().__init__()
        self.fraction = fraction
        self.color = color
        self.track = track
        self.set_size_request(-1, height)
        self.set_draw_func(self._draw, None)

    def set(self, fraction: float, color: str | None = None) -> None:
        self.fraction = fraction
        if color is not None:
            self.color = color
        self.queue_draw()

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        shown = min(1.0, max(0.0, self.fraction))
        _set(cr, self.track)
        _round_rect(cr, 0, 0, width, height, height / 2.0)
        cr.fill()
        if shown > 0:
            filled = max(width * shown, height)
            _set(cr, self.color)
            _round_rect(cr, 0, 0, filled, height, height / 2.0)
            cr.fill()


class Gauge(Gtk.DrawingArea):
    """Segmented survey dial with the hero content in the middle (TrekGauge)."""

    def __init__(self, dark: bool = False):
        super().__init__()
        self.dark = dark
        self.slices: list[tuple[str, float]] = []  # (app key, value)
        self.set_size_request(320, 320)
        self.set_draw_func(self._draw, None)

    def set(self, slices: list[tuple[str, float]], dark: bool) -> None:
        self.slices = [(k, v) for k, v in slices if v > 0]
        self.dark = dark
        self.queue_draw()

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        from .theme import DARK, LIGHT
        p = DARK if self.dark else LIGHT
        drawn = self.slices
        total = sum(v for _, v in drawn)
        stroke = 15.0
        tick_len, tick_gap = stroke * 0.45, stroke * 0.75
        diameter = max(0.0, min(width, height) - stroke - (tick_len + tick_gap) * 2)
        radius = diameter / 2.0
        cx, cy = width / 2.0, height / 2.0

        tick_radius = radius + stroke / 2.0 + tick_gap
        _set(cr, p["hairline"])
        cr.set_line_width(1.5)
        cr.set_line_cap(1)  # round
        for i in range(GAUGE_TICKS + 1):
            angle = math.radians(GAUGE_START + GAUGE_SWEEP * i / GAUGE_TICKS)
            dx, dy = math.cos(angle), math.sin(angle)
            length = tick_len if i % 3 == 0 else tick_len * 0.55
            cr.move_to(cx + dx * tick_radius, cy + dy * tick_radius)
            cr.line_to(cx + dx * (tick_radius + length), cy + dy * (tick_radius + length))
            cr.stroke()

        start_rad = math.radians(GAUGE_START)
        sweep_rad = math.radians(GAUGE_SWEEP)
        if not drawn or total <= 0:
            _set(cr, p["hairline"])
            cr.set_line_width(stroke * 0.5)
            cr.set_line_cap(1)
            cr.set_dash([2.0, 7.0])
            cr.arc(cx, cy, radius, start_rad, start_rad + sweep_rad)
            cr.stroke()
            cr.set_dash([])
            return

        _set(cr, p["sunken"])
        cr.set_line_width(stroke)
        cr.set_line_cap(0)
        cr.arc(cx, cy, radius, start_rad, start_rad + sweep_rad)
        cr.stroke()

        gap = math.radians(2.5) if len(drawn) > 1 else 0.0
        angle = start_rad
        for index, (key, value) in enumerate(drawn):
            full = value / total * sweep_rad
            sweep = max(full - gap, math.radians(0.8))
            _set(cr, series_color(key, self.dark))
            cr.set_line_width(stroke)
            cr.set_line_cap(0 if len(drawn) > 1 else 1)  # butt, round when solo
            cr.arc(cx, cy, radius, angle + gap / 2.0, angle + gap / 2.0 + sweep)
            cr.stroke()
            angle += full


class HistoryBars(Gtk.DrawingArea):
    """Tappable history columns (HistoryBars): slate, moss highlight, ink pick."""

    def __init__(self, dark: bool = False, on_select=None):
        super().__init__()
        self.dark = dark
        self.values: list[float] = []
        self.highlight = -1
        self.selected = -1
        self.on_select = on_select
        self.set_size_request(-1, 132)
        self.set_draw_func(self._draw, None)
        gesture = Gtk.GestureClick()
        gesture.connect("pressed", self._pressed)
        self.add_controller(gesture)

    def set(self, values: list[float], highlight: int, selected: int, dark: bool) -> None:
        self.values, self.highlight, self.selected, self.dark = values, highlight, selected, dark
        self.queue_draw()

    def _pressed(self, _gesture, _n: int, x: float, _y: float) -> None:
        if not self.values or self.on_select is None:
            return
        width = self.get_allocated_width()
        index = min(int(x / width * len(self.values)), len(self.values) - 1)
        self.on_select(index)

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        from .theme import DARK, LIGHT
        p = DARK if self.dark else LIGHT
        if not self.values:
            return
        peak = max(max(self.values), 1e-9)
        slot = width / len(self.values)
        bar_w = min(max(slot * 0.54, 2.0), 22.0)
        has_selection = 0 <= self.selected < len(self.values)
        for i, value in enumerate(self.values):
            x = slot * i + (slot - bar_w) / 2.0
            _set(cr, p["sunken"])
            _round_rect(cr, x, 0, bar_w, height, bar_w / 2.0)
            cr.fill()
            if value <= 0:
                continue
            bar_h = min(max(value / peak * height, bar_w), height)
            if i == self.selected:
                _set(cr, p["ink"])
            elif has_selection:
                _set(cr, p["slate"], 0.4)
            elif i == self.highlight:
                _set(cr, p["moss"])
            else:
                _set(cr, p["slate"])
            _round_rect(cr, x, height - bar_h, bar_w, bar_h, bar_w / 2.0)
            cr.fill()


class WeekPulse(Gtk.DrawingArea):
    """Seven-column glance strip; today in moss (WeekPulse)."""

    def __init__(self, dark: bool = False):
        super().__init__()
        self.dark = dark
        self.values: list[float] = []
        self.set_size_request(-1, 34)
        self.set_draw_func(self._draw, None)

    def set(self, values: list[float], dark: bool) -> None:
        self.values, self.dark = values, dark
        self.queue_draw()

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        from .theme import DARK, LIGHT
        p = DARK if self.dark else LIGHT
        if not self.values:
            return
        peak = max(max(self.values), 1e-4)
        slot = width / len(self.values)
        bar_w = min(max(slot * 0.46, 2.0), 14.0)
        for i, value in enumerate(self.values):
            x = slot * i + (slot - bar_w) / 2.0
            _set(cr, p["sunken"])
            _round_rect(cr, x, 0, bar_w, height, bar_w / 2.0)
            cr.fill()
            if value <= 0:
                continue
            bar_h = min(max(value / peak * height, bar_w), height)
            _set(cr, p["moss"] if i == len(self.values) - 1 else p["slate"])
            _round_rect(cr, x, height - bar_h, bar_w, bar_h, bar_w / 2.0)
            cr.fill()


class TrendLines(Gtk.DrawingArea):
    """Per-app trend strokes with endpoint dots (TrendLines)."""

    def __init__(self, dark: bool = False):
        super().__init__()
        self.dark = dark
        self.series: list[tuple[str, list[float]]] = []
        self.set_size_request(-1, 128)
        self.set_draw_func(self._draw, None)

    def set(self, series: list[tuple[str, list[float]]], dark: bool) -> None:
        self.series = [(k, v) for k, v in series if v]
        self.dark = dark
        self.queue_draw()

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        from .theme import DARK, LIGHT
        p = DARK if self.dark else LIGHT
        drawn = self.series
        if not drawn:
            return
        points = max(len(v) for _, v in drawn)
        peak = max(max(v) for _, v in drawn + [(" ", [1.0])])
        inset, stroke = 4.0, 2.0
        plot_w = max(1.0, width - inset * 2)
        plot_h = max(1.0, height - inset * 2)
        step = plot_w / (points - 1) if points > 1 else 0.0
        _set(cr, p["hairline"])
        cr.set_line_width(1.0)
        cr.move_to(0, height - inset)
        cr.line_to(width, height - inset)
        cr.stroke()
        cr.set_line_width(stroke)
        cr.set_line_cap(1)
        cr.set_line_join(1)
        for key, values in drawn:
            _set(cr, series_color(key, self.dark))
            pts = [(inset + step * i if points > 1 else width / 2.0,
                    height - inset - min(max(v, 0.0), peak) / peak * plot_h)
                   for i, v in enumerate(values)]
            if len(pts) == 1:
                cr.arc(pts[0][0], pts[0][1], stroke * 1.6, 0, 2 * math.pi)
                cr.fill()
                continue
            cr.move_to(*pts[0])
            for pt in pts[1:]:
                cr.line_to(*pt)
            cr.stroke()
            cr.arc(pts[-1][0], pts[-1][1], stroke * 1.7, 0, 2 * math.pi)
            cr.fill()


class ProgressRing(Gtk.DrawingArea):
    """Achievement progress ring around a glyph (ProgressRing)."""

    def __init__(self, progress: float = 0.0, color: str = "#1E6B3E",
                 track: str = "#DCD8C8", dark: bool = False):
        super().__init__()
        self.progress = progress
        self.color = color
        self.track = track
        self.dark = dark
        self.set_size_request(42, 42)
        self.set_draw_func(self._draw, None)

    def _draw(self, _area, cr, width: int, height: int, _data) -> None:
        from .theme import DARK, LIGHT
        p = DARK if self.dark else LIGHT
        cx, cy = width / 2.0, height / 2.0
        radius = min(width, height) / 2.0 - 2.0
        _set(cr, p["hairline"])
        cr.set_line_width(2.5)
        cr.arc(cx, cy, radius, 0, 2 * math.pi)
        cr.stroke()
        shown = min(1.0, max(0.0, self.progress))
        if shown > 0:
            _set(cr, self.color)
            cr.set_line_width(2.5)
            cr.set_line_cap(1)
            cr.arc(cx, cy, radius, -math.pi / 2, -math.pi / 2 + shown * 2 * math.pi)
            cr.stroke()
