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
import com.google.firebase.firestore.SetOptions
import com.thumbtrek.app.R
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.weekKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class LeaderboardEntry(
    val uid: String,
    val displayName: String,
    val photoUrl: String,
    val weekPixels: Long,
)

/** Firestore caps `whereIn` at 30 values, so friend lists are queried in chunks. */
private const val QUERY_CHUNK = 30

/**
 * Google Sign-In (Credential Manager) + weekly score sync to Firestore.
 *
 * users/{uid} = { displayName, photoUrl, friendCode, weekKey, weekPixels }
 * users/{uid}/friends/{friendUid} = { since } — written in both directions, so
 * friendship is symmetric (PRD §5.4).
 * friendCodes/{code} = { uid } — the invite lookup index.
 *
 * The "weekly leaderboard reset" needs no server job: when [weekKey] rolls over,
 * old docs simply stop matching the query.
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
        _uid.value = null
    }

    /** No-op until the user opts in — no score may leave the device before that. */
    suspend fun syncScore(weekPixels: Long) {
        val current = auth.currentUser?.uid ?: return
        if (!prefs.leaderboardOptIn.value) return
        db.collection("users").document(current).set(
            mapOf(
                "displayName" to publishedName,
                "photoUrl" to publishedPhotoUrl,
                "friendCode" to friendCode(current),
                "weekKey" to weekKey(),
                "weekPixels" to weekPixels,
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

    /** This week's board, sorted client-side (no composite index needed at this scale). */
    suspend fun leaderboard(): List<LeaderboardEntry> {
        val week = weekKey()
        return db.collection("users")
            .whereEqualTo("weekKey", week)
            .limit(100)
            .get()
            .await()
            .documents
            .map { it.toEntry(week) }
            .sortedByDescending { it.weekPixels }
    }

    suspend fun friendUids(): List<String> {
        val current = auth.currentUser?.uid ?: return emptyList()
        return friendsOf(current).get().await().documents.map { it.id }
    }

    /**
     * Me plus [friends], same weekly reset semantics as the global board: a friend whose
     * doc still carries last week's [weekKey] shows as zero rather than dropping off.
     * Lists longer than [QUERY_CHUNK] are chunked, never truncated.
     */
    suspend fun friendLeaderboard(friends: List<String>): List<LeaderboardEntry> {
        val current = auth.currentUser?.uid ?: return emptyList()
        val week = weekKey()
        val entries = mutableListOf<LeaderboardEntry>()
        for (chunk in (listOf(current) + friends).distinct().chunked(QUERY_CHUNK)) {
            db.collection("users")
                .whereIn(FieldPath.documentId(), chunk)
                .get()
                .await()
                .documents
                .mapTo(entries) { it.toEntry(week) }
        }
        return entries.sortedByDescending { it.weekPixels }
    }

    /**
     * Adds both edges in one batch so friendship is symmetric. The rules allow a user to
     * edit their own friends list, or to add themselves to someone else's — nothing else.
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
        val edge = mapOf("since" to FieldValue.serverTimestamp())
        val batch = db.batch()
        batch.set(friendsOf(current).document(friend), edge)
        batch.set(friendsOf(friend).document(current), edge)
        batch.commit().await()
    }

    private fun friendsOf(owner: String) =
        db.collection("users").document(owner).collection("friends")
}

/** Field-by-field and untyped on purpose: a half-written doc must not crash the board. */
private fun DocumentSnapshot.toEntry(week: String): LeaderboardEntry {
    val pixels = (get("weekPixels") as? Number)?.toLong() ?: 0L
    return LeaderboardEntry(
        uid = id,
        displayName = (get("displayName") as? String)?.takeIf { it.isNotBlank() } ?: "Trekker",
        photoUrl = (get("photoUrl") as? String).orEmpty(),
        weekPixels = if ((get("weekKey") as? String) == week) pixels else 0L,
    )
}
