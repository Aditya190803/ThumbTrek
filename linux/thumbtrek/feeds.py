"""Social feeds: the desktop identity of the phone's tracked apps.

Android measures four native apps (data/Apps.kt TRACKED_APPS); on Linux
those feeds live in the browser, so the linked extension measures four
domains (extension/lib/domain.js DEFAULT_SITES, on by default). Same four
feeds, same order, same names — this module is the shared dictionary both
sides already agree on, plus the name lookup mirroring siteName/appName:
built-in first, then caller labels, then the raw key.
"""

from __future__ import annotations

#: (feed name, site domains, android packages). Order is display order.
FEEDS = (
    ("Instagram", ("instagram.com",), ("com.instagram.android",)),
    ("YouTube", ("youtube.com", "youtu.be"), ("com.google.android.youtube",)),
    ("X", ("x.com",), ("com.twitter.android",)),
    ("Reddit", ("reddit.com",), ("com.reddit.frontpage",)),
)

_BY_KEY: dict[str, str] = {}
for _feed, _domains, _pkgs in FEEDS:
    for _key in _domains + _pkgs:
        _BY_KEY[_key.lower()] = _feed


def feed_name(key: str, custom_labels: dict | None = None) -> str:
    """Display name for a storage key: feed, custom label, or the key itself."""
    if custom_labels:
        for variant in (key, key.lower()):
            if variant in custom_labels:
                return custom_labels[variant]
    return _BY_KEY.get(key.lower(), key)


def feed_rank(key: str) -> int:
    """Sort key: the four feeds first in display order, everything else after."""
    name = _BY_KEY.get(key.lower())
    if name is None:
        return len(FEEDS)
    return next(i for i, (feed, _, _) in enumerate(FEEDS) if feed == name)


def is_feed_domain(key: str) -> bool:
    return key.lower() in _BY_KEY
