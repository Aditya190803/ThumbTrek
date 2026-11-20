package com.thumbtrek.app.social

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.weekKey
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Deletes a ThumbTrek account and everything it ever wrote to Firestore. Google Play
 * requires this to exist in the app.
 *
 * The order matters, and the web dashboard (web/app/account-delete.js) follows the same
 * order:
 *
 * 1. Groups. An owned group goes entirely (progress, code, group). In anyone else's group
 *    only this account's progress docs go, then the account leaves. Progress is queried
 *    first, never blind-deleted: the delete rule reads `resource.data.uid`, which doesn't
 *    exist for a missing doc.
 * 2. Friend edges on both sides. The rules allow deleting your own edge on the other
 *    person's list for exactly this reason.
 * 3. The friend code, only when it still points at this account.
 * 4. The private day ledger (every source, not just this phone) and the shared limit.
 * 5. This account's row in every weekly archive since the account was created. There's no
 *    index to find them, so we delete one per week. Deleting a row that never existed is
 *    allowed and costs nothing.
 * 6. The board row `users/{uid}`, last, because the subcollections above hang off it.
 * 7. The Firebase Auth user.
 *
 * `entitlements/{uid}` stays. Clients can't write it, and if Premium was ever bought it's
 * a billing record. web/delete-account.html says so.
 *
 * Local scroll history is not touched. It never left the phone through this account, and
 * the app still works without one.
 */
class AccountDeletion(context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = Prefs.get(context)

    /**
     * Runs the whole deletion. [reauthenticate] is called at most once, when Firebase
     * says the sign-in is too old to delete the user. It should ask Google again.
     */
    suspend fun deleteAccount(reauthenticate: suspend () -> Unit) {
        val user = auth.currentUser ?: return
        val uid = user.uid

        // Ask for fresh sign-in before touching any data. Otherwise a stale session
        // would wipe Firestore and then fail at the auth step, leaving an empty account.
        val sinceSignIn = System.currentTimeMillis() - (user.metadata?.lastSignInTimestamp ?: 0L)
        if (sinceSignIn > RECENT_SIGN_IN_MS) reauthenticate()

        deleteGroups(uid)
        deleteFriendEdges(uid)
        deleteFriendCode(uid)
        deleteAll(db.collection("users").document(uid).collection("days").get().await()
            .documents.map { it.reference })
        db.collection("users").document(uid).collection("settings").document("limits")
            .delete().await()
        val created = user.metadata?.creationTimestamp
            // A day early: a phone in a zone ahead of UTC may have filed the first week
            // under the previous ISO week. Matches web/app/account-delete.js.
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1) }
            ?: LocalDate.now().minusYears(ARCHIVE_FALLBACK_YEARS)
        deleteAll(archiveWeeks(created, LocalDate.now()).map {
            db.collection("archive").document(it).collection("scores").document(uid)
        })
        db.collection("users").document(uid).delete().await()

        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            reauthenticate()
            user.delete().await()
        }

        prefs.setLeaderboardOptIn(false)
        prefs.clearSyncState()
    }

    private suspend fun deleteGroups(uid: String) {
        val groups = db.collection("groups")
        val mine = groups.whereArrayContains("members", uid).get().await().documents
        for (group in mine) {
            val progress = group.reference.collection("progress")
            if (group.getString("owner") == uid) {
                val refs = progress.get().await().documents.map { it.reference }.toMutableList()
                // The code's delete rule reads the stored groupId, so a missing or
                // reassigned code would fail the whole batch. Check before adding it.
                group.getString("code")?.takeIf { it.isNotEmpty() }?.let { code ->
                    val index = db.collection("groupCodes").document(code)
                    if (index.get().await().getString("groupId") == group.id) refs += index
                }
                // The group goes in the last batch, so that a failure halfway leaves a
                // group the owner can still see and delete again.
                deleteAll(refs + group.reference)
            } else {
                deleteAll(progress.whereEqualTo("uid", uid).get().await().documents
                    .map { it.reference })
                group.reference.update("members", FieldValue.arrayRemove(uid)).await()
            }
        }
    }

    private suspend fun deleteFriendEdges(uid: String) {
        val users = db.collection("users")
        val friends = users.document(uid).collection("friends").get().await().documents
        deleteAll(friends.flatMap { edge ->
            listOf(edge.reference, users.document(edge.id).collection("friends").document(uid))
        })
    }

    private suspend fun deleteFriendCode(uid: String) {
        val doc = db.collection("friendCodes").document(friendCode(uid))
        val snapshot = doc.get().await()
        if (snapshot.exists() && snapshot.getString("uid") == uid) doc.delete().await()
    }

    /** In order, at most [BATCH_LIMIT] per commit. */
    private suspend fun deleteAll(refs: List<DocumentReference>) {
        for (chunk in refs.chunked(BATCH_LIMIT)) {
            val batch = db.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
        }
    }

    private companion object {
        /** Firebase's window is about five minutes; stay well inside it. */
        const val RECENT_SIGN_IN_MS = 3 * 60_000L

        /** Only used if Firebase doesn't report a creation time. */
        const val ARCHIVE_FALLBACK_YEARS = 3L
    }
}

/** Every ISO week key from [from]'s week through [to]'s week, inclusive, oldest first. */
fun archiveWeeks(from: LocalDate, to: LocalDate): List<String> {
    if (from.isAfter(to)) return emptyList()
    val keys = LinkedHashSet<String>()
    var day = from
    while (!day.isAfter(to)) {
        keys += weekKey(day)
        day = day.plusWeeks(1)
    }
    keys += weekKey(to)
    return keys.toList()
}
