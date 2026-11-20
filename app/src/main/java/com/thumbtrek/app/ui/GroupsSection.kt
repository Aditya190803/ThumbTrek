@file:OptIn(ExperimentalMaterial3Api::class)

package com.thumbtrek.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thumbtrek.app.social.Challenge
import com.thumbtrek.app.social.ChallengeKind
import com.thumbtrek.app.social.Group
import com.thumbtrek.app.social.GroupStanding
import com.thumbtrek.app.social.GroupWindow
import com.thumbtrek.app.social.MAX_CHALLENGE_TITLE
import com.thumbtrek.app.social.MAX_GROUP_MEMBERS
import com.thumbtrek.app.social.MAX_GROUP_NAME
import com.thumbtrek.app.social.challengeProblem
import com.thumbtrek.app.social.formatFriendCode
import com.thumbtrek.app.social.groupWindow
import com.thumbtrek.app.social.micrometresToMeters
import com.thumbtrek.app.stats.formatDistance
import java.time.LocalDate

private val CHALLENGE_LENGTHS = listOf(7, 14, 30)

/**
 * Private groups and challenges, on the Friends tab. A group is a list like any other
 * here: rows divided by lane lines, opened into a sheet that carries its board.
 */
@Composable
internal fun GroupsSection(vm: GroupsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    Column {
        SectionHead(
            "Groups",
            trailing = when {
                !state.loaded -> null
                state.groups.isEmpty() -> "None yet"
                state.groups.size == 1 -> "1 group"
                else -> "${state.groups.size} groups"
            },
        )
        if (state.loaded && state.groups.isEmpty()) {
            Text(
                "A group is a private board for people you hand its code to. Run a challenge " +
                    "in it, such as who scrolls the least this week.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
            Spacer(Modifier.height(12.dp))
        }
        state.groups.forEach { group ->
            val window = remember(group) { groupWindow(group) }
            LaneLine()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open ${group.name}") { vm.open(group) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Trek.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (group.challenge == null) memberCount(group) else "${window.title}, ${window.status.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Trek.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text("Open", style = TrekCaption, color = Trek.textFaint)
            }
        }
        if (state.groups.isNotEmpty()) {
            LaneLine()
            Spacer(Modifier.height(12.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TrekGhostButton("New group", { creating = true }, Modifier.weight(1f))
            TrekGhostButton("Join with a code", { joining = true }, Modifier.weight(1f))
        }
        state.error?.takeIf { state.open == null }?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Trek.danger)
        }
    }

    if (creating) {
        TextEntryDialog(
            title = "New group",
            label = "Group name",
            confirm = "Create",
            maxLength = MAX_GROUP_NAME,
            onConfirm = {
                vm.create(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
    if (joining) {
        TextEntryDialog(
            title = "Join a group",
            label = "Group code",
            confirm = "Join",
            maxLength = 64,
            capitals = true,
            onConfirm = {
                vm.join(it)
                joining = false
            },
            onDismiss = { joining = false },
        )
    }
    val open = state.open
    val window = state.window
    if (open != null && window != null) {
        GroupSheet(open, window, state.standings, state.busy, state.error, vm)
    }
}

private fun memberCount(group: Group): String =
    if (group.members.size == 1) "1 member" else "${group.members.size} of $MAX_GROUP_MEMBERS members"

@Composable
private fun GroupSheet(
    group: Group,
    window: GroupWindow,
    standings: List<GroupStanding>,
    busy: Boolean,
    error: String?,
    vm: GroupsViewModel,
) {
    val context = LocalContext.current
    val isOwner = standings.any { it.isMe && it.uid == group.owner }
    var planning by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = vm::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Trek.bg,
        contentColor = Trek.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
        ) {
            Text(
                group.name,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = Trek.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (group.challenge == null) "This week, most distance first. ${memberCount(group)}."
                else "${window.title}. ${window.kind.label}. ${window.status}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
            Spacer(Modifier.height(16.dp))

            standings.forEachIndexed { index, row ->
                LaneLine()
                LaneRow(
                    number = index + 1,
                    name = if (row.isMe) "${row.name} (you)" else row.name,
                    value = row.um?.let { formatDistance(micrometresToMeters(it)) } ?: "No data yet",
                    numberColor = if (row.isMe) Trek.accent else Trek.text,
                    valueColor = if (row.um == null) Trek.textFaint else Trek.text,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Place ${index + 1}, ${row.name}, " +
                            (row.um?.let { formatDistance(micrometresToMeters(it)) } ?: "no data yet")
                    },
                )
            }
            LaneLine()
            Spacer(Modifier.height(8.dp))
            Text(
                "Each member's number is as fresh as their last sync.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.textFaint,
            )

            Spacer(Modifier.height(20.dp))
            Text("Group code", style = TrekCaption, color = Trek.textDim)
            Text(formatFriendCode(group.code), style = MaterialTheme.typography.headlineSmall, color = Trek.text)
            Spacer(Modifier.height(10.dp))
            TrekGhostButton(
                text = "Share the code",
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "Join my ThumbTrek group \"${group.name}\". In the Friends tab, choose " +
                                "Join with a code and enter ${formatFriendCode(group.code)}.",
                        )
                    }
                    context.startActivity(Intent.createChooser(send, "Share the group code"))
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))
            if (isOwner) {
                if (group.challenge == null) {
                    TrekButton("Start a challenge", { planning = true }, Modifier.fillMaxWidth(), loading = busy)
                } else {
                    TrekGhostButton("End the challenge", { vm.setChallenge(group, null) }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
            }
            TextButton(onClick = { confirming = true }) {
                Text(if (isOwner) "Delete this group" else "Leave this group", color = Trek.danger)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Trek.danger)
            }
        }
    }

    if (planning) {
        ChallengeDialog(
            onStart = {
                vm.setChallenge(group, it)
                planning = false
            },
            onDismiss = { planning = false },
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = Trek.surface,
            titleContentColor = Trek.text,
            textContentColor = Trek.textDim,
            shape = MaterialTheme.shapes.large,
            title = { Text(if (isOwner) "Delete ${group.name}?" else "Leave ${group.name}?") },
            text = {
                Text(
                    if (isOwner) "The group and its board go away for every member. This cannot be undone."
                    else "Your number is removed from its board. You can join again with the code.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    if (isOwner) vm.delete(group) else vm.leave(group)
                }) { Text(if (isOwner) "Delete" else "Leave", color = Trek.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel", color = Trek.textDim) }
            },
        )
    }
}

@Composable
private fun ChallengeDialog(onStart: (Challenge) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }
    var length by remember { mutableIntStateOf(0) }
    val today = remember { LocalDate.now() }
    val ends = today.plusDays(CHALLENGE_LENGTHS[length] - 1L)
    val problem = challengeProblem(title, today, ends)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
        shape = MaterialTheme.shapes.large,
        title = { Text("Start a challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(MAX_CHALLENGE_TITLE) },
                    label = { Text("Name") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    colors = dialogFieldColors(),
                )
                TrekSegmented(ChallengeKind.entries.map { it.label }, kind, { kind = it })
                TrekSegmented(CHALLENGE_LENGTHS.map { "$it days" }, length, { length = it })
                Text("Starts today. Every member's count starts from zero.")
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onStart(Challenge(title.trim(), ChallengeKind.entries[kind], today, ends)) },
                enabled = problem == null,
            ) { Text("Start", color = Trek.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.textDim) }
        },
    )
}

@Composable
private fun TextEntryDialog(
    title: String,
    label: String,
    confirm: String,
    maxLength: Int,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    capitals: Boolean = false,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
        shape = MaterialTheme.shapes.large,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(maxLength) },
                label = { Text(label) },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    capitalization = if (capitals) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences,
                    autoCorrectEnabled = !capitals,
                ),
                colors = dialogFieldColors(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input.trim()) }, enabled = input.isNotBlank()) {
                Text(confirm, color = Trek.text)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.textDim) }
        },
    )
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Trek.text,
    unfocusedTextColor = Trek.text,
    focusedLabelColor = Trek.textFaint,
    unfocusedLabelColor = Trek.textFaint,
    focusedBorderColor = Trek.accent,
    unfocusedBorderColor = Trek.line,
)
