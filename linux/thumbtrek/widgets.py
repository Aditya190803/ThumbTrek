"""Trek design-system widgets for GTK — ports of ui/Surfaces.kt.

Bordered panels (never shadowed), overline section heads with hairline
rules, the one Rail primitive, MeasureRow workhorse rows, chips, streak
badge, flat buttons, sliding segmented control, ledger lines, checkpoint
tiles. All labels are plain text: hostile app names stay inert.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")

from gi.repository import Gtk  # noqa: E402

from . import stats  # noqa: E402
from .charts import ProgressRing, Rail  # noqa: E402


def label(text: str, cls: str, xalign: float = 0.0, wrap: bool = True) -> Gtk.Label:
    widget = Gtk.Label(label=text, xalign=xalign)
    widget.set_wrap(wrap)
    for token in cls.split():
        widget.add_css_class(token)
    return widget


def overline(text: str, moss: bool = False) -> Gtk.Label:
    return label(text.upper(), "trek-overline-moss" if moss else "trek-overline")


def trek_panel(*children: Gtk.Widget, amber: bool = False) -> Gtk.Box:
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
    box.add_css_class("trek-amber-wash" if amber else "trek-panel")
    for child in children:
        box.append(child)
    return box


def section_head(text: str, trailing: str | None = None) -> Gtk.Box:
    row = Gtk.Box(spacing=12)
    row.append(overline(text))
    rule = Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL, hexpand=True,
                         valign=Gtk.Align.CENTER)
    row.append(rule)
    if trailing is not None:
        row.append(overline(trailing))
    return row


def hairline() -> Gtk.Separator:
    return Gtk.Separator(orientation=Gtk.Orientation.HORIZONTAL)


class Dot(Gtk.DrawingArea):
    """Small round colour key; re-resolves theme tokens on every draw."""

    def __init__(self, hex_color: str, size: int = 9):
        super().__init__()
        self.hex_color = hex_color
        self.set_size_request(size, size)
        self.set_draw_func(self._draw, None)

    def _draw(self, _a, cr, w: int, h: int, _d) -> None:
        from .charts import _rgb
        cr.set_source_rgb(*_rgb(self.hex_color))
        cr.arc(w / 2.0, h / 2.0, min(w, h) / 2.0, 0, 6.2832)
        cr.fill()


def series_dot(hex_color: str) -> Dot:
    return Dot(hex_color)


def live_dot(live: bool, dark: bool) -> Dot:
    from .theme import DARK, LIGHT
    pal = DARK if dark else LIGHT
    return Dot(pal["moss"] if live else pal["ink_faint"], size=7)


def measure_row(text_label: str, value: str, fraction: float, color: str,
                dark: bool, label_muted: bool = False) -> Gtk.Box:
    """Labelled figure over a proportional rail (MeasureRow)."""
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
    row = Gtk.Box(spacing=10)
    name = label(text_label, "trek-body-muted" if label_muted else "trek-body")
    name.set_hexpand(True)
    name.set_ellipsize(3)  # end ellipsis, like TextOverflow.Ellipsis
    row.append(name)
    row.append(label(value, "trek-figure", xalign=1.0, wrap=False))
    box.append(row)
    from .theme import DARK, LIGHT
    pal = DARK if dark else LIGHT
    box.append(Rail(fraction, color=color, track=pal["sunken"], height=5))
    return box


def trek_chip(text: str) -> Gtk.Box:
    box = Gtk.Box()
    box.add_css_class("trek-chip")
    box.append(label(text, "trek-small-muted"))
    return box


def streak_badge(streak: int, dark: bool) -> Gtk.Box:
    """Amber heat while alive, quiet invitation at zero (StreakBadge)."""
    box = Gtk.Box(spacing=8)
    box.append(live_dot(streak > 0, dark))
    text = f"{streak} day streak" if streak > 0 else "No streak yet"
    box.append(label(text, "trek-small-muted"))
    box.add_css_class("trek-streak" if streak > 0 else "trek-streak-idle")
    return box


def trek_button(text: str, sensitive: bool = True) -> Gtk.Button:
    button = Gtk.Button(label=text)
    button.add_css_class("trek-btn")
    button.set_sensitive(sensitive)
    return button


def trek_ghost(text: str) -> Gtk.Button:
    button = Gtk.Button(label=text)
    button.add_css_class("trek-ghost")
    button.set_hexpand(True)
    return button


def trek_segmented(options: list[str], selected: int, on_select) -> Gtk.Box:
    """Sunken slot box with a raised active slot (TrekSegmented)."""
    box = Gtk.Box(spacing=0, homogeneous=True)
    box.add_css_class("trek-seg")
    for index, name in enumerate(options):
        toggle = Gtk.ToggleButton(label=name, active=(index == selected))
        toggle.set_hexpand(True)
        toggle.connect("toggled", lambda btn, i=index: on_select(i) if btn.get_active() else None)
        box.append(toggle)
    return box


def delta_mark(percent: int | None, suffix: str) -> Gtk.Label | None:
    """Percentage delta, coloured only by direction (DeltaMark)."""
    if percent is None:
        return None
    text = f"{'+' if percent >= 0 else '-'}{abs(percent)}% {suffix}"
    widget = label(text, "trek-small-muted")
    widget.add_css_class("trek-amber-text" if percent >= 0 else "trek-slate-text")
    return widget


def ledger_line(text_label: str, value: str, caption: str, value_cls: str | None) -> Gtk.Box:
    """A record: label left, figure right, the why underneath (LedgerLine)."""
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
    row = Gtk.Box()
    row.append(label(text_label, "trek-body"))
    row.append(Gtk.Box(hexpand=True))
    row.append(label(value, f"trek-head {value_cls}" if value_cls else "trek-head",
                     xalign=1.0, wrap=False))
    box.append(row)
    box.append(label(caption, "trek-small"))
    return box


def checkpoint_tile(badge: stats.Badge, dark: bool) -> Gtk.Box:
    """92dp shelf tile: progress ring around the glyph (CheckpointTile)."""
    from .theme import DARK, LIGHT
    pal = DARK if dark else LIGHT
    tile = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
    tile.set_size_request(92, -1)
    tile.add_css_class("trek-tile-earned" if badge.earned else "trek-tile")
    ring_box = Gtk.Box(halign=Gtk.Align.CENTER)
    ring = ProgressRing(progress=1.0 if badge.earned else badge.progress,
                        color=pal["moss"] if badge.earned else pal["slate"], dark=dark)
    overlay = Gtk.Overlay()
    overlay.set_child(ring)
    glyph = label(badge.emoji, "trek-title", xalign=0.5)
    overlay.add_overlay(glyph)
    glyph.set_halign(Gtk.Align.CENTER)
    glyph.set_valign(Gtk.Align.CENTER)
    ring_box.append(overlay)
    tile.append(ring_box)
    name = label(badge.label, "trek-small-muted", xalign=0.5)
    name.set_ellipsize(3)
    name.set_max_width_chars(10)
    tile.append(name)
    return tile


def status_line(live: bool, live_text: str, idle_text: str, dark: bool) -> Gtk.Box:
    """Live/idle dot indicator (StatusLine)."""
    row = Gtk.Box(spacing=10)
    dot = live_dot(live, dark)
    dot.set_size_request(8, 8)
    row.append(dot)
    row.append(label(live_text if live else idle_text, "trek-head"))
    return row


def toggle_row(title: str, subtitle: str | None, active: bool, on_toggle,
               dot_color: str | None = None, suffix: Gtk.Widget | None = None) -> Gtk.Box:
    """A setting whose whole row is the target (ToggleRow)."""
    row = Gtk.Box(spacing=12)
    row.set_size_request(-1, 56)
    if dot_color is not None:
        row.append(series_dot(dot_color))
    text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
    name = label(title, "trek-body")
    name.set_ellipsize(3)
    text.append(name)
    if subtitle is not None:
        sub = label(subtitle, "trek-small")
        sub.set_ellipsize(3)
        text.append(sub)
    row.append(text)
    if suffix is not None:
        row.append(suffix)
    switch = Gtk.Switch(active=active, valign=Gtk.Align.CENTER)
    switch.connect("notify::active", lambda sw, _p: on_toggle(sw.get_active()))
    row.append(switch)
    return row


def action_row(title: str, subtitle: str | None) -> Gtk.Box:
    row = Gtk.Box(spacing=12)
    row.set_size_request(-1, 56)
    text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
    text.append(label(title, "trek-body"))
    if subtitle is not None:
        text.append(label(subtitle, "trek-small"))
    row.append(text)
    return row


def consent_line(title: str, detail: str, dark: bool) -> Gtk.Box:
    from .theme import DARK, LIGHT
    pal = DARK if dark else LIGHT
    row = Gtk.Box(spacing=12)
    dot = series_dot(pal["moss"])
    dot.set_size_request(5, 5)
    dot.set_valign(Gtk.Align.START)
    dot.set_margin_top(7)
    row.append(dot)
    text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
    text.append(label(title, "trek-label"))
    text.append(label(detail, "trek-small"))
    row.append(text)
    return row


def calendar_key(hex_color: str, text: str) -> Gtk.Box:
    row = Gtk.Box(spacing=6)
    row.append(series_dot(hex_color))
    row.append(label(text, "trek-small-muted"))
    return row


def empty_state(title: str, body: str) -> Gtk.Box:
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
    box.append(label(title, "trek-title"))
    box.append(label(body, "trek-body-muted"))
    return box


def hero_distance(meters: float) -> Gtk.Box:
    """Dashboard readout: giant figure, quieter unit (HeroDistance)."""
    text = stats.format_distance(meters)
    cut = text.rfind(" ")
    figure, unit = (text, "") if cut < 0 else (text[:cut], text[cut + 1:])
    row = Gtk.Box(spacing=6)
    row.append(label(figure, "trek-hero" if len(figure) <= 4 else "trek-hero-tight",
                     xalign=1.0, wrap=False))
    if unit:
        unit_label = label(unit, "trek-unit", xalign=0.0, wrap=False)
        unit_label.set_valign(Gtk.Align.END)
        unit_label.set_margin_bottom(14 if len(figure) <= 4 else 10)
        row.append(unit_label)
    return row
