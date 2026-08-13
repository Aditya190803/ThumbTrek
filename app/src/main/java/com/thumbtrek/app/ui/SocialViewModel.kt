package com.thumbtrek.app.ui

import android.app.Application
import android.content.Context
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.Prefs
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

    enum class Board { FRIENDS, GLOBAL }

    data class UiState(
        val signedIn: Boolean = false,
        val optedIn: Boolean = false,
        val anonymous: Boolean = false,
        val myUid: String? = null,
        val myName: String = "",
        val myPhotoUrl: String = "",
        val friendCode: String = "",
        val friendCount: Int = 0,
        val weekPx: Long = 0,
        val board: Board = Board.GLOBAL,
        val entries: List<LeaderboardEntry> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private data class BoardState(
        val scope: Board = Board.GLOBAL,
        val entries: List<LeaderboardEntry> = emptyList(),
        val friendCount: Int = 0,
    )

    private val repo = SocialRepository(app)
    private val prefs = Prefs.get(app)
    private val dao = ScrollDatabase.get(app).dao()

    private val board = MutableStateFlow(BoardState())
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    private val status = combine(busy, error) { isBusy, err -> isBusy to err }
    private val settings =
        combine(prefs.leaderboardOptIn, prefs.anonymous) { optIn, anon -> optIn to anon }

    val state = combine(
        repo.uid,
        dao.observeAllDays(),
        board,
        status,
        settings,
    ) { uid, days, boardState, (isBusy, err), (optIn, anon) ->
        val byDate = days.associate { LocalDate.parse(it.date) to it.pixels }
        UiState(
            signedIn = uid != null,
            optedIn = optIn,
            anonymous = anon,
            myUid = uid,
            myName = if (uid != null) repo.publishedName else "",
            myPhotoUrl = if (uid != null) repo.publishedPhotoUrl else "",
            friendCode = repo.myFriendCode.orEmpty(),
            friendCount = boardState.friendCount,
            weekPx = totalThisWeek(byDate),
            board = boardState.scope,
            entries = boardState.entries,
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
        board.value = BoardState(board.value.scope)
        error.value = null
    }

    /** PRD §11 Q2: opting out withdraws the published score, it doesn't just hide it. */
    fun setOptIn(enabled: Boolean) {
        prefs.setLeaderboardOptIn(enabled)
        if (enabled) {
            refresh()
        } else {
            board.value = BoardState(board.value.scope)
            guarded("Couldn't remove your score") { repo.unpublish() }
        }
    }

    /** Re-syncs so the board swaps between the Google name and the anonymous handle. */
    fun setAnonymous(enabled: Boolean) {
        prefs.setAnonymous(enabled)
        refresh()
    }

    fun showBoard(scope: Board) {
        if (board.value.scope == scope) return
        board.value = board.value.copy(scope = scope, entries = emptyList())
        if (!published()) return
        guarded("Couldn't load leaderboard") { loadBoard() }
    }

    fun addFriend(code: String) {
        if (!published()) return
        guarded("Couldn't add that friend") {
            repo.addFriendByCode(code)
            board.value = board.value.copy(scope = Board.FRIENDS)
            loadBoard()
        }
    }

    /** Pushes my week score, then reloads the visible board. */
    fun refresh() {
        if (!published()) return
        guarded("Couldn't load leaderboard") {
            repo.syncScore(state.value.weekPx)
            loadBoard()
        }
    }

    private fun published() = repo.uid.value != null && prefs.leaderboardOptIn.value

    private suspend fun loadBoard() {
        val scope = board.value.scope
        val friends = repo.friendUids()
        val entries = when (scope) {
            Board.FRIENDS -> repo.friendLeaderboard(friends)
            Board.GLOBAL -> repo.leaderboard()
        }
        board.value = BoardState(scope, entries, friends.size)
    }

    private fun guarded(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            try {
                block()
            } catch (e: Exception) {
                error.value = e.message ?: fallback
            } finally {
                busy.value = false
            }
        }
    }
}
