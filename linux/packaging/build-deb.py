#!/usr/bin/env python3
"""Build a .deb without requiring dpkg-deb (stdlib only: tarfile + manual ar).

Layout mirrors the RPM/AUR payloads:
  /usr/lib/thumbtrek/{thumbtrek/*,thumbtrek-run}
  /usr/bin/thumbtrek (wrapper)
  /usr/lib/systemd/user/thumbtrek-tracker.service
  /usr/share/applications/thumbtrek.desktop

Usage: python3 linux/packaging/build-deb.py --version 0.5.0 --out dist/
"""
import argparse
import io
import tarfile
import time
from pathlib import Path

LINUX = Path(__file__).resolve().parents[1]
REPO = LINUX.parent
WRAPPER = b'#!/bin/sh\nexec /usr/bin/python3 /usr/lib/thumbtrek/thumbtrek-run "$@"\n'


def _add_file(tar: tarfile.TarFile, arcname: str, data: bytes, mode: int):
    info = tarfile.TarInfo(arcname)
    info.size, info.mode, info.mtime = len(data), mode, int(time.time())
    tar.addfile(info, io.BytesIO(data))


def _add_tree(tar: tarfile.TarFile, src_dir: Path, dest_prefix: str):
    for src in sorted(src_dir.rglob("*")):
        if not src.is_file() or "__pycache__" in src.parts or src.name.endswith(".pyc"):
            continue
        rel = src.relative_to(src_dir).as_posix()
        _add_file(tar, f"{dest_prefix}/{rel}", src.read_bytes(), 0o644)


def _ar_archive(members: list[tuple[str, bytes]]) -> bytes:
    out = bytearray(b"!<arch>\n")
    for name, data in members:
        header = f"{name:<16}{0:<12}{0:<6}{0:<6}{len(data):<10}`\n".encode()
        out += header + data + (b"\n" if len(data) % 2 else b"")
    return bytes(out)


def build(version: str, out_dir: Path, arch: str = "all") -> Path:
    pkg = f"thumbtrek_{version}_{arch}"
    control = (f"Package: thumbtrek\nVersion: {version}\nSection: utils\n"
               f"Priority: optional\nArchitecture: {arch}\n"
               f"Maintainer: ThumbTrek <https://github.com/Aditya190803/ThumbTrek>\n"
               f"Depends: python3, python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1\nDescription: Strava for scrolling (Linux, offline-first)\n"
               f" Scroll tracker for Linux desktops with localhost dashboard.\n").encode()

    data_buf, ctrl_buf = io.BytesIO(), io.BytesIO()
    with tarfile.open(fileobj=data_buf, mode="w:xz") as tar:
        _add_file(tar, "./usr/bin/thumbtrek", WRAPPER, 0o755)
        run = (LINUX / "bin" / "thumbtrek").read_bytes()
        _add_file(tar, "./usr/lib/thumbtrek/thumbtrek-run", run, 0o755)
        host = (LINUX / "bin" / "thumbtrek-native-host").read_bytes()
        _add_file(tar, "./usr/lib/thumbtrek/thumbtrek-native-host", host, 0o755)
        for src in sorted((LINUX / "thumbtrek").rglob("*.py")):
            _add_file(tar, f"./usr/lib/thumbtrek/thumbtrek/{src.name}", src.read_bytes(), 0o644)
        _add_tree(tar, REPO / "extension", "./usr/share/thumbtrek/extension")
        for font in sorted((LINUX / "assets" / "fonts").glob("*.ttf")):
            _add_file(tar, f"./usr/share/fonts/thumbtrek/{font.name}",
                      font.read_bytes(), 0o644)
        svc = (LINUX / "systemd" / "thumbtrek-tracker.service").read_bytes()
        _add_file(tar, "./usr/lib/systemd/user/thumbtrek-tracker.service", svc, 0o644)
        desk = (LINUX / "desktop" / "thumbtrek.desktop").read_bytes()
        _add_file(tar, "./usr/share/applications/thumbtrek.desktop", desk, 0o644)
    with tarfile.open(fileobj=ctrl_buf, mode="w:xz") as tar:
        _add_file(tar, "./control", control, 0o644)

    deb = _ar_archive([("debian-binary", b"2.0\n"),
                       ("control.tar.xz", ctrl_buf.getvalue()),
                       ("data.tar.xz", data_buf.getvalue())])
    out_dir.mkdir(parents=True, exist_ok=True)
    dest = out_dir / f"{pkg}.deb"
    dest.write_bytes(deb)
    return dest


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    parser.add_argument("--out", default="dist")
    parser.add_argument("--arch", default="all")
    args = parser.parse_args()
    print(build(args.version, Path(args.out), args.arch))
