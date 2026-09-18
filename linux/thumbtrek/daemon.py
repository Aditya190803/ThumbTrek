"""Tracker daemon: batch wheel notches into SQLite every few seconds.

Mirrors ScrollTrackerService: filter against the tracked set first, batch in
memory, single upsert per app per flush. Meters are computed at display time;
only notches→pixels are stored (PX_PER_NOTCH CSS px per notch).
"""

from __future__ import annotations

import fcntl
import os
import time
from datetime import date
from pathlib import Path

from . import config as config_mod
from . import links as links_mod
from .browsers import detect, slug_matches
from .cli import PX_PER_NOTCH
from .tracker import focused_app, read_notches

FLUSH_S = 5.0
BROWSER_CACHE_S = 300.0


def _suppressed(focused_app: str, browsers) -> bool:
    """True while a live extension link owns the focused browser (slug- or
    channel-variant match), so the same scroll is never counted twice."""
    live = set(links_mod.fresh_links())
    return any(browser.id in live and slug_matches(focused_app, browser.app_slugs)
               for browser in browsers)


def _suppressed_slugs(browsers) -> set[str]:
    """Legacy helper kept for tests: slugs with a live link right now."""
    live = set(links_mod.fresh_links())
    slugs: set[str] = set()
    for browser in browsers:
        if browser.id in live:
            slugs.update(s.lower() for s in browser.app_slugs)
    return slugs


def _take_lock() -> None:
    """Single-instance guard: two daemons (service + terminal) would count
    every scroll twice. Exits with an explanation instead."""
    root = os.environ.get("THUMBTREK_DATA_HOME")
    path = (Path(root) if root else Path.home() / ".local" / "share" / "thumbtrek") / "daemon.lock"
    path.parent.mkdir(parents=True, exist_ok=True)
    global _LOCK_FD  # noqa: PLW0603 — process-lifetime handle, closed on exit
    _LOCK_FD = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(_LOCK_FD, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        raise SystemExit("Another thumbtrek daemon is already running — "
                         "not starting a second one (it would double-count).")


_LOCK_FD: int | None = None


def run_forever(store, cfg: dict, _browsers=None) -> None:
    if _browsers is None:  # production loop takes the lock; tests inject and skip it
        _take_lock()
    pending: dict[str, int] = {}
    browsers = _browsers if _browsers is not None else []
    next_refresh = 0.0
    while True:
        now = time.monotonic()
        if _browsers is None and now >= next_refresh:
            try:
                browsers = detect()
            except OSError:
                browsers = []
            next_refresh = now + BROWSER_CACHE_S
        notches = read_notches(timeout_s=FLUSH_S)
        if notches:
            app = focused_app()
            if config_mod.is_tracked(app, cfg):
                pending[app] = pending.get(app, 0) + abs(notches)
        if pending:
            today = date.today().isoformat()
            for app, count in list(pending.items()):
                try:
                    owned = _suppressed(app, browsers)
                except OSError:
                    owned = False
                if owned:
                    continue  # extension link is authoritative for this app
                store.accumulate(app, today, int(count * PX_PER_NOTCH))
            pending.clear()
        # Re-read config so Settings changes apply without restart.
        cfg = config_mod.load()
        time.sleep(0.1)
