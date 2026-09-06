package com.thumbtrek.app.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.social.FriendRequest
import com.thumbtrek.app.social.LeaderboardEntry
import com.thumbtrek.app.social.Period
import com.thumbtrek.app.social.PodiumEntry
import com.thumbtrek.app.social.SocialRepository
import com.thumbtrek.app.social.SourceTotals
import com.thumbtrek.app.social.SyncStatus
import com.thumbtrek.app.stats.monthKey
import com.thumbtrek.app.stats.totalThisMonth
import com.thumbtrek.app.stats.totalThisWeek
import com.thumbtrek.app.stats.weekKey
import com.thumbtrek.app.work.SocialNotifier
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
        val monthPx: Long = 0,
        val totalPx: Long = 0,
        val board: Board = Board.GLOBAL,
        val period: Period = Period.WEEK,
        val entries: List<LeaderboardEntry> = emptyList(),
        val hasMore: Boolean = false,
        /** My position on the global board; null while I'm not on it or not found yet. */
        val myRank: Int? = null,
        /** Total opted-in trekkers on the global board, when known. */
        val totalCount: Int? = null,
        val requests: List<FriendRequest> = emptyList(),
        val podium: List<PodiumEntry> = emptyList(),
        /** Who is writing into my board row — this phone, a browser, both. */
        val sources: List<SourceTotals> = emptyList(),
        /** Epoch millis of this phone's last successful push; null before the first. */
        val lastSyncedAt: Long? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private data class BoardState(
        val scope: Board = Board.GLOBAL,
        val period: Period = Period.WEEK,
        val entries: List<LeaderboardEntry> = emptyList(),
        val hasMore: Boolean = false,
        val myRank: Int? = null,
        val friendCount: Int = 0,
        val requests: List<FriendRequest> = emptyList(),
        val podium: List<PodiumEntry> = emptyList(),
        val sync: SyncStatus = SyncStatus(),
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
            monthPx = totalThisMonth(byDate),
            totalPx = byDate.values.sum(),
            board = boardState.scope,
            period = boardState.period,
            entries = boardState.entries,
            hasMore = boardState.hasMore,
            myRank = boardState.myRank,
            requests = boardState.requests,
            podium = boardState.podium,
            sources = boardState.sync.sources,
            lastSyncedAt = boardState.sync.lastSyncedAt,
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
        board.value = BoardState()
        error.value = null
    }

    /** PRD §11 Q2: opting out withdraws the published score, it doesn't just hide it. */
    fun setOptIn(enabled: Boolean) {
        prefs.setLeaderboardOptIn(enabled)
        if (enabled) {
            refresh()
        } else {
            board.value = BoardState(board.value.scope, board.value.period)
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
        board.value = board.value.copy(
            scope = scope, entries = emptyList(), hasMore = false, myRank = null,
        )
        if (!published()) return
        guarded("Couldn't load leaderboard") { loadBoard() }
    }

    fun showPeriod(period: Period) {
        if (board.value.period == period) return
        board.value = board.value.copy(
            period = period, entries = emptyList(), hasMore = false, myRank = null,
        )
        if (!published()) return
        guarded("Couldn't load leaderboard") { loadBoard() }
    }

    fun loadMore() {
        if (!published() || !board.value.hasMore) return
        guarded("Couldn't load more") {
            val page = repo.nextGlobalPage(board.value.period)
            board.value = board.value.copy(
                entries = board.value.entries + page,
                hasMore = board.value.hasMore && page.isNotEmpty(),
                myRank = board.value.myRank ?: findMyRank(page),
            )
        }
    }

    fun addFriend(code: String) {
        if (!published()) return
        guarded("Couldn't send that request") {
            repo.addFriendByCode(code)
            board.value = board.value.copy(scope = Board.FRIENDS)
            loadBoard()
        }
    }

    fun respondToRequest(uid: String, accept: Boolean) {
        if (!published()) return
        guarded("Couldn't update that request") {
            if (accept) repo.acceptFriend(uid) else repo.removeFriend(uid)
            loadBoard()
        }
    }

    fun removeFriend(uid: String) = respondToRequest(uid, accept = false)

    /**
     * Publishes this phone's totals, pushes the day ledger, then reloads the visible board.
     *
     * The board row goes first and is shown as soon as it lands: the ledger push can be a
     * full-history backfill on the first sync after opting in, and a slow backfill must not
     * hold up the thing the user is actually looking at.
     */
    fun refresh() {
        if (!published()) return
        guarded("Couldn't load leaderboard") {
            val current = state.value
            val sync = repo.syncScore(current.weekPx, current.monthPx, current.totalPx)
            board.value = board.value.copy(sync = sync)
            repo.syncDays(dao.allRows())
            board.value = board.value.copy(
                sync = sync.copy(lastSyncedAt = prefs.lastSyncedAt.takeIf { it > 0L }),
            )
            loadBoard()
        }
    }

    private suspend fun findMyRank(page: List<LeaderboardEntry>, offset: Int = 0): Int? {
        val uid = repo.uid.value ?: return null
        val index = page.indexOfFirst { it.uid == uid }
        return if (index >= 0) offset + index + 1 else null
    }

    private suspend fun loadBoard() {
        val scope = board.value.scope
        val period = board.value.period
        val friends = repo.friendUids()

        var entries: List<LeaderboardEntry>
        var hasMore = false
        var rank: Int? = null
        if (scope == Board.FRIENDS) {
            entries = repo.friendBoard(friends, period)
        } else {
            entries = repo.openGlobalBoard(period)
            rank = findMyRank(entries)
            // Walk a few pages so "you're #N" works even when you're outside page one.
            var walks = 0
            while (rank == null && repo.globalHasMore() && walks < MAX_RANK_WALKS) {
                val page = repo.nextGlobalPage(period)
                if (page.isEmpty()) break
                rank = findMyRank(page, offset = entries.size)
                entries += page
                walks++
            }
            hasMore = repo.globalHasMore()
        }

        val requests = repo.incomingRequests()
        val podium = repo.lastWeekPodium()
        board.value = BoardState(
            scope = scope,
            period = period,
            entries = entries,
            hasMore = hasMore,
            myRank = rank,
            friendCount = friends.size,
            requests = requests,
            podium = podium,
            // Loading a board doesn't re-read the source split; carry the last sync's.
            sync = board.value.sync,
        )
        maybeNotify(requests, rank)
    }

    private companion object {
        const val MAX_RANK_WALKS = 8
    }

    /**
     * Local-only nudges: a new trek request, or slipping down the global board.
     * Fires at most once per event per week; silently skipped without notification
     * permission.
     */
    private fun maybeNotify(requests: List<FriendRequest>, rank: Int?) {
        val app = getApplication<Application>()
        if (!notificationsAllowed(app)) return
        val known = prefs.knownRequestUids
        val fresh = requests.filter { it.uid !in known }
        fresh.firstOrNull()?.let {
            SocialNotifier.notifyRequest(app, it.displayName)
        }
        if (requests.isNotEmpty()) prefs.knownRequestUids = requests.map { it.uid }.toSet()

        val thisWeek = weekKey()
        if (rank != null) {
            if (prefs.notifiedRankWeek == thisWeek &&
                prefs.notifiedRank != Int.MAX_VALUE &&
                rank > prefs.notifiedRank
            ) {
                SocialNotifier.notifyRankSlip(app, rank)
            }
            prefs.notifiedRankWeek = thisWeek
            prefs.notifiedRank = minOf(rank, prefs.notifiedRank.takeIf { it != Int.MAX_VALUE } ?: rank)
        }
    }

    private fun notificationsAllowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33

    private fun published() = repo.uid.value != null && prefs.leaderboardOptIn.value

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
