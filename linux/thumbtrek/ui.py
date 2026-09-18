"""Native window: faithful replica of the Android shell, adapted for desktop.

Same four tabs in the same order (Trek, History, Social, Settings), same
section heads, same copy, same charts — Trek gauge geometry, slate bars with
a moss today, per-app series ramp — on bone-paper/dark-field Trek tokens
with Space Grotesk display + Manrope body. Desktop adaptations are strictly
the hardware-bound ones: the Accessibility toggle becomes the tracker's
app switches, "phone" reads "computer", updates ride the package manager,
and sign-in is signposted as pending. All numbers come from viewmodel.py.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from . import config as config_mod  # noqa: E402
from . import ext_install  # noqa: E402
from . import feeds as feeds_mod  # noqa: E402
from . import stats  # noqa: E402
from . import viewmodel as vm  # noqa: E402
from .browsers import detect  # noqa: E402
from .charts import Gauge, HistoryBars, TrendLines, WeekPulse  # noqa: E402
from .links import fresh_links  # noqa: E402
from .store import Store  # noqa: E402
from .theme import (DARK, LIGHT, ensure_fonts, series_color)  # noqa: E402
from .version import __version__  # noqa: E402
from . import widgets as W  # noqa: E402

APP_ID = "dev.thumbtrek.app"
GUTTER = 20

TABS = (("trek", "Trek", "go-home-symbolic"),
        ("history", "History", "x-office-calendar-symbolic"),
        ("social", "Social", "system-users-symbolic"),
        ("settings", "Settings", "emblem-system-symbolic"))


def _is_dark() -> bool:
    return Adw.StyleManager.get_default().get_dark()


class TopRail(Gtk.Box):
    """Wordmark + date left, context action right (TrekTopRail)."""

    def __init__(self):
        super().__init__(spacing=10)
        self.set_margin_start(GUTTER)
        self.set_margin_end(10)
        self.set_margin_top(12)
        self.set_margin_bottom(10)
        left = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        left.append(W.overline("THUMBTREK", moss=True))
        import datetime
        today = datetime.date.today().strftime("%a %-d %b").upper()
        left.append(W.label(today, "trek-small"))
        self.append(left)
        self.share = W.trek_ghost("Share")
        self.share.set_hexpand(False)
        self.share.set_tooltip_text("Share today's trek")
        self.share.set_visible(False)
        self.append(self.share)


class BlazeNav(Gtk.Box):
    """Bottom navigation with a sliding trail blaze (TrekNavBar)."""

    def __init__(self, on_select):
        super().__init__(orientation=Gtk.Orientation.VERTICAL)
        self.add_css_class("trek-nav")
        self.on_select = on_select
        self.selected = 0
        self.blaze_x = 0.0
        self.blaze_target = 0.0
        self._timer = None
        self.blaze = Gtk.DrawingArea()
        self.blaze.set_size_request(-1, 2)
        self.blaze.set_draw_func(self._draw_blaze, None)
        self.append(self.blaze)
        row = Gtk.Box(homogeneous=True)
        self.buttons: list[Gtk.ToggleButton] = []
        for index, (_name, title, icon) in enumerate(TABS):
            button = Gtk.ToggleButton(active=(index == 0))
            inner = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
            inner.set_halign(Gtk.Align.CENTER)
            image = Gtk.Image.new_from_icon_name(icon)
            inner.append(image)
            inner.append(W.label(title, "trek-small"))
            button.set_child(inner)
            button.connect("toggled", self._picked, index)
            self.buttons.append(button)
            row.append(button)
        self.append(row)

    def _picked(self, button: Gtk.ToggleButton, index: int) -> None:
        if not button.get_active() or index == self.selected:
            if index != self.selected:
                button.set_active(False)
            return
        self.selected = index
        for i, other in enumerate(self.buttons):
            if i != index and other.get_active():
                other.set_active(False)
        width = self.get_allocated_width()
        self.blaze_target = width / 4 * index + width / 4 * 0.33 if width > 0 else 0.0
        if self._timer is None:
            self._timer = GLib.timeout_add(16, self._step)
        self.on_select(index)

    def _step(self) -> bool:
        diff = self.blaze_target - self.blaze_x
        if abs(diff) < 1.0:
            self.blaze_x = self.blaze_target
            self.blaze.queue_draw()
            self._timer = None
            return False
        self.blaze_x += diff * 0.25
        self.blaze.queue_draw()
        return True

    def _draw_blaze(self, _area, cr, width: int, _height: int, _data) -> None:
        from .charts import _rgb
        dark = _is_dark()
        pal = DARK if dark else LIGHT
        if width > 0 and self.blaze_target == 0.0 and self.blaze_x == 0.0:
            self.blaze_target = width / 4 * self.selected + width / 4 * 0.33
            self.blaze_x = self.blaze_target
        cr.set_source_rgb(*_rgb(pal["moss"]))
        cr.rectangle(self.blaze_x, 0, width / 4 * 0.34 if width else 0, 2)
        cr.fill()


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application):
        super().__init__(application=app, title="ThumbTrek",
                         default_width=1000, default_height=760)
        self.cfg = config_mod.load()
        self.store = Store()
        self.dark = _is_dark()
        self.hist_range = 0
        self.hist_sel: dict[int, int] = {}
        self.cal_day = None
        self.toasts = Adw.ToastOverlay()
        shell = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.rail = TopRail()
        self.rail.share.connect("clicked", self._on_share)
        shell.append(self.rail)
        self.stack = Gtk.Stack(hexpand=True, vexpand=True)
        self.pages = {}
        for name, _title, _icon in TABS:
            scrolled = Gtk.ScrolledWindow(hexpand=True, vexpand=True)
            self.pages[name] = scrolled
            self.stack.add_named(scrolled, name)
        shell.append(self.stack)
        self.nav = BlazeNav(self._on_tab)
        shell.append(self.nav)
        self.toasts.set_child(shell)
        self.set_content(self.toasts)
        self.refresh()
        GLib.timeout_add_seconds(30, self._auto_refresh)

    # -- plumbing ------------------------------------------------------
    def _auto_refresh(self) -> bool:
        self.refresh()
        return True

    def _on_tab(self, index: int) -> None:
        name = TABS[index][0]
        self.stack.set_visible_child_name(name)
        self.rail.share.set_visible(index == 0)
        self.refresh()

    def _toast(self, text: str) -> None:
        self.toasts.add_toast(Adw.Toast(title=text))

    def refresh(self) -> None:
        self.cfg = config_mod.load()
        self.dark = _is_dark()
        data = vm.dashboard(self.store, self.cfg)
        self._data = data
        self._fill(self.pages["trek"], self._trek_cards(data))
        self._fill(self.pages["history"], self._history_cards(data))
        self._fill(self.pages["social"], self._social_cards())
        self._fill(self.pages["settings"], self._settings_cards())

    @staticmethod
    def _fill(scrolled: Gtk.ScrolledWindow, cards: list[Gtk.Widget]) -> None:
        clamp = Adw.Clamp(maximum_size=860)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=28,
                      margin_top=6, margin_bottom=28, margin_start=GUTTER, margin_end=GUTTER)
        for card in cards:
            box.append(card)
        clamp.set_child(box)
        scrolled.set_child(clamp)

    def _tracked(self) -> list[str]:
        return list(self.cfg.get("tracked_apps", [])) + list(self.cfg.get("custom_apps", []))

    def _tracking_on(self) -> bool:
        return bool(self._tracked())

    # -- Trek tab (Dashboard) -------------------------------------------
    def _trek_cards(self, data: vm.Dashboard) -> list[Gtk.Widget]:
        cards: list[Gtk.Widget] = []
        if not self._tracking_on():
            body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
            body.append(W.label("Tracking is paused", "trek-head"))
            body.append(W.label(
                "Nothing is being measured. Switch an app on in Settings to start "
                "counting how far you scroll.", "trek-body-muted"))
            go = W.trek_button("Turn on tracking")
            go.connect("clicked", lambda _b: self._goto_settings())
            btn_row = Gtk.Box()
            btn_row.append(go)
            body.append(btn_row)
            cards.append(W.trek_panel(body, amber=True))

        gauge = Gauge(dark=self.dark)
        split = self.store.day_split(data.today.isoformat())
        gauge.set([(app, px * vm.M_PER_PX) for app, px in split.items()], self.dark)
        hero = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        hero.set_halign(Gtk.Align.CENTER)
        hero.set_valign(Gtk.Align.CENTER)
        hero.append(W.overline("TODAY'S TREK"))
        hero.append(W.hero_distance(data.today_m))
        if data.today_m > 0:
            delta = stats.day_over_day_delta(
                {d: int(m / vm.M_PER_PX) for d, m in data.day_m.items()}, data.today)
            mark = W.delta_mark(delta, "on yesterday")
            if mark is not None:
                mark.set_halign(Gtk.Align.CENTER)
                hero.append(mark)
        # The readout sits inside the instrument that measured it (HeroDial):
        # dial as the base layer, hero content centered over it.
        dial = Gtk.Overlay()
        dial.set_halign(Gtk.Align.CENTER)
        dial.set_child(gauge)
        dial.add_overlay(hero)
        gauge_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        gauge_box.set_halign(Gtk.Align.CENTER)
        gauge_box.append(dial)
        badge = W.streak_badge(data.streak, self.dark)
        badge.set_halign(Gtk.Align.CENTER)
        badge.set_margin_top(-14)
        gauge_box.append(badge)
        hero_wrap = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        hero_wrap.append(gauge_box)
        cards.append(hero_wrap)

        limit = max(data.limit_m, 1.0)
        clean = data.today_m <= data.limit_m and data.limit_m > 0
        cards.append(self._section("Daily limit",
                                   f"{data.clean_streak} CLEAN" if data.clean_streak > 0 else None,
                       self._limit_panel(data, limit, clean)))
        if data.today_m > 0:
            cards.append(self._landmark(data))
        if data.day_m:
            cards.append(self._week_glance(data))
        if data.app_split:
            rows = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
            busiest = max(px for _, px in
                          [(a, self.store.day_split(data.today.isoformat()).get(a, 0))
                           for a, _ in data.app_split]) or 1
            for app, metres in data.app_split:
                px = self.store.day_split(data.today.isoformat()).get(app, 0)
                rows.append(W.measure_row(feeds_mod.feed_name(app),
                                          stats.format_distance(metres),
                                          px / busiest, series_color(app, self.dark), self.dark))
            cards.append(self._section("Where it went", f"{len(data.app_split)} APPS", rows))
        if not data.day_m or not self._tracking_on() or data.streak < 2:
            cards.append(self._checklist(data))
        cards.append(W.label(
            "Distance only. ThumbTrek never reads what is on your screen, and nothing "
            "leaves this computer until you opt into a leaderboard.", "trek-small"))
        return cards

    def _goto_settings(self) -> None:
        self.nav.buttons[3].set_active(True)

    @staticmethod
    def _section(text: str, trailing: str | None, child: Gtk.Widget) -> Gtk.Box:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.append(W.section_head(text, trailing))
        box.append(child)
        return box

    def _limit_panel(self, data: vm.Dashboard, limit: float, clean: bool) -> Gtk.Box:
        from .theme import DARK, LIGHT
        pal = DARK if self.dark else LIGHT
        if not clean:
            caption = (f"Over the limit today — tomorrow under "
                       f"{stats.format_distance(limit)} starts a new run.")
        elif data.clean_streak > 1:
            caption = (f"{data.clean_streak} clean days in a row — stay under "
                       f"{stats.format_distance(limit)} to keep it.")
        else:
            caption = (f"On track — finish today under {stats.format_distance(limit)} "
                       f"for a clean day.")
        head = Gtk.Box()
        title = W.label("On track" if clean else "Over limit", "trek-head", xalign=0.0)
        title.set_hexpand(True)
        head.append(title)
        head.append(W.label(f"{stats.format_distance(data.today_m)} of "
                            f"{stats.format_distance(limit)}", "trek-body-muted",
                            xalign=1.0, wrap=False))
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        body.append(head)
        from .charts import Rail
        body.append(Rail(data.today_m / limit,
                         color=pal["moss"] if clean else pal["danger"],
                         track=pal["sunken"]))
        body.append(W.label(caption, "trek-small-muted"))
        return W.trek_panel(body)

    def _landmark(self, data: vm.Dashboard) -> Gtk.Box:
        from .charts import Rail
        from .theme import DARK, LIGHT
        pal = DARK if self.dark else LIGHT
        landmarks = stats.LANDMARKS
        nxt = next((lm for lm in landmarks if lm.meters > data.today_m), None)
        floor = next((lm.meters for lm in reversed(landmarks)
                      if data.today_m >= lm.meters), 0.0)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.append(W.label(stats.comparison(data.today_m), "trek-body"))
        if nxt is not None:
            span = max(nxt.meters - floor, 0.0001)
            box.append(Rail((data.today_m - floor) / span, color=pal["moss"],
                            track=pal["sunken"], height=4))
            box.append(W.label(f"{stats.format_distance(nxt.meters - data.today_m)} to go "
                               f"before {nxt.label}.", "trek-small-muted"))
        return box

    def _week_glance(self, data: vm.Dashboard) -> Gtk.Box:
        days_px = {d: int(m / vm.M_PER_PX) for d, m in data.day_m.items()}
        delta = stats.week_over_week_delta(days_px, data.today)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        box.append(W.section_head("This week", stats.format_distance(data.week_m)))
        pulse = WeekPulse(dark=self.dark)
        pulse.set([m for _, m, _ in data.week_rows], self.dark)
        box.append(pulse)
        row = Gtk.Box()
        row.append(W.overline(data.week_rows[0][0] if data.week_rows else ""))
        row.append(Gtk.Box(hexpand=True))
        mark = W.delta_mark(delta, "on last week")
        if mark is not None:
            row.append(mark)
            row.append(Gtk.Box(hexpand=True))
        row.append(W.overline("TODAY"))
        box.append(row)
        return box

    def _checklist(self, data: vm.Dashboard) -> Gtk.Box:
        steps = [(self._tracking_on(), "Turn on tracking", "One switch in Settings. Nothing else."),
                 (data.today_m > 0 or bool(data.day_m), "Go scroll a feed",
                  "Open a site in a linked browser and come back."),
                 (data.streak >= 2, "Come back tomorrow", "Two days in a row starts a streak.")]
        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        for i, (done, title, body) in enumerate(steps):
            if i > 0:
                panel.append(W.hairline())
            row = Gtk.Box(spacing=12)
            row.set_size_request(-1, 56)
            circle = Gtk.DrawingArea()
            circle.set_size_request(22, 22)
            circle.set_valign(Gtk.Align.START)
            circle.set_margin_top(12)

            def draw(_a, cr, w: int, h: int, _d, ok=done) -> None:
                from .charts import _rgb
                if ok:
                    cr.set_source_rgb(*_rgb(self._pal()["moss"]))
                    cr.arc(w / 2, h / 2, 11, 0, 6.2832)
                    cr.fill()
                    cr.set_source_rgb(*_rgb(self._pal()["on_moss"]))
                    cr.set_line_width(2)
                    cr.move_to(w * 0.32, h * 0.55)
                    cr.line_to(w * 0.46, h * 0.68)
                    cr.line_to(w * 0.70, h * 0.36)
                    cr.stroke()
                else:
                    cr.set_source_rgb(*_rgb(self._pal()["hairline"]))
                    cr.set_line_width(1)
                    cr.arc(w / 2, h / 2, 10.5, 0, 6.2832)
                    cr.stroke()

            circle.set_draw_func(draw, None)
            row.append(circle)
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
            text.set_margin_top(10)
            text.set_margin_bottom(10)
            text.append(W.label(title, "trek-label"))
            text.append(W.label(body, "trek-small"))
            row.append(text)
            panel.append(row)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.append(W.section_head("Getting started"))
        inner = W.trek_panel(panel)
        box.append(inner)
        return box

    def _on_share(self, _button: Gtk.Button) -> None:
        from .cli import render_card_svg
        data = self._data
        dialog = Gtk.FileDialog(initial_name="thumbtrek-card.svg")
        dialog.save(self, None, self._on_share_done, data)

    def _on_share_done(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult, data) -> None:
        try:
            path = dialog.save_finish(result).get_path()
        except GLib.Error:
            return
        from pathlib import Path
        from .cli import render_card_svg
        Path(path).write_text(render_card_svg(data.today_m, data.week_m, data.streak))
        self._toast(f"Saved to {path}.")

    # -- History tab ------------------------------------------------------
    def _history_cards(self, data: vm.Dashboard) -> list[Gtk.Widget]:
        days_px = {d: int(m / vm.M_PER_PX) for d, m in data.day_m.items()}
        if not days_px:
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=20)
            box.append(W.section_head("History"))
            empty = W.empty_state(
                "Nothing logged yet",
                "Once tracking has run for a day, this page fills with your daily "
                "totals, per-app trends, your longest trek and the checkpoints you have "
                "passed. Give it one session.")
            box.append(empty)
            return [box]
        cards: list[Gtk.Widget] = []
        all_time = sum(days_px.values()) * vm.M_PER_PX
        top = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=4)
        top.append(W.overline("ALL TIME"))
        top.append(W.label(stats.format_distance(all_time), "trek-display", wrap=False))
        days_n = len(days_px)
        top.append(W.label(f"over {days_n} tracked {'day' if days_n == 1 else 'days'}",
                           "trek-body-muted"))
        cards.append(top)

        hist = vm.history(days_px, self.hist_range, data.today)
        if self.hist_range not in self.hist_sel:
            self.hist_sel[self.hist_range] = len(hist.buckets) - 1
        sel = self.hist_sel[self.hist_range]
        chart_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        seg = W.trek_segmented(list(vm.HISTORY_RANGES), self.hist_range, self._on_range)
        chart_box.append(seg)
        bars = HistoryBars(dark=self.dark,
                           on_select=lambda i: self._on_bar(i, len(hist.buckets)))
        bars.set([m for _, _, m in hist.buckets], len(hist.buckets) - 1, sel, self.dark)
        chart_box.append(bars)
        chart_box.append(self._axis_labels([label for _, label, _ in hist.buckets], sel))
        if 0 <= sel < len(hist.buckets):
            chart_box.append(self._period_detail(data, hist.buckets[sel], self.hist_range))
        cards.append(chart_box)

        cards.append(self._clean_month(data, days_px))
        cards.append(self._records(data, days_px))
        trends = vm.app_series(self.store, data.today)
        if trends:
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
            box.append(W.section_head("App trends", "14 DAYS"))
            lines = TrendLines(dark=self.dark)
            lines.set([(t.package, t.points and [p.metres for p in t.points])
                       for t in trends], self.dark)
            box.append(lines)
            for trend in trends:
                row = Gtk.Box(spacing=10)
                row.append(W.series_dot(series_color(trend.package, self.dark)))
                name = W.label(feeds_mod.feed_name(trend.package), "trek-body-muted")
                name.set_hexpand(True)
                name.set_ellipsize(3)
                row.append(name)
                row.append(W.label(stats.format_distance(trend.total_m), "trek-figure",
                                   xalign=1.0, wrap=False))
                box.append(row)
            cards.append(box)
        badges = stats.badges(data.total_m, data.best_day_m, data.streak, data.clean_streak)
        if badges:
            ordered = sorted(badges, key=lambda b: (b.earned, b.progress), reverse=True)
            earned = sum(1 for b in ordered if b.earned)
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
            box.append(W.section_head("Checkpoints", f"{earned} / {len(ordered)}"))
            scrolled = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.AUTOMATIC,
                                          vscrollbar_policy=Gtk.PolicyType.NEVER)
            shelf = Gtk.Box(spacing=10)
            for badge in ordered:
                shelf.append(W.checkpoint_tile(badge, self.dark))
            scrolled.set_child(shelf)
            box.append(scrolled)
            nxt = next((b for b in ordered if not b.earned), None)
            if nxt is not None:
                box.append(W.label(f"Next up: {nxt.detail.lower()} "
                                   f"({round(nxt.progress * 100)}%).", "trek-small-muted"))
            cards.append(box)
        log_head = W.section_head("Day log", f"{len(days_px)} DAYS")
        cards.append(log_head)
        busiest = max(days_px.values()) or 1
        last_month = ""
        for day in sorted(days_px, reverse=True):
            month = day.strftime("%B %Y").upper()
            if month != last_month:
                last_month = month
                over = W.overline(month)
                over.set_margin_top(6)
                cards.append(over)
            metres = days_px[day] * vm.M_PER_PX
            is_today = day == data.today
            cards.append(W.measure_row(
                day.strftime("%a %-d %b"), stats.format_distance(metres),
                days_px[day] / busiest,
                (self._pal()["moss"] if is_today else self._pal()["slate"]),
                self.dark, label_muted=not is_today))
        return cards

    def _pal(self) -> dict:
        return DARK if self.dark else LIGHT

    def _axis_labels(self, labels: list[str], highlight: int) -> Gtk.Box:
        row = Gtk.Box(homogeneous=True)
        if len(labels) <= 8:
            for i, text in enumerate(labels):
                lab = W.overline(text)
                lab.set_xalign(0.5)
                if i != highlight:
                    lab.set_opacity(0.6)
                row.append(lab)
        else:
            first = W.overline(labels[0])
            last = W.overline(labels[-1])
            row.append(first)
            row.append(Gtk.Box(hexpand=True))
            row.append(last)
        return row

    def _on_range(self, index: int) -> None:
        if index != self.hist_range:
            self.hist_range = index
            self.refresh()

    def _on_bar(self, index: int, count: int) -> None:
        current = self.hist_sel.get(self.hist_range, -1)
        self.hist_sel[self.hist_range] = -1 if index == current else index
        self.refresh()

    def _period_detail(self, data: vm.Dashboard, bucket: tuple, index: int) -> Gtk.Box:
        key, _label_text, _metres = bucket
        days_px = {d: int(m / vm.M_PER_PX) for d, m in data.day_m.items()}
        import datetime
        if index == 0:
            title = datetime.date.fromisoformat(key).strftime("%a %-d %b")
            noun = "day"
        elif index == 1:
            title = f"Week of {_label_text}"
            noun = "week"
        else:
            year, mon = (int(p) for p in key.split("-"))
            title = datetime.date(year, mon, 1).strftime("%B %Y")
            noun = "month"
        px = 0
        if index == 0:
            try:
                px = days_px.get(datetime.date.fromisoformat(key), 0)
            except ValueError:
                px = 0
        else:
            raw = vm.history(days_px, index, data.today).buckets
            px = next((int(m / vm.M_PER_PX) for k, _l, m in raw if k == key), 0)
        metres = px * vm.M_PER_PX
        head = Gtk.Box()
        title_w = W.label(title, "trek-title")
        title_w.set_hexpand(True)
        title_w.set_ellipsize(3)
        head.append(title_w)
        value = W.label(stats.format_distance(metres), "trek-display-sm", xalign=1.0, wrap=False)
        head.append(value)
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        body.append(head)
        if metres > 0:
            body.append(W.label(stats.comparison(metres), "trek-small-muted"))
        else:
            body.append(W.label(f"No trek logged that {noun}.", "trek-small-muted"))
        if index == 0 and metres > 0:
            trends = vm.app_series(self.store, data.today)
            split = []
            for trend in trends:
                hit = next((pt for pt in trend.points if pt.key == key), None)
                if hit is not None and hit.metres > 0:
                    split.append((trend.package, hit.metres))
            split.sort(key=lambda kv: -kv[1])
            if split:
                for pkg, app_m in split:
                    row = Gtk.Box(spacing=10)
                    row.append(W.series_dot(series_color(pkg, self.dark)))
                    name = W.label(feeds_mod.feed_name(pkg), "trek-body-muted")
                    name.set_hexpand(True)
                    name.set_ellipsize(3)
                    row.append(name)
                    row.append(W.label(stats.format_distance(app_m), "trek-figure",
                                       xalign=1.0, wrap=False))
                    body.append(row)
        return W.trek_panel(body)

    def _clean_month(self, data: vm.Dashboard, days_px: dict) -> Gtk.Box:
        import calendar as _cal
        import datetime
        month = datetime.date(data.today.year, data.today.month, 1)
        title = month.strftime("%B %Y").upper()
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.append(W.section_head("Clean month", title))
        grid = Gtk.Grid(column_spacing=6, row_spacing=6, column_homogeneous=True)
        for col, head in enumerate(["M", "T", "W", "T", "F", "S", "S"]):
            lab = W.overline(head)
            lab.set_xalign(0.5)
            grid.attach(lab, col, 0, 1, 1)
        first = month
        blanks = (first.weekday()) % 7
        cells = [None] * blanks + [month.replace(day=d)
                                   for d in range(1, _cal.monthrange(month.year, month.month)[1] + 1)]
        while len(cells) % 7:
            cells.append(None)
        for row_idx in range(len(cells) // 7):
            for col in range(7):
                day = cells[row_idx * 7 + col]
                if day is None:
                    grid.attach(Gtk.Box(), col, row_idx + 1, 1, 1)
                    continue
                px = days_px.get(day)
                metres = (px or 0) * vm.M_PER_PX
                future = day > data.today
                over = not future and metres > data.limit_m
                tracked = not future and px is not None
                classes = []
                if not future and tracked:
                    classes.append("trek-cal-over" if over else "trek-cal-clean")
                elif not future and not tracked:
                    classes.append("trek-cal-idle")
                if day == self.cal_day:
                    classes.append("trek-cal-today")
                elif day == data.today:
                    classes.append("trek-cal-today")
                button = Gtk.Button(label=str(day.day))
                button.add_css_class("flat")
                for cls in classes:
                    button.add_css_class(cls)
                if over:
                    button.add_css_class("trek-danger-text")
                button.connect("clicked", self._on_cal_day, day)
                grid.attach(button, col, row_idx + 1, 1, 1)
        box.append(grid)
        keys = Gtk.Box(spacing=14)
        keys.append(W.calendar_key(self._pal()["moss"], "Clean"))
        keys.append(W.calendar_key(self._pal()["danger"], "Over"))
        keys.append(W.calendar_key(self._pal()["hairline"], "No data"))
        box.append(keys)
        if self.cal_day is not None:
            box.append(self._period_detail(
                data, (self.cal_day.isoformat(), "", 0.0), 0))
        return box

    def _on_cal_day(self, _button: Gtk.Button, day) -> None:
        self.cal_day = None if self.cal_day == day else day
        self.refresh()

    def _records(self, data: vm.Dashboard, days_px: dict) -> Gtk.Box:
        import datetime
        fmt = "%a %-d %b"
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        box.append(W.section_head("Records", trailing=None))
        if data.best_day is not None:
            box.append(W.ledger_line("Longest single day",
                                     stats.format_distance(data.best_day_m),
                                     data.best_day.strftime(fmt), "trek-amber-text"))
        week_label, week_m = vm.best_week(data.day_m, data.today)
        if week_m > 0:
            box.append(W.ledger_line("Best week", stats.format_distance(week_m),
                                     f"week of {week_label}", "trek-amber-text"))
        box.append(W.ledger_line(
            "Current streak",
            f"{data.streak} days" if data.streak > 0 else "None",
            "keep it going" if data.streak > 0 else "scroll today to start one",
            "trek-amber-text" if data.streak > 0 else None))
        return box

    # -- Social tab -------------------------------------------------------
    def _social_cards(self) -> list[Gtk.Widget]:
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        box.append(W.overline("SOCIAL"))
        box.append(W.label("See how your week stacks up.", "trek-display-sm"))
        box.append(W.label(
            "Weekly, monthly and all-time boards against friends you invite by code, or "
            "against everyone. Signing in publishes nothing on its own: you choose what "
            "gets shared on the next step, and you can leave at any time.",
            "trek-body-muted"))
        signin = W.trek_button("Continue with Google")
        signin.connect("clicked", self._on_signin)
        btn_row = Gtk.Box()
        btn_row.append(signin)
        box.append(btn_row)
        return [box]

    def _on_signin(self, _button: Gtk.Button) -> None:
        dialog = Adw.MessageDialog(transient_for=self, heading="Sign-in is coming to Linux",
                                   body="Google sign-in needs an OAuth user gesture that has no "
                                   "Linux screen yet, so the leaderboard still lives on your "
                                   "phone and in your browser. Local tracking, history, "
                                   "streaks and badges are all here and need no account.")
        dialog.add_response("close", "Close")
        dialog.present()

    # -- Settings tab -----------------------------------------------------
    def _settings_cards(self) -> list[Gtk.Widget]:
        cards: list[Gtk.Widget] = []
        tracked = set(self._tracked())
        live = bool(tracked)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        box.append(W.section_head("Tracking"))
        box.append(W.status_line(live, "Counting scrolls", "Paused", self.dark))
        box.append(W.label(
            "The tracker is running. It sees wheel motion from the apps below and "
            "nothing else." if live else
            "Nothing is being measured. Switch an app on below to start counting "
            "how far you scroll.", "trek-body-muted"))
        if not live:
            go = W.trek_button("Turn on tracking")
            go.connect("clicked", self._on_tracking_enable)
            row = Gtk.Box()
            row.append(go)
            box.append(row)
        cards.append(box)

        apps = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        apps.append(W.section_head("Tracked apps", f"{len(tracked)} ON"))
        customs = set(self.cfg.get("custom_apps", []))
        for app in sorted(set(list(self.cfg.get("tracked_apps", [])) + list(customs)),
                          key=str.lower):
            suffix = None
            if app in customs:
                remove = Gtk.Button(label="Remove")
                remove.add_css_class("flat")
                remove.connect("clicked", self._on_custom_remove, app)
                suffix = remove
            apps.append(W.toggle_row(app, None, app in tracked,
                                     lambda on, a=app: self._on_track(a, on),
                                     dot_color=series_color(app, self.dark), suffix=suffix))
        try:
            seen_slugs = set(tracked) | set(customs)
            extra = sorted({slug for browser in detect() for slug in browser.app_slugs
                            if slug not in seen_slugs})
            if extra:
                note = W.label("Detected on this machine — switch on to track:",
                               "trek-small-muted")
                note.set_margin_top(8)
                apps.append(note)
            for slug in extra:
                apps.append(W.toggle_row(slug, "detected browser", False,
                                         lambda on, a=slug: self._on_track(a, on),
                                         dot_color=series_color(slug, self.dark)))
        except OSError:
            pass
        add = W.trek_ghost("Add another app")
        add.connect("clicked", self._on_add_app)
        apps.append(add)
        if not tracked:
            apps.append(W.label("Every app is off, so nothing will be measured. Switch at "
                                "least one back on to keep your trek going.", "trek-small"))
        cards.append(apps)

        feeds_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        feeds_box.append(W.section_head("Social feeds", "4 FEEDS"))
        feeds_box.append(W.label(
            "The four feeds the phone tracks as apps, measured here per site by the "
            "linked extension — on by default there. Unlinked browser time stays "
            "under the browser's name.", "trek-body-muted"))
        day = vm.dashboard(self.store, self.cfg).today
        feed_m = vm.feed_day(self.store, day)
        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        for i, (feed, _domains, _pkgs) in enumerate(feeds_mod.FEEDS):
            if i > 0:
                panel.append(W.hairline())
            metres = feed_m[feed]
            row = Gtk.Box(spacing=12)
            row.set_size_request(-1, 48)
            row.append(W.series_dot(series_color(feed.lower(), self.dark)))
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
            text.append(W.label(feed, "trek-body"))
            text.append(W.label(
                stats.format_distance(metres) + " today" if metres > 0
                else "not measured yet — scroll it in a linked browser",
                "trek-small"))
            row.append(text)
            panel.append(row)
        feeds_box.append(W.trek_panel(panel))
        if all(m <= 0 for m in feed_m.values()):
            feeds_box.append(W.label(
                "No feed has reported yet. Run `thumbtrek extension install`, load "
                "the extension, and scroll a feed.", "trek-small"))
        cards.append(feeds_box)

        limit_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        limit_box.append(W.section_head("Daily limit", f"{int(self.cfg.get('daily_limit_m', 100))} M / DAY"))
        limit_box.append(W.label(
            "Stay at or under this far a day and the day counts as clean. Clean days in "
            "a row are the streak that matters here — this one rewards scrolling less, "
            "not more.", "trek-body-muted"))
        editor = Gtk.Box(spacing=10)
        self.limit_entry = Gtk.Entry(text=str(int(self.cfg.get("daily_limit_m", 100))),
                                     input_purpose=Gtk.InputPurpose.NUMBER, hexpand=True)
        self.limit_entry.add_css_class("trek-entry")
        self.limit_entry.set_placeholder_text("Metres per day")
        self.limit_entry.connect("changed", self._on_limit_typed)
        editor.append(self.limit_entry)
        save = W.trek_button("Save")
        save.connect("clicked", self._on_limit_save)
        editor.append(save)
        limit_box.append(editor)
        self.limit_note = W.label("", "trek-small")
        limit_box.append(self.limit_note)
        from . import stats as _stats
        if not _stats.BILLING_ENFORCED:
            limit_box.append(W.label("Free while ThumbTrek finds its feet — change your "
                                     "limit as often as you like for now.", "trek-small"))
        cards.append(limit_box)

        diag = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        diag.append(W.section_head("Tracking diagnostics", "DEBUG"))
        diag.append(W.label(
            "Proof that measurement works. Scroll in a tracked app and come back here: "
            "its row should have moved. Wheel motion without distance means events "
            "arrive but bank nothing; nothing at all means the toggle above or the "
            "tracker daemon. Counts accumulate into today's ledger and never leave "
            "this computer.", "trek-body-muted"))
        panel = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        packages = sorted(set(list(self.cfg.get("tracked_apps", [])) + list(customs)),
                          key=lambda a: (a not in tracked, a.lower()))
        if not packages:
            panel.append(W.label("Nothing tracked yet — switch an app on above and its "
                                 "row appears here.", "trek-small"))
        today_split = self.store.day_split(vm.dashboard(self.store, self.cfg).today.isoformat())
        for i, pkg in enumerate(packages):
            if i > 0:
                panel.append(W.hairline())
            px = today_split.get(pkg, 0)
            if pkg not in tracked:
                detail = "off"
            elif px <= 0:
                detail = "idle — no scroll heard yet"
            else:
                detail = f"{stats.format_distance(px * vm.M_PER_PX)} today"
            row = Gtk.Box(spacing=12)
            row.set_size_request(-1, 48)
            row.append(W.series_dot(series_color(pkg, self.dark)))
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
            name = W.label(feeds_mod.feed_name(pkg), "trek-body")
            name.set_ellipsize(3)
            text.append(name)
            text.append(W.label(detail, "trek-small"))
            row.append(text)
            panel.append(row)
        diag.append(W.trek_panel(panel))
        cards.append(diag)

        ext_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        ext_box.append(W.section_head("Browser extension"))
        ext_box.append(W.label(
            "Exact per-site tracking. Pick a browser: the extension is staged, the "
            "desktop bridge installed, and the extensions page opens with the steps.",
            "trek-body-muted"))
        try:
            from .tracker import input_available
            standalone = "Standalone wheel tracking is on." if input_available() \
                else "Standalone wheel tracking is off (no /dev/input access)."
            ext_box.append(W.label(standalone, "trek-small"))
        except OSError:
            pass
        try:
            found_browsers = detect()
        except OSError:
            found_browsers = []
        live_ids = set(fresh_links())
        if not found_browsers:
            ext_box.append(W.label("No browsers detected — install one, then come back.",
                                   "trek-small"))
        for browser in found_browsers[:10]:
            state = "live" if browser.id in live_ids else browser.source
            row = Gtk.Box(spacing=12)
            row.set_size_request(-1, 56)
            text = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True)
            text.append(W.label(browser.name, "trek-body"))
            text.append(W.label(f"{browser.engine_label} · via {browser.source} · {state}",
                                "trek-small"))
            row.append(text)
            install = W.trek_button("Install")
            install.connect("clicked", self._on_ext_install, browser)
            row.append(install)
            ext_box.append(row)
        cards.append(ext_box)

        notif = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        notif.append(W.section_head("Notifications"))
        notif.append(W.toggle_row("Streak reminder",
                                  "One quiet nudge if a streak is about to lapse",
                                  bool(self.cfg.get("streak_reminder")),
                                  lambda on: self._on_flag("streak_reminder", on)))
        notif.append(W.toggle_row("Limit nudge",
                                  "One heads-up near 80% of your limit, one if you pass it",
                                  bool(self.cfg.get("limit_nudge")),
                                  lambda on: self._on_flag("limit_nudge", on)))
        notif.append(W.label("Off by default. ThumbTrek is not here to nag you about your "
                             "screen time.", "trek-small"))
        cards.append(notif)

        data_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        data_box.append(W.section_head("Your data"))
        export = W.trek_ghost("Export everything as CSV")
        export.connect("clicked", self._on_export)
        data_box.append(export)
        data_box.append(W.label("One row per app per day: dates and pixel counts, nothing else.",
                                "trek-small"))
        cards.append(data_box)

        cards.append(W.section_head("Privacy"))
        privacy = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        privacy.append(W.label("ThumbTrek measures distance, never content.", "trek-label"))
        privacy.append(W.label(
            "The tracker listens for one thing, wheel motion from the apps you picked "
            "above. It cannot see your posts, your messages, or what you type.",
            "trek-body-muted"))
        privacy.append(W.label(
            "Distances live in a database on this computer. Nothing is uploaded until you "
            "opt into a leaderboard on the Social tab.", "trek-body-muted"))
        cards.append(W.trek_panel(privacy))

        about = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14)
        about.append(W.section_head("About"))
        about.append(W.section_head("Updates"))
        upd = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        upd.append(W.label("Kept current by your package manager",
                           "trek-label"))
        upd.append(W.label("This copy updates with the system — AUR, .deb or .rpm — "
                           "instead of phoning home for new builds.", "trek-body-muted"))
        about.append(W.trek_panel(upd))
        about.append(W.action_row("Version", f"ThumbTrek {__version__}"))
        cards.append(about)
        return cards

    def _on_tracking_enable(self, _button: Gtk.Button) -> None:
        self.cfg["tracked_apps"] = list(config_mod.DEFAULT_TRACKED_APPS)
        config_mod.save(self.cfg)
        self._toast("Tracking on.")
        self.refresh()

    def _on_track(self, app: str, on: bool) -> None:
        tracked = [a for a in self.cfg.get("tracked_apps", []) if a != app]
        if on and app not in tracked:
            tracked.append(app)
        self.cfg["tracked_apps"] = tracked
        config_mod.save(self.cfg)
        self.refresh()

    def _on_custom_remove(self, _button: Gtk.Button, app: str) -> None:
        self.cfg["custom_apps"] = [a for a in self.cfg.get("custom_apps", []) if a != app]
        self.cfg["tracked_apps"] = [a for a in self.cfg.get("tracked_apps", []) if a != app]
        config_mod.save(self.cfg)
        self.refresh()

    def _on_add_app(self, _button: Gtk.Button) -> None:
        win = Gtk.Window(transient_for=self, title="Track another app",
                         default_width=360, modal=True)
        form = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12,
                       margin_top=18, margin_bottom=18, margin_start=18, margin_end=18)
        form.append(W.label("Application name, e.g. firefox", "trek-body-muted"))
        name_entry = Gtk.Entry()
        name_entry.add_css_class("trek-entry")
        form.append(name_entry)
        buttons = Gtk.Box(spacing=10, halign=Gtk.Align.END)
        cancel = W.trek_ghost("Cancel")
        cancel.set_hexpand(False)
        cancel.connect("clicked", lambda _b: win.destroy())
        add = W.trek_button("Add")
        add.connect("clicked", self._on_add_confirm, win, name_entry)
        buttons.append(cancel)
        buttons.append(add)
        form.append(buttons)
        win.set_child(form)
        win.present()

    def _on_add_confirm(self, _button: Gtk.Button, win: Gtk.Window, entry: Gtk.Entry) -> None:
        name = entry.get_text().strip().lower()
        if not name:
            return
        customs = list(self.cfg.get("custom_apps", []))
        if name not in customs:
            customs.append(name)
        self.cfg["custom_apps"] = customs
        tracked = list(self.cfg.get("tracked_apps", []))
        if name not in tracked:
            tracked.append(name)
        self.cfg["tracked_apps"] = tracked
        config_mod.save(self.cfg)
        win.destroy()
        self.refresh()

    def _on_limit_typed(self, _entry: Gtk.Entry) -> None:
        self.limit_note.set_text("")

    def _on_limit_save(self, _button: Gtk.Button) -> None:
        import datetime
        raw = "".join(c for c in self.limit_entry.get_text() if c.isdigit())[:5]
        value = float(raw) if raw else None
        if value is None:
            self.limit_note.set_text("Enter a number, e.g. 100.")
            return
        if not stats.MIN_DAILY_LIMIT_M <= value <= stats.MAX_DAILY_LIMIT_M:
            self.limit_note.set_text(
                f"Between {int(stats.MIN_DAILY_LIMIT_M)} and {int(stats.MAX_DAILY_LIMIT_M)} m, "
                f"so a typo can't break the game.")
            return
        key = stats.week_key(datetime.date.today())
        if self.cfg.get("limit_edit_week") != key:
            self.cfg["limit_edit_week"], self.cfg["limit_edit_count"] = key, 0
        self.cfg["limit_edit_count"] = int(self.cfg.get("limit_edit_count", 0)) + 1
        self.cfg["daily_limit_m"] = value
        config_mod.save(self.cfg)
        self.limit_note.set_text("Saved. Limits are free for now — change it whenever.")
        self.refresh()

    def _on_flag(self, key: str, on: bool) -> None:
        self.cfg[key] = on
        config_mod.save(self.cfg)

    def _on_export(self, _button: Gtk.Button) -> None:
        dialog = Gtk.FileDialog(initial_name="thumbtrek_export.csv")
        dialog.save(self, None, self._on_export_done)

    def _on_export_done(self, dialog: Gtk.FileDialog, result: Gio.AsyncResult) -> None:
        try:
            path = dialog.save_finish(result).get_path()
        except GLib.Error:
            return  # user cancelled
        self.store.export_csv(path)
        self._toast(f"Exported to {path}.")

    def _on_ext_install(self, _button: Gtk.Button, browser) -> None:
        try:
            staged = ext_install.stage_extension(browser)
            manifest, _wrapper = ext_install.install_native_host(browser)
        except (RuntimeError, OSError) as exc:
            self._toast(f"Install failed: {exc}")
            return
        dialog = Adw.MessageDialog(transient_for=self, heading=f"Finish in {browser.name}",
                                   body=f"Bridge installed at {manifest}.\n\n"
                                   + ext_install.guide(browser, staged))
        dialog.add_response("close", "Close")
        dialog.add_response("open", "Open extensions page")
        dialog.set_response_appearance("open", Adw.ResponseAppearance.SUGGESTED)
        dialog.connect("response", self._on_ext_dialog, browser)
        dialog.present()

    def _on_ext_dialog(self, _dialog: Adw.MessageDialog, response: str, browser) -> None:
        if response == "open":
            ext_install.launch(ext_install.open_page_argv(browser))
        self.refresh()


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.FLAGS_NONE)

    def do_startup(self):
        Adw.Application.do_startup(self)
        ensure_fonts()
        from gi.repository import Gtk as _Gtk
        from .theme import css
        provider = _Gtk.CssProvider()
        provider.load_from_data(css(_is_dark()))
        display = Gdk.Display.get_default()
        if display is not None:
            _Gtk.StyleContext.add_provider_for_display(
                display, provider, _Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        Adw.StyleManager.get_default().connect(
            "notify::dark", self._on_theme_changed)

    def _on_theme_changed(self, _manager, _pspec) -> None:
        from gi.repository import Gtk as _Gtk
        from .theme import css
        provider = _Gtk.CssProvider()
        provider.load_from_data(css(_is_dark()))
        display = Gdk.Display.get_default()
        if display is not None:
            _Gtk.StyleContext.add_provider_for_display(
                display, provider, _Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        window = self.props.active_window
        if window is not None:
            window.refresh()

    def do_activate(self):
        window = self.props.active_window or MainWindow(self)
        window.present()


def main() -> int:
    return App().run([])
