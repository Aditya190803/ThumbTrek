package com.thumbtrek.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.thumbtrek.app.social.LeaderboardEntry
import com.thumbtrek.app.social.Period
import com.thumbtrek.app.social.PodiumEntry
import com.thumbtrek.app.social.formatFriendCode
import com.thumbtrek.app.social.inviteMessage
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters

@Composable
fun SocialScreen(modifier: Modifier = Modifier, vm: SocialViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var expandedUid by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<LeaderboardEntry?>(null) }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) vm.refresh()
    }

    LaunchedEffect(state.friendCount) {
        codeInput = ""
    }

    removeTarget?.let { target ->
        RemoveFriendDialog(
            name = target.displayName,
            onConfirm = {
                vm.removeFriend(target.uid)
                removeTarget = null
            },
            onDismiss = { removeTarget = null },
        )
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.signedIn) {
            item {
                TrekCard {
                    Text("Join the leaderboard", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Sign in to compare your weekly trek with friends — or the whole " +
                            "world. Signing in publishes nothing on its own; you choose " +
                            "what gets shared on the next screen.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { vm.signIn(context) },
                        enabled = !state.busy,
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Sign in with Google")
                        }
                    }
                }
            }
        } else if (!state.optedIn) {
            item {
                TrekCard {
                    Text("Share your trek?", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Leaderboards are off until you turn them on. Switching them on " +
                            "uploads exactly three things:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "• your name — or an anonymous handle, your call\n" +
                            "• your profile photo, unless you're anonymous\n" +
                            "• how far you've trekked this week, month and all time",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Never your history, your per-app numbers, or anything you scrolled " +
                            "past. The board resets every Monday, and switching this back " +
                            "off deletes your score from it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AnonymousToggle(state, vm)
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { vm.setOptIn(true) },
                        enabled = !state.busy,
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Turn on leaderboards")
                        }
                    }
                    TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                }
            }
        } else {
            item {
                TrekCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(state.myPhotoUrl, state.myName)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.myName, style = MaterialTheme.typography.titleMedium)
                            val rankLine = globalRankLine(state.board, state.myRank)
                            Text(
                                if (rankLine != null && state.board == SocialViewModel.Board.GLOBAL) {
                                    "$rankLine · this week: " +
                                        formatDistance(pixelsToMeters(state.weekPx, dpi))
                                } else {
                                    "This week: ${formatDistance(pixelsToMeters(state.weekPx, dpi))}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    AnonymousToggle(state, vm)
                    TextButton(onClick = { vm.setOptIn(false) }) {
                        Text("Leave the leaderboard")
                    }
                }
            }
        }

        if (state.optedIn) {
            if (state.podium.isNotEmpty()) {
                item { PodiumCard(state.podium, dpi) }
            }

            if (state.requests.isNotEmpty()) {
                item {
                    TrekCard {
                        Text("Trek requests", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        state.requests.forEach { request ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Avatar(request.photoUrl, request.displayName)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(request.displayName, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.respondToRequest(request.uid, true) }) {
                                    Text("Accept")
                                }
                                TextButton(onClick = { vm.respondToRequest(request.uid, false) }) {
                                    Text("Decline", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.signedIn && state.optedIn) {
            item {
                TrekCard {
                    Text("Your friend code", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        formatFriendCode(state.friendCode),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Anyone who adds this code sends you a trek request — accept it " +
                            "and you land on each other's board.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, inviteMessage(state.friendCode))
                            }
                            context.startActivity(
                                Intent.createChooser(send, "Invite a trekker"),
                            )
                        },
                        enabled = state.friendCode.isNotBlank(),
                    ) {
                        Text("Invite a friend")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = codeInput,
                            onValueChange = { codeInput = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("Add by code") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                            ),
                        )
                        Button(
                            onClick = { vm.addFriend(codeInput) },
                            enabled = !state.busy && codeInput.isNotBlank(),
                        ) {
                            Text("Add")
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TabRow(
                        selectedTabIndex = if (state.board == SocialViewModel.Board.FRIENDS) 0 else 1,
                    ) {
                        Tab(
                            selected = state.board == SocialViewModel.Board.FRIENDS,
                            onClick = { vm.showBoard(SocialViewModel.Board.FRIENDS) },
                            text = { Text("Friends") },
                        )
                        Tab(
                            selected = state.board == SocialViewModel.Board.GLOBAL,
                            onClick = { vm.showBoard(SocialViewModel.Board.GLOBAL) },
                            text = { Text("Global") },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Period.entries.forEach { period ->
                            FilterChip(
                                selected = state.period == period,
                                onClick = { vm.showPeriod(period) },
                                label = { Text(period.label) },
                            )
                        }
                    }
                }
            }

            if (state.entries.isEmpty() && !state.busy) {
                item {
                    Text(
                        if (state.board == SocialViewModel.Board.FRIENDS) {
                            "Just you out here. Send that code to someone."
                        } else {
                            "No trekkers on the board yet."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(state.entries) { index, entry ->
                val isMe = entry.uid == state.myUid
                val isFriendsTab = state.board == SocialViewModel.Board.FRIENDS
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.outlinedCardColors(
                        containerColor = if (isMe) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        } else {
                            TrekCardSurface
                        },
                    ),
                    border = CardDefaults.outlinedCardBorder().copy(
                        brush = SolidColor(
                            if (isMe) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                    ),
                    onClick = {
                        expandedUid = if (expandedUid == entry.uid) null else entry.uid
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${index + 1}",
                            modifier = Modifier.width(28.dp),
                            fontWeight = FontWeight.Bold,
                        )
                        Avatar(entry.photoUrl, entry.displayName)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            if (isMe) "${entry.displayName} (you)" else entry.displayName,
                            modifier = Modifier.weight(1f),
                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(formatDistance(pixelsToMeters(entry.score(state.period), dpi)))
                        if (isFriendsTab && !isMe) {
                            IconButton(onClick = { removeTarget = entry }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove ${entry.displayName}",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                    }
                    if (expandedUid == entry.uid) {
                        EntryDetails(entry, dpi)
                    }
                }
            }

            if (state.hasMore) {
                item {
                    OutlinedButton(
                        onClick = { vm.loadMore() },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Load more")
                    }
                }
            }

            if (state.board == SocialViewModel.Board.FRIENDS &&
                state.friendCount > 0 &&
                state.entries.size < state.friendCount + 1
            ) {
                item {
                    Text(
                        "Friends who haven't turned leaderboards on yet stay hidden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        state.error?.let { err ->
            item {
                Text(err, color = MaterialTheme.colorScheme.error)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (state.busy && state.signedIn) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (state.signedIn && state.optedIn) {
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                }
            }
        }
    }
}

private fun globalRankLine(board: SocialViewModel.Board, myRank: Int?): String? =
    if (board == SocialViewModel.Board.GLOBAL) myRank?.let { "#$it" } else null

@Composable
private fun PodiumCard(podium: List<PodiumEntry>, dpi: Int) {
    TrekCard {
        Text("🏆 Last week's podium", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        podium.forEachIndexed { index, p ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${index + 1}", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold)
                Avatar(p.photoUrl, p.displayName)
                Spacer(modifier = Modifier.width(12.dp))
                Text(p.displayName, modifier = Modifier.weight(1f))
                Text(formatDistance(pixelsToMeters(p.pixels, dpi)))
            }
        }
    }
}

@Composable
private fun EntryDetails(entry: LeaderboardEntry, dpi: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DetailRow("This month", pixelsToMeters(entry.monthPixels, dpi))
        DetailRow("All time", pixelsToMeters(entry.totalPixels, dpi))
    }
}

@Composable
private fun DetailRow(label: String, meters: Double) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            formatDistance(meters),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RemoveFriendDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $name?") },
        text = { Text("You'll disappear from each other's boards. You can always add " +
            "each other back with a friend code.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        },
    )
}

@Composable
private fun AnonymousToggle(state: SocialViewModel.UiState, vm: SocialViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Trek anonymously", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (state.anonymous) {
                    "The board sees \"${state.myName}\" and no photo"
                } else {
                    "The board sees your Google name and photo"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = state.anonymous,
            onCheckedChange = { vm.setAnonymous(it) },
            enabled = !state.busy,
        )
    }
}

@Composable
private fun Avatar(photoUrl: String, name: String) {
    if (photoUrl.isBlank()) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull()?.uppercase() ?: "?")
        }
    } else {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}
