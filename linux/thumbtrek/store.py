"""SQLite store (~/.local/share/thumbtrek/thumbtrek.db).

Schema mirrors Room's daily_scroll(packageName, date, pixels) so CSV export
stays byte-compatible with the Android app (date,app,pixels).
"""

from __future__ import annotations

import os
import sqlite3
from pathlib import Path


def _db_path() -> Path:
    root = os.environ.get("THUMBTREK_DATA_HOME")
    base = Path(root) if root else Path.home() / ".local" / "share" / "thumbtrek"
    base.mkdir(parents=True, exist_ok=True)
    return base / "thumbtrek.db"


class Store:
    def __init__(self, path: Path | None = None):
        self.path = Path(path) if path else _db_path()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = sqlite3.connect(str(self.path))
        self.conn.execute(
            "CREATE TABLE IF NOT EXISTS daily_scroll("
            "package_name TEXT NOT NULL, date TEXT NOT NULL, pixels INTEGER NOT NULL DEFAULT 0, "
            "PRIMARY KEY (package_name, date)) WITHOUT ROWID")

    def close(self):
        self.conn.close()

    def accumulate(self, package_name: str, day: str, pixels: int) -> None:
        if pixels <= 0 or not package_name or not day:
            return
        self.conn.execute(
            "INSERT INTO daily_scroll(package_name, date, pixels) VALUES(?,?,?) "
            "ON CONFLICT(package_name, date) DO UPDATE SET pixels = pixels + excluded.pixels",
            (package_name, day, int(pixels)))
        self.conn.commit()

    def day_total(self, day: str) -> int:
        row = self.conn.execute(
            "SELECT COALESCE(SUM(pixels),0) FROM daily_scroll WHERE date=?", (day,)).fetchone()
        return int(row[0])

    def day_split(self, day: str) -> dict[str, int]:
        return {r[0]: int(r[1]) for r in self.conn.execute(
            "SELECT package_name, pixels FROM daily_scroll WHERE date=?", (day,))}

    def days(self) -> dict[str, int]:
        return {r[0]: int(r[1]) for r in self.conn.execute(
            "SELECT date, SUM(pixels) FROM daily_scroll GROUP BY date")}

    def rows_since(self, day: str) -> list[tuple[str, str, int]]:
        return [(r[0], r[1], int(r[2])) for r in self.conn.execute(
            "SELECT package_name, date, pixels FROM daily_scroll WHERE date >= ? "
            "ORDER BY date, package_name", (day,))]

    def all_rows(self) -> list[tuple[str, str, int]]:
        return [(r[0], r[1], int(r[2])) for r in self.conn.execute(
            "SELECT package_name, date, pixels FROM daily_scroll ORDER BY date, package_name")]

    def export_csv(self, dest: Path) -> Path:
        dest = Path(dest)
        lines = ["date,app,pixels"]
        for pkg, day, px in self.all_rows():
            safe = pkg.replace('"', '""')
            lines.append(f'{day},"{safe}",{px}')
        dest.write_text("\n".join(lines) + "\n")
        try:
            os.chmod(dest, 0o600)
        except OSError:
            pass
        return dest
