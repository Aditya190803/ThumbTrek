"""Local config (~/.config/thumbtrek/config.json, mode 0600 when tokens present).

Mirrors Prefs.kt keys needed on desktop: tracked apps, daily limit, opt-ins,
quota bookkeeping, pushed markers. All writes are atomic (tmp + rename).
"""

from __future__ import annotations

import json
import os
from pathlib import Path

#: Apps counted out of the box. Every browser slug the detector knows
#: (browsers.py app_slugs) belongs here, or scrolling in it is silently
#: dropped by the tracked-set filter. `unknown` is the honest Wayland
#: bucket: compositors won't name the focused app, so unattributed motion
#: lands there instead of vanishing.
DEFAULT_TRACKED_APPS = ["firefox", "firefox-esr", "chrome", "chromium",
                        "brave", "brave-browser", "edge", "microsoft-edge",
                        "opera", "vivaldi", "librewolf", "zen", "zen-browser",
                        "floorp", "waterfox", "whale", "thorium",
                        "ungoogled-chromium", "kitty", "gnome-terminal",
                        "konsole", "code", "discord", "slack", "unknown"]
DEFAULT_LIMIT_M = 100.0


def _base() -> Path:
    root = os.environ.get("THUMBTREK_CONFIG_HOME")
    return Path(root) if root else Path.home() / ".config" / "thumbtrek"


def _path() -> Path:
    return _base() / "config.json"


def _defaults() -> dict:
    return {
        "tracked_apps": list(DEFAULT_TRACKED_APPS),
        "custom_apps": [],
        "custom_browsers": [],
        "daily_limit_m": DEFAULT_LIMIT_M,
        "leaderboard_opt_in": False,
        "anonymous": True,
        "streak_reminder": False,
        "limit_nudge": False,
        "limit_edit_week": None,
        "limit_edit_count": 0,
        "premium": False,
        "refresh_token": None,
        "uid": None,
        "id_token": None,
        "id_token_expiry": 0,
        "pushed": {},
        "last_synced_at": 0,
    }


def load() -> dict:
    cfg = _defaults()
    try:
        stored = json.loads(_path().read_text())
        if isinstance(stored, dict):
            for key, value in stored.items():
                if key in cfg:
                    cfg[key] = value
    except (OSError, ValueError):
        pass
    return cfg


def save(cfg: dict) -> None:
    base = _base()
    base.mkdir(parents=True, exist_ok=True)
    tmp = base / "config.json.tmp"
    keep = {k: v for k, v in cfg.items() if k in _defaults()}
    tmp.write_text(json.dumps(keep, indent=2, sort_keys=True))
    try:
        os.chmod(tmp, 0o600)
    except OSError:
        pass
    os.replace(tmp, _path())


def is_tracked(app: str, cfg: dict) -> bool:
    app = (app or "").lower()
    allowed = {a.lower() for a in cfg.get("tracked_apps", [])} | \
              {a.lower() for a in cfg.get("custom_apps", [])}
    return app in allowed
