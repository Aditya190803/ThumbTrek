package com.thumbtrek.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.thumbtrek.app.data.ScrollDatabase
import com.thumbtrek.app.social.AccountSync
import com.thumbtrek.app.social.Challenge
import com.thumbtrek.app.social.Group
import com.thumbtrek.app.social.GroupStanding
import com.thumbtrek.app.social.GroupWindow
import com.thumbtrek.app.social.groupStandings
import com.thumbtrek.app.social.groupWindow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Groups and challenges for the Friends tab. A view model of its own so the leaderboard's
 * state, which is already a five-way combine, does not have to carry it.
 */
class GroupsViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val loaded: Boolean = false,
        val groups: List<Group> = emptyList(),
        /** The group whose board is open, with its window and ranking. */
        val open: Group? = null,
        val window: GroupWindow? = null,
        val standings: List<GroupStanding> = emptyList(),
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val account = AccountSync(app)
    private val dao = ScrollDatabase.get(app).dao()
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val myUid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    fun load() = guarded("Couldn't load your groups") {
        val groups = account.myGroups()
        _state.update { it.copy(loaded = true, groups = groups) }
        // Keep this phone's number in every group current while we are here.
        account.publishProgress(groups, dao.allRows())
    }

    fun create(name: String) = guarded("Couldn't create the group") {
        val group = account.createGroup(name)
        account.publishProgress(listOf(group), dao.allRows())
        _state.update { it.copy(groups = (it.groups + group).sortedBy { g -> g.name.lowercase() }) }
        show(group)
    }

    fun join(code: String) = guarded("Couldn't join that group") {
        val group = account.joinGroup(code)
        account.publishProgress(listOf(group), dao.allRows())
        _state.update { s ->
            s.copy(groups = (s.groups.filterNot { it.id == group.id } + group).sortedBy { it.name.lowercase() })
        }
        show(group)
    }

    fun open(group: Group) = guarded("Couldn't load that group") { show(group) }

    fun close() = _state.update { it.copy(open = null, window = null, standings = emptyList()) }

    fun leave(group: Group) = guarded("Couldn't leave the group") {
        account.leaveGroup(group)
        dropLocally(group)
    }

    fun delete(group: Group) = guarded("Couldn't delete the group") {
        account.deleteGroup(group)
        dropLocally(group)
    }

    fun setChallenge(group: Group, challenge: Challenge?) = guarded("Couldn't save the challenge") {
        account.setChallenge(group, challenge)
        val updated = group.copy(challenge = challenge)
        _state.update { s -> s.copy(groups = s.groups.map { if (it.id == group.id) updated else it }) }
        // A new window means everyone's number starts again; publish mine straight away.
        account.publishProgress(listOf(updated), dao.allRows())
        show(updated)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun show(group: Group) {
        val window = groupWindow(group)
        val standings = groupStandings(group, account.progress(group), window, myUid)
        _state.update { it.copy(open = group, window = window, standings = standings) }
    }

    private fun dropLocally(group: Group) = _state.update { s ->
        s.copy(groups = s.groups.filterNot { it.id == group.id }, open = null, window = null, standings = emptyList())
    }

    private fun guarded(fallback: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: fallback) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
