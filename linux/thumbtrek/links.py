"""Live-link heartbeats: which browsers are currently feeding the daemon.

The native host (bin/thumbtrek-native-host) records `{browser_id: epoch_ms}`
every time the extension forwards a batch. The daemon treats a link fresher
than LINK_FRESH_MS as authoritative and drops its own evdev counts for that
browser's WM_CLASS slugs — that is the whole double-counting story.

Plain JSON, no locking (single writer, last-write-wins is fine), read on
every daemon loop so restarts and reinstalls take effect immediately.
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path

LINK_FRESH_MS = 120_000


def _path() -> Path:
    root = os.environ.get("THUMBTREK_DATA_HOME")
    base = Path(root) if root else Path.home() / ".local" / "share" / "thumbtrek"
    return base / "browser-links.json"


def read() -> dict[str, int]:
    try:
        data = json.loads(_path().read_text())
    except (OSError, ValueError):
        return {}
    return {k: int(v) for k, v in data.items() if isinstance(v, (int, float))} \
        if isinstance(data, dict) else {}


def beat(browser_id: str, now_ms: int | None = None) -> None:
    path = _path()
    path.parent.mkdir(parents=True, exist_ok=True)
    current = read()
    current[browser_id] = now_ms if now_ms is not None else int(time.time() * 1000)
    # Drop links stale by >24h so the file cannot grow without bound.
    cutoff = (now_ms if now_ms is not None else int(time.time() * 1000)) - 86_400_000
    current = {k: v for k, v in current.items() if v >= cutoff}
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(current, sort_keys=True))
    os.replace(tmp, path)


def fresh_links(now_ms: int | None = None) -> dict[str, int]:
    now = now_ms if now_ms is not None else int(time.time() * 1000)
    return {k: v for k, v in read().items() if now - v < LINK_FRESH_MS}
