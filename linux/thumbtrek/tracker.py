"""Scroll tracking for Linux — honest, least-privilege design.

X11: count wheel buttons (4/5) per focused window via XInput2 events is ideal,
but needs an X connection; Wayland deliberately forbids global snooping.
So v1 does the portable thing:

1. Read wheel motion from /dev/input/event* (evdev REL_WHEEL / HIRES_WHEEL).
   Needs membership in the ``input`` group — no root, no keylogging (we only
   look at relative-wheel codes, never EV_KEY key codes).
2. Attribute each notch batch to the focused app via X11 (xprop) or, on
   Wayland, via app-provided hints; fall back to ``unknown``.
3. Filter against the tracked-apps set before counting (like
   ScrollTrackerService checks Prefs.isTracked before counting a pixel).

Honest limit (same spirit as README's self-update section): on Wayland the
per-app attribution is best-effort; totals stay exact, splits may contain
``unknown``. The browser extension remains the precise per-site source.
"""

from __future__ import annotations

import glob
import os
import select
import shutil
import struct
import subprocess
import time
from dataclasses import dataclass

EV_REL, REL_WHEEL, REL_HWHEEL = 0x02, 0x08, 0x06
REL_WHEEL_HI_RES, REL_HWHEEL_HI_RES = 0x0B, 0x0C
EVENT_FMT = "llHHi"
EVENT_SIZE = struct.calcsize(EVENT_FMT)

# Drop absurd jumps the same way measure-core.js drops >3-viewport jumps.
MAX_NOTCHES_PER_READ = 500


@dataclass
class WheelEvent:
    notches: int  # vertical notches; +1 = up, -1 = down (sign kept for parity)
    app: str


def _focused_app_x11() -> str:
    """Active window's class via xprop (args list, no shell). Empty on failure."""
    if not shutil.which("xprop"):
        return ""
    try:
        active = subprocess.run(
            ["xprop", "-root", "_NET_ACTIVE_WINDOW"], capture_output=True,
            text=True, timeout=2).stdout
        win = active.strip().rsplit("#", 1)[-1].strip().strip(",")
        if not win or win.startswith("0x0"):
            return ""
        out = subprocess.run(
            ["xprop", "-id", win, "WM_CLASS"], capture_output=True,
            text=True, timeout=2).stdout
        parts = [p.strip().strip('"') for p in out.split("=")[-1].split(",")]
        return (parts[-1] or "").lower()
    except (OSError, ValueError, subprocess.SubprocessError):
        return ""


def focused_app() -> str:
    """Best-effort focused-app slug; 'unknown' when the compositor won't say."""
    session = (os.environ.get("XDG_SESSION_TYPE") or "").lower()
    if session != "wayland":
        app = _focused_app_x11()
        if app:
            return app
    # Wayland: compositors offer no stable CLI; respect that, don't scrape.
    hint = (os.environ.get("THUMBTREK_ACTIVE_APP") or "").strip().lower()
    return hint or "unknown"


def input_available() -> bool:
    """Whether the daemon can track standalone right now: at least one
    /dev/input/event* node opens for reading (usually the `input` group).
    No extension needed when this is true; without it, only the browser
    extension measures anything.
    """
    for node in glob.glob("/dev/input/event*"):
        try:
            fd = os.open(node, os.O_RDONLY | os.O_NONBLOCK)
        except OSError:
            continue
        try:
            os.close(fd)
        except OSError:
            pass
        return True
    return False


def _open_wheel_devices():
    devices = []
    for node in sorted(glob.glob("/dev/input/event*")):
        try:
            fd = os.open(node, os.O_RDONLY | os.O_NONBLOCK)
            devices.append((node, fd))
        except OSError:
            continue
    return devices


def read_notches(timeout_s: float = 5.0) -> int:
    """Block up to timeout_s, return net vertical wheel notches seen."""
    devices = _open_wheel_devices()
    if not devices:
        return 0
    total, deadline = 0, time.time() + timeout_s
    hires_accum = 0
    try:
        while time.time() < deadline:
            remaining = max(0.1, deadline - time.time())
            readable, _, _ = select.select([fd for _, fd in devices], [], [], remaining)
            for fd in readable:
                try:
                    chunk = os.read(fd, EVENT_SIZE * 32)
                except OSError:
                    continue
                for off in range(0, len(chunk) - EVENT_SIZE + 1, EVENT_SIZE):
                    _, _, typ, code, value = struct.unpack(
                        EVENT_FMT, chunk[off:off + EVENT_SIZE])
                    if typ != EV_REL:
                        continue
                    if code == REL_WHEEL and value:
                        total += max(-MAX_NOTCHES_PER_READ,
                                     min(MAX_NOTCHES_PER_READ, value))
                    elif code == REL_WHEEL_HI_RES and value:
                        # 120 hires units == 1 notch (libinput convention).
                        hires_accum += value
                        while abs(hires_accum) >= 120:
                            step = 1 if hires_accum > 0 else -1
                            total += step
                            hires_accum -= 120 * step
    finally:
        for _, fd in devices:
            try:
                os.close(fd)
            except OSError:
                pass
    return total
