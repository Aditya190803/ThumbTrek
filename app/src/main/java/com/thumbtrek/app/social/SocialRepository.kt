package com.thumbtrek.app.social

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.thumbtrek.app.R
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.monthKey
import com.thumbtrek.app.stats.weekKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Which stretch of time a board covers. */
enum class Period(val label: String) {
    WEEK("This week"),
    MONTH("This month"),
    ALL_TIME("All time"),
}

data class LeaderboardEntry(
    val uid: String,
    val displayName: String,
    val photoUrl: String,
    val weekPixels: Long,
    val monthPixels: Long,
    val totalPixels: Long,
) {
    fun score(period: Period): Long = when (period) {
        Period.WEEK -> weekPixels
        Period.MONTH -> monthPixels
        Period.ALL_TIME -> totalPixels
    }
}

/** Someone who added your code and is waiting for you to accept. */
data class FriendRequest(val uid: String, val displayName: String, val photoUrl: String)

/** One of last week's top three, from the weekly archive. */
data class PodiumEntry(val displayName: String, val photoUrl: String, val pixels: Long)

/**
 * Firestore caps `whereIn` at 30 values, so friend lists are queried in chunks.
 */
private const val QUERY_CHUNK = 30

/** Global board page size; [MAX_PAGES] bounds how far "load more" can walk. */
private const val PAGE_SIZE = 50
private const val MAX_PAGES = 8

/**
 * Google Sign-In (Credential Manager) + score sync to Firestore.
 *
 * users/{uid} = { displayName, photoUrl, friendCode, weekKey, weekPixels,
 *                 monthKey, monthPixels, totalPixels }
 * users/{uid}/friends/{friendUid} = { since, status } — status is "accepted" or
 * "pending"; edges written before requests existed have no status field and read as
 * accepted, so old friendships survive without migration.
 * friendCodes/{code} = { uid } — the invite lookup index.
 * archive/{weekKey}/scores/{uid} = { displayName, photoUrl, pixels } — one frozen copy
 * of each week's board so last week's podium survives the reset (scores are otherwise
 * overwritten in place when weekKey rolls over).
 *
 * Nothing here writes anything until [Prefs.leaderboardOptIn] is on (PRD §11 Q2):
 * signing in by itself publishes no score.
 */
class SocialRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = Prefs.get(context)
    private val credentialManager = CredentialManager.create(context)

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid = _uid.asStateFlow()

    private var registeredCodeFor: String? = null

    /** Server-side pagination cursor for the global board; null once exhausted. */
    private var globalCursor: DocumentSnapshot? = null
    private var globalExhausted = false

    val displayName: String get() = auth.currentUser?.displayName ?: "Trekker"
    val photoUrl: String get() = auth.currentUser?.photoUrl?.toString() ?: ""

    /** What the board actually shows: the Google identity, or a stable pseudonym. */
    val publishedName: String
        get() {
            val current = auth.currentUser?.uid ?: return displayName
            return if (prefs.anonymous.value) anonymousHandle(current) else displayName
        }

    val publishedPhotoUrl: String get() = if (prefs.anonymous.value) "" else photoUrl

    val myFriendCode: String? get() = auth.currentUser?.uid?.let(::friendCode)

    /** [activityContext] must be an Activity: Credential Manager renders UI over it. */
    suspend fun signIn(activityContext: Context) {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(context.getString(R.string.default_web_client_id))
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val result = credentialManager.getCredential(activityContext, request)
        val google = GoogleIdTokenCredential.createFrom(result.credential.data)
        auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
        _uid.value = auth.currentUser?.uid
    }

    fun signOut() {
        auth.signOut()
        registeredCodeFor = null
        resetGlobalCursor()
        _uid.value = null
    }

    /**
     * Publishes all three scores. Week/month come from local history; total too — the
     * phone is the source of truth. No-op until the user opts in.
     */
    suspend fun syncScore(weekPixels: Long, monthPixels: Long, totalPixels: Long) {
        val current = auth.currentUser?.uid ?: return
        if (!prefs.leaderboardOptIn.value) return
        db.collection("users").document(current).set(
            mapOf(
                "displayName" to publishedName,
                "photoUrl" to publishedPhotoUrl,
                "friendCode" to friendCode(current),
                "weekKey" to weekKey(),
                "weekPixels" to weekPixels,
                "monthKey" to monthKey(),
                "monthPixels" to monthPixels,
                "totalPixels" to totalPixels,
            ),
            SetOptions.merge(),
        ).await()
        // Freeze this week's row into the archive so the podium outlives the reset.
        db.collection("archive").document(weekKey()).collection("scores").document(current)
            .set(
                mapOf(
                    "displayName" to publishedName,
                    "photoUrl" to publishedPhotoUrl,
                    "pixels" to weekPixels,
                ),
                SetOptions.merge(),
            ).await()
        registerFriendCode(current)
    }

    /**
     * Publishes the code → uid mapping invites resolve against. Best effort: a code
     * already claimed by another account must not break score sync.
     */
    private suspend fun registerFriendCode(current: String) {
        if (registeredCodeFor == current) return
        runCatching {
            db.collection("friendCodes").document(friendCode(current))
                .set(mapOf("uid" to current))
                .await()
        }.onSuccess { registeredCodeFor = current }
    }

    /** Opting back out withdraws the score. Friend edges survive a later opt-in. */
    suspend fun unpublish() {
        val current = auth.currentUser?.uid ?: return
        db.collection("users").document(current).delete().await()
    }

    // --- friends ---------------------------------------------------------------

    /** Accepted friends only; edges predating request semantics count as accepted. */
    suspend fun friendUids(): List<String> {
        val current = auth.currentUser?.uid ?: return emptyList()
        return friendsOf(current).get().await().documents
            .filter { it.getString(FIELD_STATUS) != STATUS_PENDING }
            .map { it.id }
    }

    /** Requests I've received and not answered yet, newest bookkeeping aside. */
    suspend fun incomingRequests(): List<FriendRequest> {
        val current = auth.currentUser?.uid ?: return emptyList()
        val pending = friendsOf(current).get().await().documents
            .filter { it.getString(FIELD_STATUS) == STATUS_PENDING }
            .map { it.id }
        if (pending.isEmpty()) return emptyList()
        return pending.chunked(QUERY_CHUNK).flatMap { chunk ->
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
                .documents
                .map { FriendRequest(it.id, entryName(it), entryPhoto(it)) }
        }
    }

    /**
     * Sends a trek request by writing both edges as pending. Either endpoint may later
     * flip them to accepted or delete them — that's what makes acceptance possible
     * without any edge being editable by an outsider (see firestore.rules).
     */
    suspend fun addFriendByCode(input: String) {
        val current = auth.currentUser?.uid ?: error("Sign in to add friends")
        val code = normalizeFriendCode(input)
        require(code.length == FRIEND_CODE_LENGTH) {
            "That code doesn't look right — friend codes are $FRIEND_CODE_LENGTH characters."
        }
        require(code != friendCode(current)) { "That's your own code." }
        val friend = (db.collection("friendCodes").document(code).get().await()
            .get("uid") as? String).orEmpty()
        require(friend.isNotEmpty()) { "No trekker has that code yet." }
        require(friend != current) { "That's your own code." }

        val mine = friendsOf(current).document(friend).get().await()
        require(!mine.exists()) {
            if (mine.getString(FIELD_STATUS) == STATUS_PENDING) {
                "Request already sent — waiting on them."
            } else {
                "You're already trekking together."
            }
        }
        val edge = mapOf(
            "since" to FieldValue.serverTimestamp(),
            FIELD_STATUS to STATUS_PENDING,
        )
        val batch = db.batch()
        batch.set(friendsOf(current).document(friend), edge)
        batch.set(friendsOf(friend).document(current), edge)
        batch.commit().await()
    }

    /** Accepts a request: flips both pending edges to accepted in one batch. */
    suspend fun acceptFriend(friendUid: String) =
        respondToFriend(friendUid, STATUS_ACCEPTED)

    /** Declines a request, or removes an existing friend — both drop both edges. */
    suspend fun removeFriend(friendUid: String) =
        respondToFriend(friendUid, null)

    private suspend fun respondToFriend(friendUid: String, newStatus: String?) {
        val current = auth.currentUser?.uid ?: return
        val batch = db.batch()
        if (newStatus == null) {
            batch.delete(friendsOf(current).document(friendUid))
            batch.delete(friendsOf(friendUid).document(current))
        } else {
            // merge, not update: the other side's edge may already be gone if they
            // removed us first — recreating a bare accepted edge keeps things symmetric.
            val edge = mapOf(FIELD_STATUS to newStatus)
            batch.set(friendsOf(current).document(friendUid), edge, SetOptions.merge())
            batch.set(friendsOf(friendUid).document(current), edge, SetOptions.merge())
        }
        batch.commit().await()
    }

    // --- boards ----------------------------------------------------------------

    /**
     * Me plus [friends], client-sorted for every period (friend lists are small enough
     * that chunked `whereIn` beats a composite index). A friend whose doc still carries
     * last period's key shows as zero rather than dropping off.
     */
    suspend fun friendBoard(friends: List<String>, period: Period): List<LeaderboardEntry> {
        val current = auth.currentUser?.uid ?: return emptyList()
        val ids = (listOf(current) + friends).distinct()
        val entries = mutableListOf<LeaderboardEntry>()
        for (chunk in ids.chunked(QUERY_CHUNK)) {
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
                .documents
                .mapTo(entries) { it.toEntry() }
        }
        return entries.sortedByDescending { it.score(period) }
    }

    /**
     * First page of the global board for [period], server-ordered (needs the composite
     * indexes documented in firestore.rules / README).
     */
    suspend fun openGlobalBoard(period: Period): List<LeaderboardEntry> {
        resetGlobalCursor()
        return globalPage(period)
    }

    /** Next page, or empty once exhausted. Only valid after [openGlobalBoard]. */
    suspend fun nextGlobalPage(period: Period): List<LeaderboardEntry> {
        if (globalExhausted) return emptyList()
        return globalPage(period)
    }

    /** True while another page might exist for the currently open global board. */
    fun globalHasMore(): Boolean = !globalExhausted

    private suspend fun globalPage(period: Period): List<LeaderboardEntry> {
        var query: Query = db.collection("users")
        when (period) {
            Period.WEEK -> query = query
                .whereEqualTo("weekKey", weekKey())
                .orderBy("weekPixels", Query.Direction.DESCENDING)
            Period.MONTH -> query = query
                .whereEqualTo("monthKey", monthKey())
                .orderBy("monthPixels", Query.Direction.DESCENDING)
            Period.ALL_TIME -> query = query
                .orderBy("totalPixels", Query.Direction.DESCENDING)
        }
        query = query.limit(PAGE_SIZE.toLong())
        globalCursor?.let { query = query.startAfter(it) }
        val snap = query.get().await()
        globalCursor = snap.documents.lastOrNull()
        globalExhausted = snap.size() < PAGE_SIZE || globalCursor == null
        return snap.documents.map { it.toEntry() }
    }

    private fun resetGlobalCursor() {
        globalCursor = null
        globalExhausted = false
    }

    /** Top three of last week's frozen board. Empty before archives exist. */
    suspend fun lastWeekPodium(): List<PodiumEntry> {
        val last = weekKey(LocalDate.now().minusWeeks(1))
        return db.collection("archive").document(last).collection("scores")
            .orderBy("pixels", Query.Direction.DESCENDING)
            .limit(3)
            .get()
            .await()
            .documents
            .mapNotNull { doc ->
                val pixels = (doc.get("pixels") as? Number)?.toLong() ?: return@mapNotNull null
                PodiumEntry(entryName(doc), entryPhoto(doc), pixels)
            }
    }

    private fun friendsOf(owner: String) =
        db.collection("users").document(owner).collection("friends")

    companion object {
        const val FIELD_STATUS = "status"
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"

        fun entryName(doc: DocumentSnapshot): String =
            (doc.getString("displayName"))?.takeIf { it.isNotBlank() } ?: "Trekker"

        fun entryPhoto(doc: DocumentSnapshot): String = doc.getString("photoUrl").orEmpty()
    }
}

/** Field-by-field and untyped on purpose: a half-written doc must not crash the board. */
private fun DocumentSnapshot.toEntry(): LeaderboardEntry {
    fun px(key: String): Long = (get(key) as? Number)?.toLong() ?: 0L
    return LeaderboardEntry(
        uid = id,
        displayName = SocialRepository.entryName(this),
        photoUrl = SocialRepository.entryPhoto(this),
        weekPixels = if (getString("weekKey") == weekKey()) px("weekPixels") else 0L,
        monthPixels = if (getString("monthKey") == monthKey()) px("monthPixels") else 0L,
        totalPixels = px("totalPixels"),
    )
}
