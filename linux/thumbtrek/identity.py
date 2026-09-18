"""Identity — exact Python port of app/.../social/Identity.kt (§4 of sync protocol).

friendCode / anonymousHandle must match byte-for-byte or the Linux client
splits the user's identity in half. Covered by linux/tests/test_identity.py.
"""

from __future__ import annotations

import hashlib

CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
FRIEND_CODE_LENGTH = 8

_ANON_ADJECTIVES = [
    "Silent", "Feral", "Midnight", "Restless", "Caffeinated", "Sneaky", "Blurry",
    "Infinite", "Rogue", "Turbo", "Velvet", "Nocturnal", "Bottomless", "Unbothered",
    "Wandering", "Relentless",
]
_ANON_NOUNS = [
    "Scroller", "Thumb", "Trekker", "Swiper", "Lurker", "Wanderer", "Nomad",
    "Voyager", "Flicker", "Drifter",
]


def _digest(purpose: str, uid: str) -> bytes:
    return hashlib.sha256(f"thumbtrek:{purpose}:{uid}".encode()).digest()


def friend_code(uid: str) -> str:
    raw = _digest("code", uid)
    return "".join(CODE_ALPHABET[b & 0x1F] for b in raw[:FRIEND_CODE_LENGTH])


def format_friend_code(code: str) -> str:
    return f"{code[:4]}-{code[4:]}" if len(code) == FRIEND_CODE_LENGTH else code


def normalize_friend_code(user_input: str) -> str:
    tail = user_input.strip().split("/")[-1].upper()
    out = []
    for ch in tail:
        if not ch.isalnum():
            continue
        if ch in ("I", "L"):
            ch = "1"
        elif ch == "O":
            ch = "0"
        elif ch == "U":
            ch = "V"
        out.append(ch)
    return "".join(out)[:FRIEND_CODE_LENGTH]


def anonymous_handle(uid: str) -> str:
    raw = _digest("handle", uid)
    adj = _ANON_ADJECTIVES[raw[0] % len(_ANON_ADJECTIVES)]
    noun = _ANON_NOUNS[raw[1] % len(_ANON_NOUNS)]
    number = ((raw[2] << 8) | raw[3]) % 10_000
    return f"{adj} {noun} #{number:04d}"


def invite_link(code: str) -> str:
    return f"https://thumbtrek.adityamer.dev/i/{code}"


def invite_message(code: str) -> str:
    return (
        "I'm tracking how far my thumb scrolls with ThumbTrek. Add me with code "
        f"{format_friend_code(code)} and let's see who treks further this week.\n\n"
        + invite_link(code)
    )
