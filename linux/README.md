# ThumbTrek for Linux (AUR-first)

Offline-first scroll tracker for Linux desktops with a **native GTK4 /
libadwaita window** (no web server, no Electron). Python + system GTK only —
no pip dependencies. Version mirrors the Android app
(`linux/thumbtrek/version.py`).

The window is a faithful replica of the Android app, laid out for a wide
screen: **Trek** (wordmark rail, hero dial with per-app slices, streak
badge, daily-limit panel, landmark reading, week glance, app split,
first-trek checklist), **History** (all-time total, 7d/8w/6m segmented
ranges, tappable bars with period detail, clean-month calendar, records,
app trends, checkpoint shelf, day log), **Social** (sign-in pitch;
leaderboard sign-in itself is still phone/web-only), **Settings**
(tracking status, tracked apps, limit editor, diagnostics, notifications,
CSV export, privacy, updates, version). Same Trek tokens (moss/amber/slate
on bone paper or dark field), same Space Grotesk + Manrope faces — the
packages install the TTFs system-wide, and checkout runs copy them to
`~/.local/share/fonts` on first launch. Bottom nav keeps the sliding trail
blaze. Dark mode follows the system.

## Try it without installing anything

```sh
cd ~/Git-Repos/thumbtrek
./linux/bin/thumbtrek status        # terminal summary, works anywhere
./linux/bin/thumbtrek dashboard     # native window (needs system GTK4 stack)
```

The launchers add `linux/` to `sys.path` themselves, so no install, no
venv, no root. System GTK stack required for the window only:

```sh
# Arch
sudo pacman -S gtk4 libadwaita python-gobject
# Debian/Ubuntu
sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1
# Fedora
sudo dnf install python3-gobject gtk4 libadwaita
```

Keep your real data untouched while experimenting — point the app at a
sandbox and seed a week of demo treks:

```sh
export THUMBTREK_DATA_HOME=./.scratch/demo THUMBTREK_CONFIG_HOME=./.scratch/demo
python3 - <<'EOF'
import sys; sys.path.insert(0, "linux")
from thumbtrek.store import Store
from datetime import date, timedelta
s = Store(); t = date.today()
for i, px in enumerate([40000, 90000, 12000, 150000, 30000, 60000, 20000]):
    s.accumulate("firefox", (t - timedelta(days=i)).isoformat(), px)
s.accumulate("kitty", t.isoformat(), 12000)
s.close(); print("seeded")
EOF
./linux/bin/thumbtrek dashboard      # explore Trek / History / Badges / Settings
./linux/bin/thumbtrek extension install   # works from a checkout too (uses repo extension/)
python3 -m unittest discover -s linux/tests   # headless suite; window test auto-skips without a display
```

Notes: `./linux/bin/thumbtrek daemon` runs in the foreground without the
package (needs `input` group for `/dev/input`, else it idles at zero —
safe). The extension side needs no install either: load `extension/` as an
unpacked extension from `chrome://extensions` (developer mode) or
`about:debugging`, then point the native bridge at your checkout with
`THUMBTREK_NATIVE_HOST=$PWD/linux/bin/thumbtrek-native-host`.

## Install

**Arch (primary, AUR):**
```sh
cp linux/packaging/PKGBUILD .
makepkg --printsrcinfo > .SRCINFO   # when publishing to AUR
makepkg -si
usermod -aG input "$USER"           # wheel tracking reads /dev/input (no root)
systemctl --user enable --now thumbtrek-tracker.service
```

**Debian/Ubuntu / Fedora** — grab the `.deb`/`.rpm` from GitHub Releases
(built by `.github/workflows/linux.yml` on every `v*` tag), or build the deb
locally with `python3 linux/packaging/build-deb.py --version 0.5.0 --out dist`.

## Use

```sh
thumbtrek status      # today's trek, streaks, badges
thumbtrek dashboard   # localhost page, opens in browser
thumbtrek daemon      # tracker (systemd runs this)
thumbtrek export --out t.csv   # Android-compatible date,app,pixels CSV
thumbtrek card --out card.svg  # shareable stat card
thumbtrek browsers             # list detected browsers
thumbtrek extension install    # pick a browser, link the web-extension
thumbtrek extension status     # staged copy, bridges, live links
```

## Do I need the extension?

No. The daemon tracks **standalone**: wheel motion from `/dev/input`
attributed to the focused app. Totals are exact with zero setup beyond the
`input` group. The extension is an accuracy upgrade, not a requirement —
it reports exact per-domain scroll for browser time, and while its link is
live the daemon stands down for that browser so nothing double-counts:

| Setup | Totals | Browser split |
|---|---|---|
| Daemon only | exact | one bucket per browser |
| Daemon + linked extension | exact | per site (`youtube.com`, …) |
| Extension only (no `/dev/input`) | browser time only | per site |

`thumbtrek extension status` reports which mode you are in.

## Social feeds: the same four, measured as sites

The phone tracks Instagram, YouTube, X and Reddit as native apps; on Linux
those feeds live in the browser, so the linked extension measures
`instagram.com`, `youtube.com`, `x.com` and `reddit.com` — on by default,
same as the phone. The Linux client renders them under the same names
everywhere (dashboard split, trends, day detail, diagnostics, and the
Social-feeds panel in Settings), aggregated from the per-domain ledger.
Unlinked browser time cannot be attributed to a feed and stays under the
browser's bucket — that is a measurement limit, not a product difference.

## Browser extension linking (accurate web tracking)

The daemon's wheel-event attribution is approximate for browsers (one
`firefox` bucket, no per-site split). Linking the extension fixes that:

```sh
thumbtrek extension install
```

1. **Detect** — scans PATH binaries, `.desktop` files, `flatpak list`,
   `snap list` + `/snap/bin`, `~/Applications`/`/opt` AppImages, and
   well-known tarball homes (`~/zen`, `/opt/zen*`, …).
   Covered: Chrome, Chromium (+Ungoogled), Brave, Edge, Opera, Vivaldi,
   Firefox (+Developer/Nightly/ESR), LibreWolf, Zen, Floorp, Waterfox,
   Whale, Thorium — including `-beta`/`-dev`/`-bin` channel variants, which
   match on the binary stem. You pick from the numbered list, in the
   terminal **or** in Settings → Browser extension.
   Still missed? `thumbtrek browsers -v` shows every scanned location and
   per-source hit counts — that output tells us exactly where yours hides.
2. **Stage** — a stable copy of `extension/` lands in
   `~/.local/share/thumbtrek/extension` (packaged: `/usr/share/...`).
3. **Bridge** — a `com.thumbtrek.native` native-messaging manifest is
   written to the right per-browser location (native `~/.config/...`,
   flatpak `~/.var/app/...`, snap `~/snap/...`), so the extension reports
   exact per-domain µm over stdio from the first run.
4. **Load** — the extensions page opens with a 3-step guide. Chromium gets
   an optional one-click `--load-extension` launch; Firefox uses
   `about:debugging` (temporary add-on — a Firefox rule, reprintable).

While a link is live the daemon drops its own evdev counts for that
browser (heartbeat in `browser-links.json`, 120 s freshness), so the same
scroll is never counted twice. Untracked domains stay daemon-counted.

Silent install is deliberately *not* offered: browsers forbid it (malware
path). This is the same honest-limit policy as the self-updater section.

Something still missed? `thumbtrek browsers` shows exactly what was found.
For the rest there is an escape hatch in
`~/.config/thumbtrek/config.json` — entries need a display name, an engine,
and a launch command; slugs default to the launcher basename:

```json
"custom_browsers": [
  {"name": "My Browser", "engine": "chromium",
   "launch": ["/opt/mine/mine", "--user-data-dir=/home/you/.mine"]},
  {"name": "Work Firefox", "engine": "firefox",
   "launch": ["firefox", "-P", "work"], "slugs": ["firefox"]}
]
```

## How tracking works (and its honest limit)

1. Wheel motion is read from `/dev/input/event*` (`REL_WHEEL`/`HIRES_WHEEL`
   only — key codes are never touched, so this cannot keylog).
2. Each batch is attributed to the focused app (`xprop` on X11) and counted
   only if that app is in your tracked set — the same filter-before-count
   rule as Android's `ScrollTrackerService`.
3. Batches upsert into SQLite (`~/.local/share/thumbtrek/thumbtrek.db`,
   one row per app per day); metres are computed at display time.

**Wayland note:** compositors forbid global input snooping, so the focused
app reads as `unknown` and unattributed motion lands in an honestly-labeled
`unknown` bucket (tracked by default) instead of vanishing; totals stay
exact. The browser extension remains the precise per-site source.

**Nothing recorded?** Run `thumbtrek doctor` — it checks input access, whether
the daemon is running, today's ledger, and whether the focused app is
tracked, and says exactly which step is missing.

## Sync contract

New source slug **`linux`** (`thumbtrek/sync_model.py`). Old clients are
unaffected: `combine()` sums every slug it sees, and the per-source
`weekKey` staleness rule (protocol §3) applies unchanged. Day ledgers are
`users/{uid}/days/{date}__linux`; opt-out deletes those, then deletes the
row only if last writer, else removes `sources.linux` and re-rolls —
protocol §5 verbatim. Nothing reaches Firestore until leaderboard opt-in.

## Security notes

- Least privilege: `input` group for wheel events, `NoNewPrivileges` +
  `ProtectSystem=strict` in the user unit; config (tokens) is mode 0600.
- No window content is captured — only the focused window's class name.
- Sync uses HTTPS with default cert validation; distances are integer µm
  (floats rejected); app breakdowns capped at 64 keys; subprocess calls use
  arg lists, never shell.
