package com.thumbtrek.app.social

import com.thumbtrek.app.stats.weekKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Private groups and challenges (sync-protocol §8). A group is a named set of up to
// MAX_GROUP_MEMBERS people who joined with its code. Each member publishes one number per
// source into the group: their distance inside the group's current window. The window is
// the running challenge when there is one, and the current ISO week otherwise.
//
// Everything in this file is pure; GroupsRepository does the Firestore half.

const val MAX_GROUP_MEMBERS = 50
const val MAX_GROUP_NAME = 40
const val MAX_CHALLENGE_TITLE = 60
const val MAX_CHALLENGE_DAYS = 92

enum class ChallengeKind(val id: String, val label: String) {
    /** Lowest distance wins: the point of a limit, played against friends. */
    LEAST("least", "Scroll the least"),
    MOST("most", "Scroll the most"),
    ;

    companion object {
        fun of(id: String?): ChallengeKind = entries.firstOrNull { it.id == id } ?: LEAST
    }
}

data class Challenge(
    val title: String,
    val kind: ChallengeKind,
    val startsOn: LocalDate,
    val endsOn: LocalDate,
)

data class Group(
    val id: String,
    val name: String,
    val owner: String,
    val code: String,
    val members: List<String>,
    val challenge: Challenge? = null,
)

/** One member's distance from one source, as stored under the group. */
data class GroupProgress(
    val uid: String,
    val source: String,
    val name: String,
    val photo: String,
    val windowKey: String,
    val um: Long,
)

/** The stretch of days a group is currently counting. */
data class GroupWindow(
    /** Stamped on every progress document, so a stale one reads as nothing. */
    val key: String,
    val from: LocalDate,
    val to: LocalDate,
    val kind: ChallengeKind,
    val title: String,
    /** "Starts 3 Dec", "4 days left", "Ended 30 Nov", or "This week". */
    val status: String,
)

private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

/**
 * The window a group counts on [today]. A challenge that has not started yet or has
 * already ended still owns the window: the board shows it waiting or final, and the group
 * goes back to plain weeks only when the owner clears it.
 */
fun groupWindow(group: Group, today: LocalDate = LocalDate.now()): GroupWindow {
    val challenge = group.challenge
    if (challenge == null) {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return GroupWindow(weekKey(today), monday, monday.plusDays(6), ChallengeKind.MOST, "This week", "This week")
    }
    val status = when {
        today < challenge.startsOn -> "Starts ${challenge.startsOn.format(DAY)}"
        today > challenge.endsOn -> "Ended ${challenge.endsOn.format(DAY)}"
        else -> {
            val left = (challenge.endsOn.toEpochDay() - today.toEpochDay()).toInt()
            when (left) {
                0 -> "Last day"
                1 -> "1 day left"
                else -> "$left days left"
            }
        }
    }
    return GroupWindow(
        key = "${challenge.startsOn}..${challenge.endsOn}",
        from = challenge.startsOn,
        to = challenge.endsOn,
        kind = challenge.kind,
        title = challenge.title,
        status = status,
    )
}

/** This source's distance inside [window], from its own per-day totals in micrometres. */
fun windowUm(dayUm: Map<String, Long>, window: GroupWindow): Long =
    dayUm.entries.sumOf { (date, um) ->
        val day = runCatching { LocalDate.parse(date) }.getOrNull()
        if (day != null && day >= window.from && day <= window.to) um else 0L
    }

/** One row of a group's board. [um] is null for a member who has published nothing yet. */
data class GroupStanding(
    val uid: String,
    val name: String,
    val photo: String,
    val um: Long?,
    val isMe: Boolean,
)

/**
 * Ranks a group. A member's sources are summed, but only the documents stamped with the
 * current window count, so last week's total never sits in this week's board. Members
 * with nothing published go last whichever way the challenge runs: in a "least" challenge
 * an empty row must not win by never having synced.
 */
fun groupStandings(
    group: Group,
    progress: List<GroupProgress>,
    window: GroupWindow,
    myUid: String?,
): List<GroupStanding> {
    val current = progress.filter { it.windowKey == window.key && it.uid in group.members }
    val byUid = current.groupBy { it.uid }
    // Any document names the member, even a stale one: a name beats "Member".
    val names = progress.groupBy { it.uid }
    val rows = group.members.map { uid ->
        val mine = byUid[uid]
        val named = (mine ?: names[uid])?.firstOrNull { it.name.isNotBlank() }
        GroupStanding(
            uid = uid,
            name = named?.name ?: "Member",
            photo = named?.photo.orEmpty(),
            um = mine?.sumOf { it.um },
            isMe = uid == myUid,
        )
    }
    val (ranked, waiting) = rows.partition { it.um != null }
    val ordered = if (window.kind == ChallengeKind.LEAST) {
        ranked.sortedWith(compareBy<GroupStanding> { it.um }.thenBy { it.name })
    } else {
        ranked.sortedWith(compareByDescending<GroupStanding> { it.um }.thenBy { it.name })
    }
    return ordered + waiting.sortedBy { it.name }
}

/** Checks a challenge before it is written; returns the problem in words, or null. */
fun challengeProblem(title: String, startsOn: LocalDate, endsOn: LocalDate): String? = when {
    title.isBlank() -> "Give the challenge a name."
    title.length > MAX_CHALLENGE_TITLE -> "Keep the name under $MAX_CHALLENGE_TITLE characters."
    endsOn < startsOn -> "The last day has to be on or after the first."
    endsOn.toEpochDay() - startsOn.toEpochDay() >= MAX_CHALLENGE_DAYS ->
        "A challenge can run for up to $MAX_CHALLENGE_DAYS days."
    else -> null
}

private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

/** A fresh group code: eight Crockford characters, the same shape as a friend code. */
fun newGroupCode(random: java.util.Random = java.security.SecureRandom()): String =
    buildString { repeat(FRIEND_CODE_LENGTH) { append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)]) } }
