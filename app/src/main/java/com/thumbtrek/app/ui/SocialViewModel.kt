package com.thumbtrek.app.ui

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.social.LeaderboardEntry
import com.thumbtrek.app.social.SocialRepository
import com.thumbtrek.app.stats.totalThisWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class SocialViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val signedIn: Boolean = false,
        val myUid: String? = null,
        val myName: String = "",
        val myPhotoUrl: String = "",
        val weekPx: Long = 0,
        val entries: List<LeaderboardEntry> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val repo = SocialRepository(app)
    private val dao = ScrollDatabase.get(app).dao()

    private val entries = MutableStateFlow<List<LeaderboardEntry>>(emptyList())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state = combine(
        repo.uid,
        dao.observeAllDays(),
        entries,
        busy,
        error,
    ) { uid, days, board, isBusy, err ->
        val byDate = days.associate { LocalDate.parse(it.date) to it.pixels }
        UiState(
            signedIn = uid != null,
            myUid = uid,
            myName = if (uid != null) repo.displayName else "",
            myPhotoUrl = if (uid != null) repo.photoUrl else "",
            weekPx = totalThisWeek(byDate),
            entries = board,
            busy = isBusy,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    fun signIn(activityContext: Context) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repo.signIn(activityContext)
                refresh()
            } catch (e: GetCredentialCancellationException) {
                // User dismissed the sheet — not an error.
            } catch (e: Exception) {
                error.value = e.message ?: "Sign-in failed"
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        repo.signOut()
        entries.value = emptyList()
    }

    /** Pushes my week score, then reloads the board. */
    fun refresh() {
        if (repo.uid.value == null) return
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                repo.syncScore(state.value.weekPx)
                entries.value = repo.leaderboard()
            } catch (e: Exception) {
                error.value = e.message ?: "Couldn't load leaderboard"
            } finally {
                busy.value = false
            }
        }
    }
}
