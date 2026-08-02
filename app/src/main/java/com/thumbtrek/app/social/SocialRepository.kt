package com.thumbtrek.app.social

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.thumbtrek.app.R
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

/**
 * Google Sign-In (Credential Manager) + weekly score sync to Firestore.
 *
 * users/{uid} = { displayName, photoUrl, weekKey, weekPixels }
 * The "weekly leaderboard reset" needs no server job: when [weekKey] rolls over,
 * old docs simply stop matching the query.
 */
class SocialRepository(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val credentialManager = CredentialManager.create(context)

    private val _uid = MutableStateFlow(auth.currentUser?.uid)
    val uid = _uid.asStateFlow()

    val displayName: String get() = auth.currentUser?.displayName ?: "Trekker"
    val photoUrl: String get() = auth.currentUser?.photoUrl?.toString() ?: ""

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
        _uid.value = null
    }

    suspend fun syncScore(weekPixels: Long) {
        val uid = auth.currentUser?.uid ?: return
        db.collection("users").document(uid).set(
            mapOf(
                "displayName" to displayName,
                "photoUrl" to photoUrl,
                "weekKey" to weekKey(),
                "weekPixels" to weekPixels,
            ),
            SetOptions.merge(),
        ).await()
    }

    /** This week's board, sorted client-side (no composite index needed at this scale). */
    suspend fun leaderboard(): List<LeaderboardEntry> =
        db.collection("users")
            .whereEqualTo("weekKey", weekKey())
            .limit(100)
            .get()
            .await()
            .documents
            .map {
                LeaderboardEntry(
                    uid = it.id,
                    displayName = it.getString("displayName") ?: "Trekker",
                    photoUrl = it.getString("photoUrl") ?: "",
                    weekPixels = it.getLong("weekPixels") ?: 0L,
                )
            }
            .sortedByDescending { it.weekPixels }
}
