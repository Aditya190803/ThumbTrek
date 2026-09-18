import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

LINUX = Path(__file__).resolve().parents[1]
HOST = LINUX / "bin" / "thumbtrek-native-host"


def _frame(payload: dict) -> bytes:
    body = json.dumps(payload).encode()
    return struct.pack("<I", len(body)) + body


def _read_frame(stream) -> dict:
    (length,) = struct.unpack("<I", stream.read(4))
    return json.loads(stream.read(length).decode())


class NativeHostTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.env = dict(os.environ, THUMBTREK_DATA_HOME=self.tmp.name,
                        THUMBTREK_CONFIG_HOME=self.tmp.name)

    def test_batch_end_to_end(self):
        proc = subprocess.Popen(
            [sys.executable, str(HOST), "--browser", "native:firefox"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, env=self.env)
        msgs = [
            {"type": "trek/batch", "date": "2026-09-18",
             "domain": "youtube.com", "um": 25400},
            {"type": "trek/heartbeat"},
            {"type": "trek/batch", "date": "not-a-date",
             "domain": "youtube.com", "um": 5},
            {"type": "trek/batch", "date": "2026-09-18",
             "domain": "evil..domain", "um": 5},
            {"type": "trek/batch", "date": "2026-09-18",
             "domain": "youtube.com", "um": 1.5},
            {"nope": True},
        ]
        stdin_data = b"".join(_frame(m) for m in msgs)
        out, _ = proc.communicate(stdin_data, timeout=30)
        replies = []
        stream = __import__("io").BytesIO(out)
        for _ in msgs:
            replies.append(_read_frame(stream))
        self.assertEqual(proc.returncode, 0)
        self.assertEqual({"ok": True, "counted": 25400}, replies[0])
        self.assertEqual({"ok": True}, replies[1])
        for bad in replies[2:]:
            self.assertFalse(bad["ok"])
        # 25400µm == 1 inch == 96 CSS px in the shared ledger.
        sys.path.insert(0, str(LINUX))
        from thumbtrek.store import Store
        store = Store(Path(self.tmp.name) / "thumbtrek.db")
        try:
            self.assertEqual({"youtube.com": 96}, store.day_split("2026-09-18"))
        finally:
            store.close()
        links = json.loads((Path(self.tmp.name) / "browser-links.json").read_text())
        self.assertIn("native:firefox", links)

    def test_handle_rejects_absurd_um(self):
        from thumbtrek.store import Store
        store = Store(Path(self.tmp.name) / "t.db")
        try:
            import importlib.util
            from importlib.machinery import SourceFileLoader
            loader = SourceFileLoader("native_host", str(HOST))
            spec = importlib.util.spec_from_loader("native_host", loader)
            module = importlib.util.module_from_spec(spec)
            with mock.patch.dict(os.environ, {"THUMBTREK_DATA_HOME": self.tmp.name}):
                spec.loader.exec_module(module)
                reply = module.handle({"type": "trek/batch", "browser": "x",
                                       "date": "2026-09-18", "domain": "a.com",
                                       "um": 10**13}, store, "")
                self.assertFalse(reply["ok"])
        finally:
            store.close()


if __name__ == "__main__":
    unittest.main()
