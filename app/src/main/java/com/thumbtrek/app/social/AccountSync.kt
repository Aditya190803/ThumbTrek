package com.thumbtrek.app.social

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.thumbtrek.app.data.DailyScroll
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.LimitSync
import com.thumbtrek.app.stats.SharedLimit
import com.thumbtrek.app.stats.metresToUm
import com.thumbtrek.app.stats.reconcileLimit
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/**
 * The account-level extras that ride along with a score sync: the shared limit
 * (sync-protocol §7), groups and challenges (§8), and the entitlement record (§9).
 *
 * Kept apart from [SocialRepository] because none of it touches the board row. The same
 * opt-in rule applies: nothing here writes until sync is on, and signing in alone
 * publishes nothing.
 *
 * users/{uid}/settings/limits = { limitUm, setAt, source, updatedAt }
 * groups/{groupId} = { name, owner, code, members[], createdAt, challenge? }
 * groups/{groupId}/progress/{uid}__{source} = { uid, source, name, photo, windowKey, um, updatedAt }
 * groupCodes/{code} = { groupId }
 * entitlements/{uid} = { premium, until }   (written by the billing backend only)
 */
class AccountSync(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = Prefs.get(context)

    private val uid: String? get() = auth.currentUser?.uid
    private val syncing: Boolean get() = uid != null && prefs.leaderboardOptIn.value
    private val densityDpi: Int get() = context.resources.displayMetrics.densityDpi

    // --- shared limit --------------------------------------------------------------

    private fun limitsDoc(current: String) =
        db.collection("users").document(current).collection("settings").document("limits")

    /**
     * Brings this phone's limit and the account's into agreement, newest edit winning,
     * then refreshes what the other sources measured today. No-op unless the user turned
     * the shared limit on.
     */
    suspend fun syncSharedLimit() {
        val current = uid ?: return
        if (!syncing || !prefs.sharedLimit.value) return
        val local = SharedLimit(
            limitUm = prefs.dailyLimitM.value?.let { metresToUm(it.toDouble()) } ?: 0L,
            setAt = prefs.limitSetAt,
        )
        val snapshot = limitsDoc(current).get().await()
        val remote = if (snapshot.exists()) {
            SharedLimit(snapshot.long("limitUm"), snapshot.long("setAt"))
        } else {
            null
        }
        when (val action = reconcileLimit(local, remote)) {
            is LimitSync.Push -> limitsDoc(current).set(
                mapOf(
                    "limitUm" to action.limit.limitUm,
                    "setAt" to action.limit.setAt,
                    "source" to SOURCE_ANDROID,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
            is LimitSync.Adopt -> prefs.adoptSharedLimit(
                limitM = action.limit.limitUm.takeIf { it > 0L }?.let { micrometresToMeters(it).toFloat() },
                setAt = action.limit.setAt,
            )
            LimitSync.InSync -> Unit
        }
        refreshOtherSources(current)
    }

    /** Today's distance from every source that is not this phone, as of their last sync. */
    private suspend fun refreshOtherSources(current: String) {
        val today = LocalDate.now().toString()
        val um = db.collection("users").document(current).collection("days")
            .whereEqualTo("date", today)
            .get().await().documents
            .filter { it.getString("source") != SOURCE_ANDROID }
            .sumOf { it.long("um") }
        prefs.setOtherSourcesToday(today, um)
    }

    /** Turning the shared limit off takes the account's copy away; each device keeps its own. */
    suspend fun withdrawSharedLimit() {
        val current = uid ?: return
        runCatching { limitsDoc(current).delete().await() }
    }

    // --- groups --------------------------------------------------------------------

    private val groups get() = db.collection("groups")

    suspend fun myGroups(): List<Group> {
        val current = uid ?: return emptyList()
        if (!syncing) return emptyList()
        return groups.whereArrayContains("members", current).get().await().documents
            .mapNotNull { it.toGroup() }
            .sortedBy { it.name.lowercase() }
    }

    suspend fun createGroup(name: String): Group {
        val current = requireSync()
        val clean = name.trim().take(MAX_GROUP_NAME)
        require(clean.isNotEmpty()) { "Give the group a name." }
        check(premiumAllowed()) { "Groups are part of Premium." }
        val doc = groups.document()
        val code = newGroupCode()
        val batch = db.batch()
        batch.set(
            doc,
            mapOf(
                "name" to clean,
                "owner" to current,
                "code" to code,
                "members" to listOf(current),
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(db.collection("groupCodes").document(code), mapOf("groupId" to doc.id))
        batch.commit().await()
        return Group(doc.id, clean, current, code, listOf(current))
    }

    /** Joins by code. The code is the only way to learn a group's id. */
    suspend fun joinGroup(input: String): Group {
        val current = requireSync()
        val code = normalizeFriendCode(input)
        require(code.length == FRIEND_CODE_LENGTH) {
            "That code doesn't look right. Group codes are $FRIEND_CODE_LENGTH characters."
        }
        val groupId = db.collection("groupCodes").document(code).get().await().getString("groupId")
        require(!groupId.isNullOrEmpty()) { "No group has that code." }
        val doc = groups.document(groupId)
        // arrayUnion is the whole write: the rules accept exactly "members grew by me".
        doc.update("members", FieldValue.arrayUnion(current)).await()
        return doc.get().await().toGroup() ?: error("That group no longer exists.")
    }

    suspend fun leaveGroup(group: Group) {
        val current = requireSync()
        deleteMyProgress(group.id, current)
        groups.document(group.id).update("members", FieldValue.arrayRemove(current)).await()
    }

    /** Owner only. */
    suspend fun deleteGroup(group: Group) {
        requireSync()
        // Every member's progress goes first: deleting a document does not delete its
        // subcollections, and names left under a dead group could never be cleaned up.
        val batch = db.batch()
        groups.document(group.id).collection("progress").get().await().documents
            .forEach { batch.delete(it.reference) }
        batch.delete(db.collection("groupCodes").document(group.code))
        batch.delete(groups.document(group.id))
        batch.commit().await()
    }

    /** Owner only. Null clears the challenge and the group goes back to counting weeks. */
    suspend fun setChallenge(group: Group, challenge: Challenge?) {
        requireSync()
        val value: Any = challenge?.let {
            mapOf(
                "title" to it.title.trim().take(MAX_CHALLENGE_TITLE),
                "kind" to it.kind.id,
                "startsOn" to it.startsOn.toString(),
                "endsOn" to it.endsOn.toString(),
            )
        } ?: FieldValue.delete()
        groups.document(group.id).update("challenge", value).await()
    }

    suspend fun progress(group: Group): List<GroupProgress> =
        groups.document(group.id).collection("progress").get().await().documents.map {
            GroupProgress(
                uid = it.getString("uid").orEmpty(),
                source = it.getString("source").orEmpty(),
                name = it.getString("name").orEmpty(),
                photo = it.getString("photo").orEmpty(),
                windowKey = it.getString("windowKey").orEmpty(),
                um = it.long("um"),
            )
        }

    /**
     * Publishes this phone's distance into every group the user is in. Members see each
     * other's real names here, the same as on the friends board: a group is private to the
     * people who were handed its code.
     */
    suspend fun publishProgress(memberOf: List<Group>, rows: List<DailyScroll>) {
        val current = uid ?: return
        if (!syncing || memberOf.isEmpty()) return
        val dayUm = dayLedgers(rows, densityDpi).associate { it.date to it.um }
        val user = auth.currentUser
        val batch = db.batch()
        memberOf.forEach { group ->
            val window = groupWindow(group)
            batch.set(
                groups.document(group.id).collection("progress").document("${current}__$SOURCE_ANDROID"),
                mapOf(
                    "uid" to current,
                    "source" to SOURCE_ANDROID,
                    "name" to (user?.displayName ?: "Trekker").take(64),
                    "photo" to (user?.photoUrl?.toString().orEmpty()).take(512),
                    "windowKey" to window.key,
                    "um" to windowUm(dayUm, window),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
        batch.commit().await()
    }

    private suspend fun deleteMyProgress(groupId: String, current: String) {
        runCatching {
            groups.document(groupId).collection("progress")
                .document("${current}__$SOURCE_ANDROID").delete().await()
        }
    }

    /** Turning sync off withdraws this phone's number from every group, like the board row. */
    suspend fun withdrawFromGroups() {
        val current = uid ?: return
        groups.whereArrayContains("members", current).get().await().documents
            .forEach { deleteMyProgress(it.id, current) }
    }

    // --- entitlement ---------------------------------------------------------------

    /**
     * Whether Premium features are open to this account. While billing is not enforced the
     * answer is always yes: there is nothing to buy yet, so nothing is held back. Once it
     * is, the record is written by the billing backend and only read here.
     */
    suspend fun premiumAllowed(): Boolean {
        if (!BILLING_ENFORCED) return true
        val current = uid ?: return false
        val doc = db.collection("entitlements").document(current).get().await()
        return doc.getBoolean("premium") == true
    }

    private fun requireSync(): String {
        check(syncing) { "Sign in and turn sync on first." }
        return uid!!
    }
}

private fun DocumentSnapshot.long(key: String): Long = (get(key) as? Number)?.toLong() ?: 0L

private fun DocumentSnapshot.toGroup(): Group? {
    if (!exists()) return null
    val members = (get("members") as? List<*>)?.filterIsInstance<String>().orEmpty()
    val challenge = (get("challenge") as? Map<*, *>)?.let { raw ->
        val from = runCatching { LocalDate.parse(raw["startsOn"] as? String) }.getOrNull()
        val to = runCatching { LocalDate.parse(raw["endsOn"] as? String) }.getOrNull()
        if (from == null || to == null) null
        else Challenge((raw["title"] as? String).orEmpty(), ChallengeKind.of(raw["kind"] as? String), from, to)
    }
    return Group(
        id = id,
        name = getString("name").orEmpty(),
        owner = getString("owner").orEmpty(),
        code = getString("code").orEmpty(),
        members = members,
        challenge = challenge,
    )
}
