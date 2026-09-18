"""Install the ThumbTrek extension into a user-chosen detected browser.

Honest capability boundary (same spirit as README's self-update section):
Chromium and Firefox deliberately offer **no silent-install API** for
unpacked extensions — that path is how malware ships. So this module
automates everything that *is* automatable without root:

1. Stage a stable copy of `extension/` at `~/.local/share/thumbtrek/extension`.
2. Install the `com.thumbtrek.native` native-messaging manifest (+ a tiny
   wrapper baking in the browser id) so the extension can report exact
   per-domain scroll to the daemon from the first run.
3. Open the right `chrome://extensions` / `about:debugging` page and print
   the 3-step load-unpacked guide — plus an optional one-shot launch with
   `--load-extension` so tracking starts immediately, with confirmation.

From there tracking is accurate: extension batches arrive over stdio with
per-domain µm, and the daemon suppresses its own evdev counts for that
browser while the link is live (see links.py), so nothing is double-counted.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

from .browsers import (ENGINE_CHROMIUM, ENGINE_FIREFOX, EXTENSION_ID, FIREFOX_ID,
                     NATIVE_HOST_NAME, Browser)

GUIDE_CHROMIUM = (
    "  1. We opened chrome://extensions — turn on 'Developer mode' (top right).\n"
    "  2. Click 'Load unpacked' and pick this folder:\n"
    "     {staged}\n"
    "  3. Pin ThumbTrek to the toolbar. Done — today's trek appears in `thumbtrek status`."
)
GUIDE_FIREFOX = (
    "  1. We opened about:debugging — click 'This Firefox'.\n"
    "  2. Click 'Load Temporary Add-on…' and pick this file:\n"
    "     {manifest}\n"
    "  3. Note: temporary add-ons unload on browser restart (a Firefox rule, not\n"
    "     ours). Re-running `thumbtrek extension install` reprints these steps."
)


def extension_addon_id(browser: Browser | None = None) -> str:
    """Which add-on ID the browser will present: pinned Chrome ID, or the
    fixed gecko ID for permanent Firefox installs."""
    if browser is not None and browser.engine == ENGINE_FIREFOX:
        return FIREFOX_ID
    return EXTENSION_ID


def extension_id(manifest_path: Path | None = None) -> str:
    """Recompute the stable extension ID from the manifest's pinned key.

    Chromium IDs are the first 16 bytes of SHA-256 over the public key, hex
    mapped 0-9a-f → a-p. Must equal browsers.EXTENSION_ID; a key rotation
    without updating everything fails loudly here instead of unlinking.
    """
    path = manifest_path or (extension_source_dir() / "manifest.json")
    key_b64 = json.loads(path.read_text())["key"]
    digest = hashlib.sha256(base64.b64decode(key_b64)).digest()[:16]
    table = str.maketrans("0123456789abcdef", "abcdefghijklmnop")
    return digest.hex().translate(table)


def extension_source_dir() -> Path:
    """Where the shippable extension/ lives: packaged, env override, or repo."""
    override = os.environ.get("THUMBTREK_EXTENSION_SRC")
    if override:
        return Path(override)
    packaged = Path("/usr/share/thumbtrek/extension")
    if (packaged / "manifest.json").exists():
        return packaged
    repo = Path(__file__).resolve().parents[2] / "extension"
    return repo


def _data_home() -> Path:
    root = os.environ.get("THUMBTREK_DATA_HOME")
    base = Path(root) if root else Path.home() / ".local" / "share" / "thumbtrek"
    base.mkdir(parents=True, exist_ok=True)
    return base


def staged_dir() -> Path:
    return _data_home() / "extension"


def stage_extension(browser: Browser | None = None) -> Path:
    """Copy extension/ to the stable staged path. For Firefox engines the
    staged copy carries manifest.firefox.json as manifest.json (Firefox
    rejects service_worker); Chromium keeps manifest.json as-is."""
    src = extension_source_dir()
    if not (src / "manifest.json").exists():
        raise RuntimeError(f"extension source missing manifest.json: {src}")
    dest = staged_dir()
    shutil.copytree(src, dest, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("__pycache__", "*.pyc", ".git",
                                                  "test", "tools"))
    want_firefox = browser is not None and browser.engine == ENGINE_FIREFOX
    if want_firefox:
        variant = src / "manifest.firefox.json"
        if not variant.exists():
            raise RuntimeError(f"firefox manifest variant missing: {variant}")
        shutil.copy2(variant, dest / "manifest.json")
    # Read back what Firefox/Chrome will actually parse: a stale or swapped
    # manifest here is precisely the "service_worker is disabled" trap.
    try:
        staged_manifest = json.loads((dest / "manifest.json").read_text())
    except (OSError, ValueError) as exc:
        raise RuntimeError(f"staged manifest unreadable: {exc}") from exc
    background = staged_manifest.get("background", {})
    if want_firefox and "service_worker" in background:
        raise RuntimeError("staged copy still carries the Chrome manifest — "
                           "delete ~/.local/share/thumbtrek/extension and retry")
    if not want_firefox and "service_worker" not in background:
        raise RuntimeError("staged copy lost the Chrome manifest — "
                           "delete ~/.local/share/thumbtrek/extension and retry")
    return dest


def host_binary() -> str:
    override = os.environ.get("THUMBTREK_NATIVE_HOST")
    if override:
        return override
    packaged = "/usr/lib/thumbtrek/thumbtrek-native-host"
    if Path(packaged).exists():
        return packaged
    return str(Path(__file__).resolve().parents[1] / "bin" / "thumbtrek-native-host")


def _safe_id(browser_id: str) -> str:
    return re.sub(r"[^a-z0-9-]+", "-", browser_id.lower()).strip("-") or "browser"


def _flatpak_app_id(browser: Browser) -> str | None:
    if browser.id.startswith("flatpak:"):
        return browser.id.split(":", 1)[1]
    if browser.launch[:2] == ["flatpak", "run"] and len(browser.launch) > 2:
        return browser.launch[2]
    return None


def _snap_name(browser: Browser) -> str | None:
    if browser.id.startswith("snap:"):
        return browser.id.split(":", 1)[1]
    return None


def nm_host_dirs(browser: Browser) -> list[Path]:
    """Candidate NativeMessagingHosts dirs, most likely first (best-effort for
    snap/flatpak layouts, which vendors move around — all are tried in order)."""
    home = Path.home()
    flatpak_id = _flatpak_app_id(browser)
    snap = _snap_name(browser)
    dirs: list[Path] = []
    if browser.engine == ENGINE_FIREFOX:
        leaf = Path("native-messaging-hosts")
        if flatpak_id:
            dirs.append(home / ".var/app" / flatpak_id / ".mozilla" / leaf)
        elif snap:
            dirs.append(home / "snap" / snap / "common" / ".mozilla" / leaf)
            dirs.append(home / "snap" / snap / "current" / ".mozilla" / leaf)
        else:
            dirs.append(home / ".mozilla" / leaf)
    elif browser.engine == ENGINE_CHROMIUM and browser.known is not None:
        cfg = browser.known.config_dir
        if flatpak_id:
            dirs.append(home / ".var/app" / flatpak_id / "config" / cfg / "NativeMessagingHosts")
        elif snap:
            dirs.append(home / "snap" / snap / "common" / cfg / "NativeMessagingHosts")
            dirs.append(home / "snap" / snap / "current" / cfg / "NativeMessagingHosts")
        else:
            dirs.append(home / ".config" / cfg / "NativeMessagingHosts")
    return dirs


def install_native_host(browser: Browser) -> tuple[Path, Path]:
    """Write wrapper + manifest into the first writable candidate dir.

    Returns (manifest_path, wrapper_path). Raises RuntimeError if nothing is
    writable (e.g. unknown engine) — the caller reports, never half-installs.
    """
    if browser.engine not in (ENGINE_CHROMIUM, ENGINE_FIREFOX):
        raise RuntimeError(f"don't know how to bridge engine {browser.engine!r}")
    safe = _safe_id(browser.id)
    wrapper = _data_home() / "native-hosts" / f"{safe}.sh"
    wrapper.parent.mkdir(parents=True, exist_ok=True)
    host = host_binary().replace("'", "")
    if not (os.path.isfile(host) and os.access(host, os.X_OK)):
        raise RuntimeError(
            f"native host is not executable: {host} — run `chmod +x {host}` "
            f"(checkout copies lose the exec bit) and retry")
    wrapper.write_text(f'#!/bin/sh\nexec \'{host}\' --browser \'{browser.id}\'\n')
    wrapper.chmod(0o755)

    if browser.engine == ENGINE_FIREFOX:
        manifest = {"name": NATIVE_HOST_NAME, "description": "ThumbTrek desktop bridge",
                    "path": str(wrapper), "type": "stdio",
                    "allowed_extensions": [extension_addon_id(browser)]}
    else:
        manifest = {"name": NATIVE_HOST_NAME, "description": "ThumbTrek desktop bridge",
                    "path": str(wrapper), "type": "stdio",
                    "allowed_origins": [f"chrome-extension://{extension_addon_id(browser)}/"]}
    last_error = "no candidate directories"
    for directory in nm_host_dirs(browser):
        try:
            directory.mkdir(parents=True, exist_ok=True)
            dest = directory / f"{NATIVE_HOST_NAME}.json"
            dest.write_text(json.dumps(manifest, indent=2, sort_keys=True))
            return dest, wrapper
        except OSError as exc:
            last_error = str(exc)
    raise RuntimeError(f"could not write native-messaging manifest: {last_error}")


def ping_host(browser: Browser, timeout_s: float = 10.0) -> dict:
    """Run the installed bridge exactly like the browser would: spawn the
    wrapper from the manifest, send one framed heartbeat, read the reply.

    This is the loud version of what the extension does silently — when the
    dashboard shows nothing, this says whether the pipe or the install is
    at fault. Never raises; the dict always explains.
    """
    import struct
    manifest_path = next(
        (d / f"{NATIVE_HOST_NAME}.json" for d in nm_host_dirs(browser)
         if (d / f"{NATIVE_HOST_NAME}.json").exists()), None)
    if manifest_path is None:
        return {"ok": False, "error": "no bridge manifest installed for this browser"}
    try:
        wrapper = json.loads(manifest_path.read_text())["path"]
    except (OSError, ValueError, KeyError) as exc:
        return {"ok": False, "manifest": str(manifest_path),
                "error": f"manifest unreadable: {exc}"}
    body = json.dumps({"type": "trek/heartbeat", "browser": browser.id}).encode()
    try:
        proc = subprocess.run([wrapper], input=struct.pack("<I", len(body)) + body,
                              capture_output=True, timeout=timeout_s)
    except OSError as exc:
        return {"ok": False, "manifest": str(manifest_path), "wrapper": wrapper,
                "error": f"could not execute wrapper: {exc}"}
    except subprocess.TimeoutExpired:
        return {"ok": False, "manifest": str(manifest_path), "wrapper": wrapper,
                "error": "host did not reply in time"}
    if proc.returncode != 0:
        tail = proc.stderr.decode(errors="replace")[-300:]
        return {"ok": False, "manifest": str(manifest_path), "wrapper": wrapper,
                "error": f"host exited {proc.returncode}: {tail or 'no output'}"}
    if len(proc.stdout) < 4:
        return {"ok": False, "error": "host replied with an empty frame"}
    (length,) = struct.unpack("<I", proc.stdout[:4])
    try:
        reply = json.loads(proc.stdout[4:4 + length].decode())
    except ValueError as exc:
        return {"ok": False, "error": f"host reply unparseable: {exc}"}
    if reply.get("ok") is True:
        return {"ok": True, "manifest": str(manifest_path), "wrapper": wrapper}
    return {"ok": False, "manifest": str(manifest_path), "wrapper": wrapper,
            "error": f"host refused: {reply}"}


def remove_native_host(browser: Browser) -> int:
    """Remove manifests installed for this browser. Returns count removed."""
    removed = 0
    for directory in nm_host_dirs(browser):
        try:
            target = directory / f"{NATIVE_HOST_NAME}.json"
            if target.exists():
                target.unlink()
                removed += 1
        except OSError:
            continue
    return removed


def install_status(browsers: list[Browser]) -> list[dict]:
    """Per-browser install state for `extension status`."""
    staged = staged_dir()
    rows = []
    for browser in browsers:
        manifests = [str(d / f"{NATIVE_HOST_NAME}.json")
                     for d in nm_host_dirs(browser)
                     if (d / f"{NATIVE_HOST_NAME}.json").exists()]
        rows.append({"browser": browser, "staged": staged.exists(), "manifests": manifests})
    return rows


def pick_browser(browsers: list[Browser], preselect: int | None = None) -> Browser | None:
    """Choose a browser: 1-based preselect, single-item shortcut, else prompt."""
    if not browsers:
        return None
    if preselect is not None:
        return browsers[preselect - 1] if 1 <= preselect <= len(browsers) else None
    if len(browsers) == 1:
        print(f"One browser detected: {browsers[0].name} ({browsers[0].source}). Using it.")
        return browsers[0]
    print("Detected browsers:")
    for i, browser in enumerate(browsers, 1):
        print(f"  [{i}] {browser.name}  ({browser.engine_label}, via {browser.source})")
    try:
        answer = input(f"Install to which browser? [1-{len(browsers)}, default 1]: ").strip()
    except EOFError:
        return None
    if not answer:
        return browsers[0]
    if answer.isdigit() and 1 <= int(answer) <= len(browsers):
        return browsers[int(answer) - 1]
    return None


def extensions_page(browser: Browser) -> str:
    if browser.engine == ENGINE_FIREFOX:
        return "about:debugging#/runtime/this-firefox"
    return "chrome://extensions"


def guide(browser: Browser, staged: Path) -> str:
    if browser.engine == ENGINE_FIREFOX:
        return GUIDE_FIREFOX.format(manifest=staged / "manifest.json")
    return GUIDE_CHROMIUM.format(staged=staged)


def open_page_argv(browser: Browser) -> list[str]:
    """argv to open the right install page (caller runs it; pure for tests)."""
    if browser.engine == ENGINE_FIREFOX:
        return browser.launch + ["about:debugging#/runtime/this-firefox"]
    return browser.launch + ["chrome://extensions"]


def oneshot_argv(browser: Browser, staged: Path) -> list[str] | None:
    """Immediate-tracking launch: Chromium --load-extension; else None."""
    if browser.engine == ENGINE_CHROMIUM:
        return browser.launch + [f"--load-extension={staged}", "chrome://extensions"]
    return None


def launch(argv: list[str]) -> None:
    subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
