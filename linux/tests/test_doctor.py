import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import config as config_mod
from thumbtrek.cli import cmd_doctor


class DoctorTest(unittest.TestCase):
    def test_defaults_cover_detectable_browsers(self):
        tracked = config_mod.DEFAULT_TRACKED_APPS
        for slug in ("zen", "zen-browser", "librewolf", "floorp", "waterfox",
                     "vivaldi", "opera", "unknown"):
            self.assertIn(slug, tracked)

    def test_doctor_reports_state(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        with mock.patch.dict(os.environ, {
                "THUMBTREK_DATA_HOME": tmp.name,
                "THUMBTREK_CONFIG_HOME": tmp.name}):
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = cmd_doctor()
            out = buf.getvalue()
        self.assertIn(code, (0, 1))
        for needle in ("session:", "input:", "daemon:", "today:", "tracked:"):
            self.assertIn(needle, out, needle)


if __name__ == "__main__":
    unittest.main()
