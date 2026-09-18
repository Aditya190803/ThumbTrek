"""ThumbTrek Linux client — offline-first scroll tracker for Linux desktops.

Native GTK4/libadwaita window, stdlib-only everything else: sqlite3 storage,
urllib Firestore sync, evdev/X11 tracking with Wayland fallbacks.
Source slug for sync: ``linux`` (see docs/sync-protocol.md §2 pattern).
"""

from .version import __version__

__all__ = ["__version__"]
