"""Firestore sync over HTTPS with stdlib urllib (no Firebase SDK).

Mirrors extension/lib/{auth,firestore,sync}.js: Google ID-token flow is left
to the user pasting a token is NOT required — instead the Linux client syncs
with a refresh_token obtained once via the extension-style device flow file,
then refreshes through securetoken.googleapis.com (tokens expire in 1h).

Writes follow the protocol exactly:
- day ledgers: users/{uid}/days/{date}__linux (wholly owned, set outright)
- board row: merge sources.linux, re-read, re-roll combined totals
- opt-out: delete own day docs, then delete row iff last writer else remove
  sources.linux and re-roll (protocol §5).
"""

from __future__ import annotations

import json
import time
import urllib.parse
import urllib.request

from .sync_model import SOURCE_LINUX, CombinedTotals, combine, parse_sources

FIRESTORE = "https://firestore.googleapis.com/v1"
SECURETOKEN = "https://securetoken.googleapis.com/v1/token"
TIMEOUT_S = 30


class SyncError(RuntimeError):
    pass


def _api_key() -> str:
    import os
    key = os.environ.get("THUMBTREK_FIREBASE_API_KEY", "")
    if not key:
        raise SyncError("Set THUMBTREK_FIREBASE_API_KEY (Firebase web API key) to sync.")
    return key


def refresh_id_token(refresh_token: str) -> tuple[str, str, int]:
    body = urllib.parse.urlencode(
        {"grant_type": "refresh_token", "refresh_token": refresh_token}).encode()
    req = urllib.request.Request(
        f"{SECURETOKEN}?key={_api_key()}", data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            payload = json.load(resp)
    except OSError as exc:
        raise SyncError(f"token refresh failed: {exc}") from exc
    return (payload["id_token"], payload.get("refresh_token", refresh_token),
            int(time.time()) + int(payload.get("expires_in", 3600)) - 300)


def _authed(path: str, id_token: str, method="GET", payload=None) -> dict:
    req = urllib.request.Request(
        f"{FIRESTORE}{path}", method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": f"Bearer {id_token}",
                 "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            raw = resp.read().decode()
            return json.loads(raw) if raw else {}
    except OSError as exc:
        raise SyncError(f"firestore {method} {path} failed: {exc}") from exc


def _int_field(value: int) -> dict:
    if value != int(value):
        raise SyncError("distances must be integer µm, never floats")
    return {"integerValue": str(int(value))}


def _doc_id_for_day(day: str) -> str:
    return f"{day}__{SOURCE_LINUX}"


def push_day(uid: str, id_token: str, project: str, ledger) -> None:
    apps = {k: _int_field(v) for k, v in list(ledger.apps.items())[:64]}
    fields = {
        "date": {"stringValue": ledger.date},
        "source": {"stringValue": SOURCE_LINUX},
        "um": _int_field(ledger.um),
        "apps": {"mapValue": {"fields": apps}},
        "updatedAt": {"timestampValue": time.strftime(
            "%Y-%m-%dT%H:%M:%SZ", time.gmtime())},
    }
    doc = f"/projects/{project}/databases/(default)/documents/users/{uid}/days/{_doc_id_for_day(ledger.date)}"
    _authed(f"{doc}?updateMask.fieldPaths=date&updateMask.fieldPaths=source"
            f"&updateMask.fieldPaths=um&updateMask.fieldPaths=apps"
            f"&updateMask.fieldPaths=updatedAt",
            id_token, method="PATCH", payload={"fields": fields})


def sync_board_row(uid: str, id_token: str, project: str, mine: dict,
                   identity: dict, week_key: str, month_key: str) -> CombinedTotals:
    """Merge sources.linux, re-read, re-roll combined (protocol §3 steps 1-2)."""
    row = f"/projects/{project}/databases/(default)/documents/users/{uid}"
    mine_wrapped = {"mapValue": {"fields": mine}}
    _authed(f"{row}?updateMask.fieldPaths=sources.{SOURCE_LINUX}",
            id_token, method="PATCH",
            payload={"fields": {"sources": {"mapValue": {"fields": {
                SOURCE_LINUX: mine_wrapped}}}}})
    got = _authed(row, id_token)
    fields = got.get("fields", {})
    raw_sources = {}
    for slug, wrapped in (fields.get("sources", {}).get("mapValue", {}).get("fields", {}) or {}).items():
        inner = wrapped.get("mapValue", {}).get("fields", {})
        entry = {}
        for key in ("weekKey", "monthKey"):
            val = inner.get(key, {}).get("stringValue")
            if isinstance(val, str):
                entry[key] = val
        for key in ("weekUm", "monthUm", "totalUm"):
            try:
                entry[key] = int(inner.get(key, {}).get("integerValue", "0"))
            except ValueError:
                entry[key] = 0
        raw_sources[slug] = entry
    combined = combine(parse_sources(raw_sources), week_key, month_key)
    patch = {
        "fields": {
            "weekKey": {"stringValue": week_key},
            "monthKey": {"stringValue": month_key},
            "weekUm": _int_field(combined.week_um),
            "monthUm": _int_field(combined.month_um),
            "totalUm": _int_field(combined.total_um),
        }
    }
    if identity:
        patch["fields"].update(identity)
    _authed(f"{row}?updateMask.fieldPaths=weekKey&updateMask.fieldPaths=monthKey"
            f"&updateMask.fieldPaths=weekUm&updateMask.fieldPaths=monthUm"
            f"&updateMask.fieldPaths=totalUm"
            + "".join(f"&updateMask.fieldPaths={k}" for k in identity),
            id_token, method="PATCH", payload=patch)
    return combined


def delete_day(uid: str, id_token: str, project: str, day: str) -> None:
    doc = f"/projects/{project}/databases/(default)/documents/users/{uid}/days/{_doc_id_for_day(day)}"
    try:
        _authed(doc, id_token, method="DELETE")
    except SyncError:
        pass  # already gone is fine
