import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import version


class VersionTest(unittest.TestCase):
    def test_display_marks_the_port(self):
        self.assertEqual(f"{version.__version__}-{version.VARIANT}",
                         version.DISPLAY_VERSION)
        self.assertTrue(version.DISPLAY_VERSION.endswith("-Linux"))

    def test_packaging_version_stays_numeric(self):
        # PKGBUILD/RPM/deb forbid hyphens; they must keep reading __version__.
        self.assertRegex(version.__version__, r"^[0-9][0-9A-Za-z.]*$")
        self.assertNotIn("-", version.__version__)
        self.assertGreater(version.VERSION_CODE, 0)


if __name__ == "__main__":
    unittest.main()
