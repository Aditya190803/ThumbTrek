package com.thumbtrek.app.social

import java.security.MessageDigest
import java.util.Locale

/**
 * The public half of a trekker's identity, all derived from the Firebase uid so it is
 * stable across reinstalls and needs no extra storage: a shareable friend code for
 * invites, and an anonymised handle for people who don't want their Google name on the
 * board (PRD §5.4).
 */

/** Crockford base32: no I, L, O or U, so a code can't be misread or misheard. */
private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** 8 symbols = 40 bits. Collision odds stay negligible well past a million trekkers. */
const val FRIEND_CODE_LENGTH = 8

private fun digest(purpose: String, uid: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest("thumbtrek:$purpose:$uid".toByteArray())

fun friendCode(uid: String): String {
    val bytes = digest("code", uid)
    return buildString(FRIEND_CODE_LENGTH) {
        for (i in 0 until FRIEND_CODE_LENGTH) {
            append(CODE_ALPHABET[bytes[i].toInt() and 0x1F])
        }
    }
}

fun formatFriendCode(code: String): String =
    if (code.length == FRIEND_CODE_LENGTH) "${code.take(4)}-${code.drop(4)}" else code

/**
 * Accepts what people actually paste: lower case, spaces, the grouping dash, or the
 * whole `https://thumbtrek.app/i/<code>` invite link.
 */
fun normalizeFriendCode(input: String): String =
    input.trim()
        .substringAfterLast('/')
        .uppercase(Locale.US)
        .filter { it.isLetterOrDigit() }
        .map {
            when (it) {
                'I', 'L' -> '1'
                'O' -> '0'
                'U' -> 'V'
                else -> it
            }
        }
        .joinToString("")
        .take(FRIEND_CODE_LENGTH)

private val ANON_ADJECTIVES = listOf(
    "Silent", "Feral", "Midnight", "Restless", "Caffeinated", "Sneaky", "Blurry",
    "Infinite", "Rogue", "Turbo", "Velvet", "Nocturnal", "Bottomless", "Unbothered",
    "Wandering", "Relentless",
)

private val ANON_NOUNS = listOf(
    "Scroller", "Thumb", "Trekker", "Swiper", "Lurker", "Wanderer", "Nomad",
    "Voyager", "Flicker", "Drifter",
)

/** Stable pseudonym for a uid, e.g. "Silent Scroller #4821". */
fun anonymousHandle(uid: String): String {
    val bytes = digest("handle", uid)
    val adjective = ANON_ADJECTIVES[(bytes[0].toInt() and 0xFF) % ANON_ADJECTIVES.size]
    val noun = ANON_NOUNS[(bytes[1].toInt() and 0xFF) % ANON_NOUNS.size]
    val number = (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)) % 10_000
    return String.format(Locale.US, "%s %s #%04d", adjective, noun, number)
}

fun inviteLink(code: String): String = "https://thumbtrek.app/i/$code"

fun inviteMessage(code: String): String =
    "I'm tracking how far my thumb scrolls with ThumbTrek. Add me with code " +
        "${formatFriendCode(code)} and let's see who treks further this week.\n\n" +
        inviteLink(code)
