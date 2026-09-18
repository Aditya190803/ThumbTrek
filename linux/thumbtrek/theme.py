"""Exact Trek design tokens, ported from app/.../ui/Theme.kt + Type.kt + Charts.kt.

Field-survey instrument, not a grid of Material cards: green-cast neutrals,
three accents with one job each (moss = today/you/live, amber = streak heat
and records, slate = the past and other people), flat 1px-bordered panels,
Space Grotesk display + Manrope body. Hex values are verbatim from Theme.kt.
"""

from __future__ import annotations

DARK = {
    "ground": "#0C0F0B", "raised": "#13170F", "sunken": "#080A07",
    "hairline": "#262C1D", "hairline_soft": "#1A1F15", "contour": "#1B2114",
    "ink": "#EDF1E6", "ink_muted": "#A9B39C", "ink_faint": "#79836D",
    "moss": "#8BE6A0", "on_moss": "#05210F", "moss_wash": "#17281B",
    "amber": "#F2B455", "amber_wash": "#2A2113",
    "slate": "#8FB6CC", "slate_wash": "#16212A",
    "danger": "#F08497", "danger_wash": "#2B1319",
    "medals": ["#F2C55C", "#C9D2C2", "#D09A66"],
}

LIGHT = {
    "ground": "#F3F1E8", "raised": "#FBFAF4", "sunken": "#E6E3D6",
    "hairline": "#DCD8C8", "hairline_soft": "#EAE7DA", "contour": "#E4E0D0",
    "ink": "#171A10", "ink_muted": "#5A6151", "ink_faint": "#6B7160",
    "moss": "#1E6B3E", "on_moss": "#FBFAF4", "moss_wash": "#DFEEE2",
    "amber": "#8A5806", "amber_wash": "#F6E9CE",
    "slate": "#1D5F80", "slate_wash": "#DDEAF1",
    "danger": "#A32639", "danger_wash": "#F7DFE2",
    "medals": ["#9A6E12", "#6E7668", "#8A5230"],
}

#: Categorical per-app ramp (Charts.kt) — never the semantic accents.
SERIES_DARK = ["#8BE6A0", "#77C4E8", "#E9C063", "#E79BB4", "#B6A2F0", "#6FD3C4"]
SERIES_LIGHT = ["#1E6B3E", "#1A5F87", "#8A5806", "#9C2F55", "#5A3FA8", "#116B60"]

#: Display order from data/Apps.kt; tracked apps take their slot, rest hash in.
SERIES_ORDER = ("com.instagram.android", "com.google.android.youtube",
                "com.twitter.android", "com.reddit.frontpage")


def series_color(key: str, dark: bool) -> str:
    ramp = SERIES_DARK if dark else SERIES_LIGHT
    try:
        index = SERIES_ORDER.index(key)
    except ValueError:
        index = hash(key) % len(ramp)
    return ramp[index % len(ramp)]


DISPLAY = "'Space Grotesk', 'Manrope', sans-serif"
BODY = "'Manrope', 'Space Grotesk', sans-serif"

FONT_FILES = ("space_grotesk.ttf", "manrope.ttf")


def ensure_fonts() -> bool:
    """Install the bundled display/body faces user-locally (freedesktop path).

    The APK embeds these; Linux packages drop them in /usr/share/fonts. For
    checkout runs, a user-level copy + fc-cache makes Pango resolve the exact
    faces without root. Silent best-effort: absence just falls back to system
    sans, never an error.
    """
    import os
    import shutil
    import subprocess
    from pathlib import Path
    try:
        dest = Path.home() / ".local" / "share" / "fonts" / "thumbtrek"
        src = Path(__file__).resolve().parents[1] / "assets" / "fonts"
        if not (src / FONT_FILES[0]).exists():
            src = Path("/usr/share/thumbtrek/assets/fonts")
        missing = [f for f in FONT_FILES if not (dest / f).exists()]
        if missing and (src / FONT_FILES[0]).exists():
            dest.mkdir(parents=True, exist_ok=True)
            for name in FONT_FILES:
                shutil.copy2(src / name, dest / name)
            subprocess.run(["fc-cache", "-f", str(dest)], capture_output=True, timeout=30)
        return True
    except OSError:
        return False
    except subprocess.SubprocessError:
        return True


def css(dark: bool) -> bytes:
    """GTK stylesheet expressing the Trek system (panels, rails, chips, type)."""
    p = DARK if dark else LIGHT
    return f"""
window {{ background: {p['ground']}; color: {p['ink']}; }}
.trek-ground {{ background: {p['ground']}; }}
.trek-panel {{ background: {p['raised']}; border: 1px solid {p['hairline']};
  border-radius: 18px; padding: 18px; }}
.trek-hero {{ font-family: {DISPLAY}; font-weight: 800; font-size: 68px;
  letter-spacing: -3px; color: {p['ink']}; }}
.trek-hero-tight {{ font-family: {DISPLAY}; font-weight: 800; font-size: 52px;
  letter-spacing: -2px; color: {p['ink']}; }}
.trek-unit {{ font-family: {DISPLAY}; font-weight: 500; font-size: 22px;
  color: {p['ink_muted']}; }}
.trek-display {{ font-family: {DISPLAY}; font-weight: 700; font-size: 42px;
  letter-spacing: -1.5px; color: {p['ink']}; }}
.trek-display-sm {{ font-family: {DISPLAY}; font-weight: 600; font-size: 26px;
  letter-spacing: -0.6px; color: {p['ink']}; }}
.trek-head {{ font-family: {DISPLAY}; font-weight: 500; font-size: 20px;
  color: {p['ink']}; }}
.trek-title {{ font-family: {DISPLAY}; font-weight: 500; font-size: 16px;
  color: {p['ink']}; }}
.trek-overline {{ font-family: {BODY}; font-weight: 700; font-size: 11px;
  letter-spacing: 1.7px; color: {p['ink_faint']}; }}
.trek-overline-moss {{ font-family: {BODY}; font-weight: 700; font-size: 11px;
  letter-spacing: 1.7px; color: {p['moss']}; }}
.trek-figure {{ font-family: {DISPLAY}; font-weight: 500; font-size: 15px;
  color: {p['ink_muted']}; }}
.trek-body {{ font-family: {BODY}; font-size: 15px; color: {p['ink']}; }}
.trek-body-muted {{ font-family: {BODY}; font-size: 14px; color: {p['ink_muted']}; }}
.trek-small {{ font-family: {BODY}; font-size: 12px; color: {p['ink_faint']}; }}
.trek-small-muted {{ font-family: {BODY}; font-size: 12px; color: {p['ink_muted']}; }}
.trek-label {{ font-family: {BODY}; font-weight: 600; font-size: 13px; color: {p['ink']}; }}
.trek-chip {{ border: 1px solid {p['hairline']}; border-radius: 999px;
  padding: 5px 12px; font-family: {BODY}; font-weight: 600; font-size: 12px;
  color: {p['ink_muted']}; }}
.trek-streak {{ background: {p['amber_wash']}; border: 1px solid {p['amber']};
  border-radius: 999px; padding: 6px 12px; font-family: {BODY}; font-weight: 600;
  font-size: 12px; color: {p['amber']}; }}
.trek-streak-idle {{ border: 1px solid {p['hairline']}; border-radius: 999px;
  padding: 6px 12px; font-family: {BODY}; font-weight: 600; font-size: 12px;
  color: {p['ink_faint']}; }}
.trek-btn {{ background: {p['moss']}; color: {p['on_moss']}; border-radius: 10px;
  padding: 12px 22px; font-family: {BODY}; font-weight: 600; font-size: 14px; }}
.trek-btn:disabled {{ background: {p['sunken']}; color: {p['ink_faint']}; }}
.trek-ghost {{ border: 1px solid {p['hairline']}; border-radius: 10px;
  padding: 12px 20px; font-family: {BODY}; font-weight: 600; font-size: 14px;
  color: {p['ink']}; background: transparent; }}
.trek-seg {{ background: {p['sunken']}; border: 1px solid {p['hairline_soft']};
  border-radius: 10px; padding: 3px; }}
.trek-seg button {{ border-radius: 8px; padding: 10px 0; font-family: {BODY};
  font-weight: 600; font-size: 12px; color: {p['ink_faint']}; background: transparent;
  border: none; }}
.trek-seg button:checked {{ background: {p['raised']};
  border: 1px solid {p['hairline']}; color: {p['ink']}; }}
.trek-nav {{ background: {p['ground']}; border-top: 1px solid {p['hairline']}; }}
.trek-nav button {{ background: transparent; border: none; color: {p['ink_faint']};
  font-family: {BODY}; font-weight: 600; font-size: 11px; padding: 8px 0 10px; }}
.trek-nav button:checked {{ color: {p['ink']}; }}
.trek-nav button:checked image {{ color: {p['moss']}; }}
.trek-tile {{ background: {p['raised']}; border: 1px solid {p['hairline']};
  border-radius: 14px; padding: 14px 6px; }}
.trek-tile-earned {{ background: {p['moss_wash']}; border: 1px solid {p['moss']};
  border-radius: 14px; padding: 14px 6px; }}
.trek-cal-clean {{ background: {p['moss_wash']}; border-radius: 10px; }}
.trek-cal-over {{ background: {p['danger_wash']}; border-radius: 10px; }}
.trek-cal-today {{ border: 1px solid {p['moss']}; border-radius: 10px; }}
.trek-cal-idle {{ border: 1px solid {p['hairline_soft']}; border-radius: 10px; }}
.trek-entry {{ background: {p['sunken']}; border: 1px solid {p['hairline']};
  border-radius: 10px; padding: 10px 12px; color: {p['ink']}; }}
.trek-amber-wash {{ background: {p['amber_wash']}; border: 1px solid {p['amber']};
  border-radius: 18px; padding: 18px; }}
.trek-danger-text {{ color: {p['danger']}; }}
.trek-amber-text {{ color: {p['amber']}; }}
.trek-slate-text {{ color: {p['slate']}; }}
.trek-moss-text {{ color: {p['moss']}; }}
switch:checked {{ background: {p['moss']}; }}
switch:checked slider {{ background: {p['on_moss']}; }}
""".encode()
