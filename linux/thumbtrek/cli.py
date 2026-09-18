"""CLI + native desktop window — the Linux app's user interface.

``thumbtrek dashboard`` opens a real GTK4/libadwaita window (see ui.py);
``status``/``export``/``card`` stay terminal commands, ``daemon`` runs the
tracker, ``browsers``/``extension`` link the web-extension for exact
per-site tracking.
"""

from __future__ import annotations

import argparse
import os
from datetime import date, timedelta
from pathlib import Path

from . import config as config_mod
from . import stats
from .store import Store
from .sync_model import micrometres_to_meters, wheel_notches_to_micrometres
from .version import DISPLAY_VERSION

# Desktop wheel notches arrive as CSS-px equivalents: keep the same 96-dpi
# wire unit as the browser so µm stay comparable across clients.
NOTCH_PX = 40.0
PX_PER_NOTCH = NOTCH_PX


def _day_meters(store: Store) -> dict:
    out = {}
    for day, px in store.days().items():
        y, m, d = (int(p) for p in day.split("-"))
        out[date(y, m, d)] = px / 96.0 * 0.0254
    return out


def render_status(store: Store, cfg: dict) -> str:
    today = date.today()
    day_m = _day_meters(store)
    today_px = store.day_total(today.isoformat())
    today_m = today_px / 96.0 * 0.0254
    limit = float(cfg.get("daily_limit_m", 100.0))
    lines = [
        f"ThumbTrek {DISPLAY_VERSION} — today's trek: {stats.format_distance(today_m)}",
        f"  {stats.comparison(today_m)}",
        f"  streak {stats.trek_streak([d for d, px in store.days().items() if px > 0 for d in [date(*map(int, d.split('-')))]])} day(s)"
        f" · clean streak {stats.limit_streak(day_m, limit)} day(s) (limit {limit:g} m)",
        f"  week {stats.format_distance(sum(day_m.get(today - timedelta(days=i), 0.0) for i in range(today.weekday() + 1)))}"
        f" · total {stats.format_distance(sum(day_m.values()))}",
    ]
    earned = [b for b in stats.badges(sum(day_m.values()),
                                      max(day_m.values(), default=0.0),
                                      stats.trek_streak(list(day_m)),
                                      stats.limit_streak(day_m, limit)) if b.earned]
    if earned:
        lines.append("  badges: " + " ".join(f"{b.emoji}{b.label}" for b in earned[-5:]))
    return "\n".join(lines)


def cmd_dashboard() -> int:
    """Open the native desktop window (GTK4/libadwaita, no web server)."""
    try:
        from .ui import main as ui_main
    except ImportError as exc:
        print("The native window needs system GTK4 + libadwaita + PyGObject:")
        print("  Arch:    sudo pacman -S gtk4 libadwaita python-gobject")
        print("  Debian:  sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1")
        print("  Fedora:  sudo dnf install python3-gobject gtk4 libadwaita")
        print(f"({exc})")
        return 1
    return ui_main()


def render_card_svg(today_m: float, week_m: float, streak: int) -> str:
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="560">'
            f'<rect width="1080" height="560" rx="24" fill="#0e1420"/>'
            f'<text x="60" y="120" font-size="52" fill="#fff" font-family="sans-serif">ThumbTrek</text>'
            f'<text x="60" y="230" font-size="96" fill="#7db4ff" font-family="sans-serif">{today_m:.1f} m today</text>'
            f'<text x="60" y="320" font-size="40" fill="#cdd7e4" font-family="sans-serif">week {week_m:.1f} m · 🔥 {streak}</text>'
            f'<text x="60" y="480" font-size="28" fill="#8b98ab" font-family="sans-serif">Strava for scrolling — Linux edition</text>'
            f"</svg>")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="thumbtrek", description="ThumbTrek for Linux (offline-first).")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("status", help="Print today's trek.")
    sub.add_parser("dashboard", help="Open the native desktop window.")
    sub.add_parser("daemon", help="Run the scroll tracker (systemd usually runs this).")
    export = sub.add_parser("export", help="Write Android-compatible CSV.")
    export.add_argument("--out", default="thumbtrek_export.csv")
    card = sub.add_parser("card", help="Write shareable SVG stat card.")
    card.add_argument("--out", default="thumbtrek-card.svg")
    browse =     sub.add_parser("doctor", help="Explain why nothing is being recorded, if so.")
    sub.add_parser("signin", help="Sign in with Google (Desktop OAuth flow).")
    sub.add_parser("signout", help="Clear the stored sign-in session.")
    browse = sub.add_parser("browsers", help="List detected browsers (native/flatpak/snap/appimage).")
    browse.add_argument("-v", "--verbose", action="store_true",
                        help="Show scanned locations and per-source hit counts.")
    ext = sub.add_parser("extension", help="Install the web-extension into a browser.")
    ext_sub = ext.add_subparsers(dest="ext_cmd", required=True)
    install = ext_sub.add_parser("install", help="Pick a browser and install into it.")
    install.add_argument("--browser", type=int, default=None,
                         help="1-based number from `thumbtrek browsers` (else ask).")
    install.add_argument("--yes", action="store_true",
                         help="Non-interactive: stage + bridge, print guide, no prompts.")
    ext_sub.add_parser("status", help="Show staged copy, bridges, and live links.")
    remove = ext_sub.add_parser("remove", help="Remove the native-messaging bridge.")
    remove.add_argument("--browser", type=int, default=None)
    ping = ext_sub.add_parser("ping", help="Test the installed bridge like the browser would.")
    ping.add_argument("--browser", type=int, default=None)
    sub.add_parser("version", help="Print version.")
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    cfg = config_mod.load()
    store = Store()
    try:
        if args.cmd == "status":
            print(render_status(store, cfg))
        elif args.cmd == "dashboard":
            store.close()
            return cmd_dashboard()
        elif args.cmd == "daemon":
            from .daemon import run_forever
            run_forever(store, cfg)
        elif args.cmd == "export":
            print(store.export_csv(Path(args.out)))
        elif args.cmd == "card":
            today = date.today().isoformat()
            today_m = store.day_total(today) / 96.0 * 0.0254
            week_m = sum(store.day_total((date.today() - timedelta(days=i)).isoformat())
                         for i in range(date.today().weekday() + 1)) / 96.0 * 0.0254
            active = [date(*map(int, d.split("-"))) for d, px in store.days().items() if px > 0]
            Path(args.out).write_text(render_card_svg(today_m, week_m, stats.trek_streak(active)))
            print(args.out)
        elif args.cmd == "version":
            print(DISPLAY_VERSION)
        elif args.cmd == "doctor":
            return cmd_doctor()
        elif args.cmd == "signin":
            return cmd_signin()
        elif args.cmd == "signout":
            return cmd_signout()
        elif args.cmd == "browsers":
            return cmd_browsers(verbose=args.verbose)
        elif args.cmd == "extension":
            if args.ext_cmd == "install":
                return cmd_extension_install(preselect=args.browser, yes=args.yes)
            elif args.ext_cmd == "status":
                return cmd_extension_status()
            elif args.ext_cmd == "remove":
                return cmd_extension_remove(preselect=args.browser)
            elif args.ext_cmd == "ping":
                return cmd_extension_ping(preselect=args.browser)
        return 0
    finally:
        store.close()


def _all_browsers() -> list:
    """Detected browsers plus validated config `custom_browsers` overrides."""
    from .browsers import browser_from_config, detect
    found = detect()
    seen = {(b.engine, tuple(b.launch)) for b in found}
    for entry in config_mod.load().get("custom_browsers", []) or []:
        custom = browser_from_config(entry)
        if custom is not None and (custom.engine, tuple(custom.launch)) not in seen:
            seen.add((custom.engine, tuple(custom.launch)))
            found.append(custom)
    return found


def cmd_doctor() -> int:
    """Explain why nothing is being recorded, if that is the case."""
    import fcntl
    from datetime import date
    from pathlib import Path
    from .tracker import focused_app, input_available
    ok = True
    print(f"session: {os.environ.get('XDG_SESSION_TYPE', '?')} "
          f"| focused app right now: {focused_app()!r}")
    if input_available():
        print("input: /dev/input readable — wheel tracking possible")
    else:
        ok = False
        print("input: NOT readable — join the `input` group AND log back in, "
              "or link the extension for browser-only tracking")
    data_home = os.environ.get("THUMBTREK_DATA_HOME")
    lock = (Path(data_home) if data_home else Path.home() / ".local" / "share"
            / "thumbtrek") / "daemon.lock"
    try:
        fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.flock(fd, fcntl.LOCK_UN)
            print("daemon: NOT running — start it (`thumbtrek daemon` or the user service)")
            ok = False
        except OSError:
            print("daemon: running")
        finally:
            os.close(fd)
    except OSError as exc:
        print(f"daemon: unknown ({exc})")
    cfg = config_mod.load()
    store = Store()
    try:
        today = date.today().isoformat()
        total = store.day_total(today)
        print(f"today: {total} px in the ledger "
              f"({', '.join(f'{a}={px}' for a, px in store.day_split(today).items()) or 'empty'})")
        tracked = set(cfg.get("tracked_apps", [])) | set(cfg.get("custom_apps", []))
        print(f"tracked: {len(tracked)} app(s)")
        current = focused_app()
        if current not in tracked:
            ok = False
            print(f"focused app {current!r} is NOT tracked — enable it in Settings → "
                  f"Tracked apps (or add it as a custom app)")
        if total == 0:
            print("hint: scroll in a tracked app for a few seconds, then `thumbtrek status`")
    finally:
        store.close()
    return 0 if ok else 1


def cmd_signin() -> int:
    """Desktop Google sign-in: browser → loopback → Firebase session."""
    import os
    from . import auth
    api_key = os.environ.get("THUMBTREK_FIREBASE_API_KEY", "")
    try:
        cfg = auth.sign_in(config_mod.load(), api_key)
    except auth.AuthError as exc:
        print(f"Sign-in failed: {exc}")
        return 1
    from .identity import friend_code, format_friend_code
    print(f"Signed in. Friend code: {format_friend_code(friend_code(cfg['uid']))}")
    print("Leaderboard sync itself is still phone/web-only; this session is "
          "stored for when the Social tab lands.")
    return 0


def cmd_signout() -> int:
    from . import auth
    auth.sign_out(config_mod.load())
    print("Signed out — local session cleared.")
    return 0


def cmd_browsers(verbose: bool = False) -> int:
    browsers = _all_browsers()
    if verbose:
        from .browsers import scan_report
        report = scan_report()
        print(f"PATH entries: {len(report['path_dirs'])}; "
              f"flatpak tool: {'yes' if report['flatpak_present'] else 'no'}; "
              f"/snap/bin: {'yes' if report['snap_bin_present'] else 'no'}")
        print("hits: " + ", ".join(f"{k}={v}" for k, v in report["hits"].items()))
    if not browsers:
        print("No browsers detected (checked PATH, .desktop files, flatpak, snap, AppImages).")
        print("For anything missed, add it to ~/.config/thumbtrek/config.json "
              "under \"custom_browsers\" — see the README.")
        return 1
    for i, browser in enumerate(browsers, 1):
        print(f"[{i}] {browser.name}  ({browser.engine_label}, via {browser.source})")
        print(f"     {' '.join(browser.launch)}")
    return 0


def cmd_extension_install(preselect: int | None = None, yes: bool = False) -> int:
    from . import ext_install
    browsers = _all_browsers()
    if not browsers:
        print("No browsers detected — install one, then re-run `thumbtrek extension install`.")
        return 1
    if yes and preselect is None and len(browsers) > 1:
        print("Multiple browsers detected; re-run with --browser N (see `thumbtrek browsers`).")
        return 1
    browser = ext_install.pick_browser(browsers, preselect=preselect)
    if browser is None:
        print("Selection aborted." if not yes else "Invalid --browser number.")
        return 1
    try:
        staged = ext_install.stage_extension(browser)
    except RuntimeError as exc:
        print(f"Cannot stage extension: {exc}")
        return 1
    try:
        manifest, _wrapper = ext_install.install_native_host(browser)
    except RuntimeError as exc:
        print(f"Bridge install failed: {exc}")
        return 1
    print(f"Extension staged at {staged}\nBridge manifest at {manifest}\n")
    print(f"Finish in {browser.name}:")
    print(ext_install.guide(browser, staged))
    ext_install.launch(ext_install.open_page_argv(browser))
    if not yes:
        oneshot = ext_install.oneshot_argv(browser, staged)
        if oneshot is not None:
            try:
                answer = input("Launch it now with the extension loaded for this session? [y/N]: ")
            except EOFError:
                answer = ""
            if answer.strip().lower() in ("y", "yes"):
                ext_install.launch(oneshot)
                print("Launched — scrolling there is now measured per-site.")
                return 0
        print("Once loaded, scrolling there is measured per-site (evdev won't double-count it).")
    return 0


def cmd_extension_status() -> int:
    from . import ext_install, links as links_mod
    from .tracker import input_available
    if input_available():
        print("Standalone tracking: active (wheel events readable — no extension needed).")
    else:
        print("Standalone tracking: unavailable (no /dev/input access — join the `input` "
              "group, or link the extension below for browser tracking).")
    staged = ext_install.staged_dir()
    manifest = staged / "manifest.json"
    print(f"Staged copy: {staged} ({'present' if manifest.exists() else 'missing — run install'})")
    live = set(links_mod.fresh_links())
    for row in ext_install.install_status(_all_browsers()):
        browser = row["browser"]
        state = "LIVE" if browser.id in live else ("bridged" if row["manifests"] else "not installed")
        print(f"  {browser.name} [{browser.source}]: {state}")
        for path in row["manifests"]:
            print(f"    bridge: {path}")
    return 0


def cmd_extension_remove(preselect: int | None = None) -> int:
    from . import ext_install
    browsers = _all_browsers()
    browser = ext_install.pick_browser(browsers, preselect=preselect)
    if browser is None:
        print("Selection aborted.")
        return 1
    print(f"Removed {ext_install.remove_native_host(browser)} bridge manifest(s).")
    return 0


def cmd_extension_ping(preselect: int | None = None) -> int:
    from . import ext_install
    browsers = _all_browsers()
    browser = ext_install.pick_browser(browsers, preselect=preselect)
    if browser is None:
        print("Selection aborted.")
        return 1
    result = ext_install.ping_host(browser)
    if result["ok"]:
        print(f"Bridge alive for {browser.name}:")
        print(f"  manifest: {result['manifest']}")
        print(f"  host:     {result['wrapper']}")
        print("Scroll in that browser — batches should now land in `thumbtrek status`.")
        return 0
    print(f"Bridge broken for {browser.name}: {result['error']}")
    if "manifest" in result:
        print(f"  manifest: {result['manifest']}")
    if "wrapper" in result:
        print(f"  host:     {result['wrapper']}")
    print("Re-run `thumbtrek extension install` for that browser and ping again.")
    return 1
