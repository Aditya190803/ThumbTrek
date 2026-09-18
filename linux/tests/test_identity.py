import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from thumbtrek import identity


class IdentityTest(unittest.TestCase):
    def test_friend_code_shape_and_stability(self):
        code = identity.friend_code("some-uid")
        self.assertEqual(8, len(code))
        self.assertEqual(code, identity.friend_code("some-uid"))
        self.assertTrue(all(c in identity.CODE_ALPHABET for c in code))
        self.assertNotIn("O", code)

    def test_normalize_accepts_links_and_typos(self):
        code = identity.friend_code("some-uid")
        link = f"https://thumbtrek.adityamer.dev/i/{code[:4].lower()}-{code[4:].lower()}"
        self.assertEqual(code, identity.normalize_friend_code(link))
        # Crockford confusables map like the Kotlin port
        self.assertEqual(
            identity.normalize_friend_code("oliu"),
            identity.normalize_friend_code("011V")[:4])

    def test_anonymous_handle_shape(self):
        handle = identity.anonymous_handle("some-uid")
        name, number = handle.rsplit(" #", 1)
        self.assertEqual(2, len(name.split(" ")))
        self.assertEqual(4, len(number))
        self.assertTrue(number.isdigit())


if __name__ == "__main__":
    unittest.main()
