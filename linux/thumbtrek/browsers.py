"""Detect installed browsers across every common Linux install source.

Sources covered: native PATH binaries, .desktop entries (system + user +
flatpak/snap exports, which also catches AppImages that ship one), `flatpak
list`, `snap list` + /snap/bin, and bare AppImage files scanned by name.

Security notes: nothing detected here is ever *executed* during detection
(AppImages especially are matched by filename only — running one to probe it
would be arbitrary code execution). .desktop `Exec` lines are parsed, never
run, with field codes (%U, %F, …) stripped. `flatpak`/`snap` are invoked by
argv with a timeout, never through a shell.
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

ENGINE_CHROMIUM = "chromium"
ENGINE_FIREFOX = "firefox"

#: Pinned extension ID, derived from the `"key"` in extension/manifest.json
#: (first 16 bytes of SHA-256 over the key, hex mapped 0-9a-f → a-p).
#: ext_install.extension_id() recomputes it; EXTENSION_ID is the documented
#: expectation so a key rotation fails loudly instead of silently unlinking.
EXTENSION_ID = "hnpbmamhbbhafanpjjedajgddbholoed"
#: Fixed Firefox add-on ID (manifest.firefox.json gecko id). Verified to apply
#: even to temporary about:debugging installs, so the native bridge allowlist
#: matches from the first load.
FIREFOX_ID = "thumbtrek@adityamer.dev"
NATIVE_HOST_NAME = "com.thumbtrek.native"


@dataclass
class KnownBrowser:
    tokens: frozenset[str]  # binary basenames / flatpak-id fragments / name fragments
    name: str
    engine: str
    config_dir: str  # dirname under the Chromium config root / Firefox home
    app_slugs: tuple[str, ...]  # WM_CLASS values (lowercase) for evdev attribution
    flatpak_ids: tuple[str, ...] = ()
    snap_names: tuple[str, ...] = ()
    #: Tarball/manual install locations (~/zen, /opt/…) that never touch PATH.
    extra_globs: tuple[str, ...] = ()


KNOWN = (
    KnownBrowser(frozenset({"google-chrome", "google-chrome-stable"}), "Google Chrome",
                 ENGINE_CHROMIUM, "google-chrome",
                 ("google-chrome", "chrome", "google-chrome-stable"),
                 ("com.google.Chrome",), ()),
    # Before Chromium: "ungoogled-chromium" contains "chromium" and would
    # otherwise match the entry below on substring rules.
    KnownBrowser(frozenset({"ungoogled-chromium"}), "Ungoogled Chromium",
                 ENGINE_CHROMIUM, "chromium",
                 ("ungoogled-chromium", "chromium", "chromium-browser"),
                 (), ()),
    KnownBrowser(frozenset({"chromium", "chromium-browser"}), "Chromium",
                 ENGINE_CHROMIUM, "chromium",
                 ("chromium", "chromium-browser"),
                 ("org.chromium.Chromium",), ("chromium",)),
    KnownBrowser(frozenset({"brave", "brave-browser"}), "Brave",
                 ENGINE_CHROMIUM, "BraveSoftware/Brave-Browser",
                 ("brave-browser", "brave"),
                 ("com.brave.Browser",), ()),
    KnownBrowser(frozenset({"microsoft-edge", "microsoft-edge-stable", "edge"}), "Microsoft Edge",
                 ENGINE_CHROMIUM, "microsoft-edge",
                 ("microsoft-edge", "edge", "microsoft-edge-stable"),
                 ("com.microsoft.Edge",), ()),
    KnownBrowser(frozenset({"opera"}), "Opera",
                 ENGINE_CHROMIUM, "opera",
                 ("opera",),
                 ("com.opera.Opera",), ()),
    KnownBrowser(frozenset({"vivaldi"}), "Vivaldi",
                 ENGINE_CHROMIUM, "vivaldi",
                 ("vivaldi",),
                 ("com.vivaldi.Vivaldi",), ("vivaldi",)),
    KnownBrowser(frozenset({"firefox", "firefox-esr"}), "Firefox",
                 ENGINE_FIREFOX, ".mozilla",
                 ("firefox", "firefox-esr"),
                 ("org.mozilla.firefox",), ("firefox",)),
    KnownBrowser(frozenset({"librewolf"}), "LibreWolf",
                 ENGINE_FIREFOX, ".librewolf",
                 ("librewolf",),
                 ("io.gitlab.librewolf-community",), ()),
    KnownBrowser(frozenset({"zen", "zen-browser"}), "Zen Browser",
                 ENGINE_FIREFOX, ".zen",
                 ("zen", "zen-browser"),
                 ("app.zen_browser.zen",), (),
                 ("~/zen/zen", "~/.zen-browser/zen", "/opt/zen*/zen*",
                  "/opt/zen-browser*/zen-bin", "~/.local/share/zen/zen")),
    KnownBrowser(frozenset({"floorp"}), "Floorp",
                 ENGINE_FIREFOX, ".floorp",
                 ("floorp",),
                 ("one.ale Floorp", "one.ale.floorp"), (),
                 ("~/floorp/floorp", "/opt/floorp*/floorp*")),
    KnownBrowser(frozenset({"waterfox"}), "Waterfox",
                 ENGINE_FIREFOX, ".waterfox",
                 ("waterfox",),
                 ("net.waterfox.waterfox",), (),
                 ("~/waterfox/waterfox", "/opt/waterfox*/waterfox*")),
    KnownBrowser(frozenset({"whale", "naver-whale"}), "Whale",
                 ENGINE_CHROMIUM, "naver-whale",
                 ("whale", "naver-whale"),
                 (), (),
                 ("~/whale/whale", "/opt/*whale*/whale")),
    KnownBrowser(frozenset({"thorium", "thorium-browser"}), "Thorium",
                 ENGINE_CHROMIUM, "Thorium",
                 ("thorium", "thorium-browser", "thorium-shell"),
                 (), (),
                 ("~/thorium/thorium", "/opt/*thorium*/thorium*")),
)


@dataclass
class Browser:
    """One installed browser and everything needed to drive it."""
    id: str  # stable: "<source>:<token>", e.g. "flatpak:com.brave.Browser"
    name: str
    engine: str
    source: str  # native | desktop | flatpak | snap | appimage
    launch: list[str]  # argv to start it (URL appended by caller)
    known: KnownBrowser | None = None
    detail: str = ""
    app_slugs: tuple[str, ...] = ()

    @property
    def engine_label(self) -> str:
        return {"chromium": "Chromium", "firefox": "Firefox"}.get(self.engine, self.engine)


def _run(argv: list[str], timeout: int = 10) -> str:
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.SubprocessError):
        return ""
    return proc.stdout if proc.returncode == 0 else ""


def _match_known(text: str) -> KnownBrowser | None:
    lowered = text.lower()
    for known in KNOWN:
        if any(tok in lowered for tok in known.tokens):
            return known
    return None


def _match_flatpak_id(app_id: str) -> KnownBrowser | None:
    lowered = app_id.lower()
    for known in KNOWN:
        if lowered in (i.lower() for i in known.flatpak_ids):
            return known
    return _match_known(app_id.replace(".", " ").replace("-", " ").replace("_", " "))


def _token_hit(basename: str, tokens: frozenset[str]) -> bool:
    """Exact or channel-variant binary match (zen-bin, brave-browser-beta)."""
    name = basename.lower()
    return any(name == tok or name.startswith(tok + "-") or name.startswith(tok + "_")
               for tok in tokens)


def _detect_path(path_dirs: list[str] | None) -> list[Browser]:
    found: list[Browser] = []
    dirs = path_dirs if path_dirs is not None else os.environ.get("PATH", "").split(os.pathsep)
    for known in KNOWN:
        for token in sorted(known.tokens):
            hit = None
            for directory in dirs:
                # Exact binary first, then channel/variant builds sharing the
                # stem: brave-browser-beta, firefox-developer-edition, zen-bin.
                # Names are matched, never executed.
                try:
                    exact = Path(directory) / token
                    if exact.is_file() and os.access(exact, os.X_OK):
                        hit = exact
                        break
                    for variant in sorted(Path(directory).glob(f"{token}-*")) + \
                            sorted(Path(directory).glob(f"{token}_*")):
                        if variant.is_file() and os.access(variant, os.X_OK):
                            hit = variant
                            break
                    if hit is not None:
                        break
                except OSError:
                    continue
            if hit is not None:
                found.append(Browser(
                    id=f"native:{hit.name}", name=known.name, engine=known.engine,
                    source="native", launch=[str(hit)], known=known,
                    app_slugs=known.app_slugs))
                break
    return found


_FIELD_CODE = re.compile(r"%[uUfFdDnNickvm]")

def _parse_desktop(path: Path) -> dict[str, str]:
    """Parse Name/Exec/Categories from a .desktop file. Never executes anything."""
    fields: dict[str, str] = {}
    try:
        text = path.read_text(errors="replace")
    except OSError:
        return fields
    in_entry = False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("["):
            in_entry = line == "[Desktop Entry]"
            continue
        if not in_entry or "=" not in line:
            continue
        key, _, value = line.partition("=")
        if key.strip() in ("Name", "Exec", "Categories", "NoDisplay", "Hidden", "Type"):
            fields[key.strip()] = value.strip()
    return fields


def _detect_desktops(desktop_dirs: list[str] | None) -> list[Browser]:
    if desktop_dirs is None:
        home = Path.home()
        desktop_dirs = [
            "/usr/share/applications",
            "/usr/local/share/applications",
            "/var/lib/flatpak/exports/share/applications",
            str(home / ".local/share/applications"),
            str(home / ".local/share/flatpak/exports/share/applications"),
        ]
    found: list[Browser] = []
    for directory in desktop_dirs:
        try:
            entries = sorted(Path(directory).glob("*.desktop"))
        except OSError:
            continue
        for entry in entries:
            fields = _parse_desktop(entry)
            if (fields.get("Type", "Application") != "Application"
                    or fields.get("NoDisplay") == "true" or fields.get("Hidden") == "true"):
                continue
            exec_line = _FIELD_CODE.sub("", fields.get("Exec", "")).strip()
            if not exec_line:
                continue
            binary = exec_line.split()[0]
            token = Path(binary).name.lower()
            known = _match_known(f"{fields.get('Name', '')} {token} {entry.stem}")
            is_browser = known is not None or "WebBrowser" in fields.get("Categories", "")
            if not is_browser:
                continue
            launch = [binary] if binary.startswith("/") else [shutil.which(binary) or binary]
            # Flatpak-exported entries exec `flatpak run …`; keep the full argv.
            if token == "flatpak" and "run" in exec_line:
                launch = exec_line.split()
            name = fields.get("Name", entry.stem)
            found.append(Browser(
                id=f"desktop:{entry.stem}", name=name,
                engine=known.engine if known else "unknown",
                source="flatpak" if launch[:2] == ["flatpak", "run"] else "desktop",
                launch=launch, known=known, detail=str(entry),
                app_slugs=known.app_slugs if known else ()))
    return found


def _detect_flatpak() -> list[Browser]:
    if not shutil.which("flatpak"):
        return []
    out = _run(["flatpak", "list", "--app", "--columns=application,name"])
    found: list[Browser] = []
    for line in out.splitlines():
        parts = line.split("\t")
        if not parts or not parts[0].strip():
            continue
        app_id = parts[0].strip()
        app_name = parts[1].strip() if len(parts) > 1 else app_id
        known = _match_flatpak_id(f"{app_id} {app_name}")
        if known is None:
            continue
        found.append(Browser(
            id=f"flatpak:{app_id}", name=known.name, engine=known.engine,
            source="flatpak", launch=["flatpak", "run", app_id], known=known,
            app_slugs=known.app_slugs))
    return found


def _detect_snap() -> list[Browser]:
    found: list[Browser] = []
    snap_bin = Path("/snap/bin")
    names: set[str] = set()
    out = _run(["snap", "list"])
    for line in out.splitlines()[1:]:  # skip header
        if line.strip():
            names.add(line.split()[0].lower())
    if snap_bin.is_dir():
        try:
            names.update(p.name.lower() for p in snap_bin.iterdir())
        except OSError:
            pass
    for name in sorted(names):
        known = None
        for candidate in KNOWN:
            if name in candidate.snap_names or name in candidate.tokens:
                known = candidate
                break
        if known is None:
            continue
        launcher = str(snap_bin / name)
        if not Path(launcher).exists():
            launcher = shutil.which(name) or name
        found.append(Browser(
            id=f"snap:{name}", name=known.name, engine=known.engine,
            source="snap", launch=[launcher], known=known,
            app_slugs=known.app_slugs))
    return found


def _detect_appimages(appimage_dirs: list[str] | None) -> list[Browser]:
    if appimage_dirs is None:
        home = Path.home()
        appimage_dirs = [str(home / "Applications"), str(home / ".local/bin"),
                         str(home / "AppImages"), "/opt"]
    found: list[Browser] = []
    for directory in appimage_dirs:
        try:
            candidates = list(Path(directory).glob("*.AppImage")) + \
                list(Path(directory).glob("*.appimage"))
        except OSError:
            continue
        for candidate in candidates:
            known = _match_known(candidate.stem.replace("-", " ").replace("_", " "))
            if known is None:
                continue
            found.append(Browser(
                id=f"appimage:{candidate.name}", name=known.name, engine=known.engine,
                source="appimage", launch=[str(candidate)], known=known,
                detail=str(candidate), app_slugs=known.app_slugs))
    return found


def _detect_known_paths() -> list[Browser]:
    """Tarball/manual installs that never touch PATH (~/zen, /opt/…).

    Names are matched, never executed: a hit still needs the executable bit.
    """
    import glob as _glob
    found: list[Browser] = []
    for known in KNOWN:
        if not known.extra_globs:
            continue
        for pattern in known.extra_globs:
            expanded = os.path.expanduser(pattern)
            try:
                candidates = sorted(_glob.glob(expanded))
            except OSError:
                continue
            for candidate in candidates:
                path = Path(candidate)
                try:
                    if (path.is_file() and os.access(path, os.X_OK)
                            and _token_hit(path.name, known.tokens)):
                        found.append(Browser(
                            id=f"native:{path.name}", name=known.name,
                            engine=known.engine, source="native",
                            launch=[str(path)], known=known,
                            detail=str(path), app_slugs=known.app_slugs))
                        break
                except OSError:
                    continue
            else:
                continue
            break
    return found


def scan_report() -> dict:
    """What `detect` looked at: locations, tool availability, per-source hits."""
    import shutil as _shutil
    home = Path.home()
    report = {
        "path_dirs": os.environ.get("PATH", "").split(os.pathsep),
        "desktop_dirs": [
            "/usr/share/applications",
            "/usr/local/share/applications",
            "/var/lib/flatpak/exports/share/applications",
            str(home / ".local/share/applications"),
            str(home / ".local/share/flatpak/exports/share/applications"),
        ],
        "appimage_dirs": [str(home / "Applications"), str(home / ".local/bin"),
                          str(home / "AppImages"), "/opt"],
        "flatpak_present": bool(_shutil.which("flatpak")),
        "snap_bin_present": Path("/snap/bin").is_dir(),
        "known_location_globs": sorted(
            pattern for known in KNOWN for pattern in known.extra_globs),
    }
    report["hits"] = {
        "path": len(_detect_path(None)),
        "desktop": len(_detect_desktops(None)),
        "flatpak": len(_detect_flatpak()),
        "snap": len(_detect_snap()),
        "appimage": len(_detect_appimages(None)),
        "known_locations": len(_detect_known_paths()),
    }
    return report


def detect(path_dirs: list[str] | None = None,
           desktop_dirs: list[str] | None = None,
           appimage_dirs: list[str] | None = None,
           include_known_paths: bool = True) -> list[Browser]:
    """Detect browsers across all sources, deduplicated by launch target."""
    seen: set[tuple] = set()
    ordered: list[Browser] = []
    found = (_detect_path(path_dirs) + _detect_desktops(desktop_dirs)
             + _detect_flatpak() + _detect_snap() + _detect_appimages(appimage_dirs))
    if include_known_paths:
        found += _detect_known_paths()
    for browser in found:
        key = (browser.engine, tuple(browser.launch), browser.name)
        if key in seen:
            continue
        seen.add(key)
        ordered.append(browser)
    # Deterministic order: native first, then the rest alphabetically.
    rank = {"native": 0, "desktop": 1, "flatpak": 2, "snap": 3, "appimage": 4}
    ordered.sort(key=lambda b: (rank.get(b.source, 9), b.name.lower()))
    return ordered


def slug_matches(focused: str, slugs: tuple[str, ...]) -> bool:
    """WM_CLASS attribution: exact or channel-variant match.

    Beta/dev/nightly builds report classes like `brave-browser-beta`; the
    stem still identifies the browser, so `slug-…` counts as a match.
    """
    focused = (focused or "").lower()
    for slug in slugs:
        slug = slug.lower()
        if focused == slug or focused.startswith(slug + "-") or focused.startswith(slug + "_"):
            return True
    return False


def browser_from_config(entry: dict) -> Browser | None:
    """User override from config `custom_browsers`: escape hatch for anything
    detection misses. Schema: {name, engine, launch[]} with optional
    `slugs` (defaults to the launcher basename). Anything malformed → None,
    never a half-built entry.
    """
    if not isinstance(entry, dict):
        return None
    name = entry.get("name")
    engine = entry.get("engine")
    launch = entry.get("launch")
    if (not isinstance(name, str) or not name.strip()
            or engine not in (ENGINE_CHROMIUM, ENGINE_FIREFOX)
            or not isinstance(launch, list) or not launch
            or not all(isinstance(part, str) and part for part in launch)):
        return None
    slugs = entry.get("slugs")
    if not isinstance(slugs, list) or not all(isinstance(s, str) and s for s in slugs):
        slugs = [Path(launch[0]).name.lower()]
    safe = re.sub(r"[^a-z0-9-]+", "-", name.lower()).strip("-") or "custom"
    return Browser(id=f"custom:{safe}", name=name.strip(), engine=engine,
                   source="custom", launch=list(launch), known=None,
                   detail="from config custom_browsers",
                   app_slugs=tuple(s.lower() for s in slugs))
