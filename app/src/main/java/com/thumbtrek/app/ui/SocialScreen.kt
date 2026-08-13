package com.thumbtrek.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
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

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) vm.refresh()
    }

    LaunchedEffect(state.friendCount) {
        codeInput = ""
    }

    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.signedIn) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Join the leaderboard", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sign in to compare your weekly trek with friends — or the whole " +
                                "world. Signing in publishes nothing on its own; you choose " +
                                "what gets shared on the next screen.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
            }
        } else if (!state.optedIn) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Share your trek?", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Leaderboards are off until you turn them on. Switching them on " +
                                "uploads exactly three things:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "• your name — or an anonymous handle, your call\n" +
                                "• your profile photo, unless you're anonymous\n" +
                                "• how far you've trekked this week, one number",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Never your history, your per-app numbers, or anything you scrolled " +
                                "past. The board resets every Monday, and switching this back " +
                                "off deletes your score from it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AnonymousToggle(state, vm)
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
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(state.myPhotoUrl, state.myName)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(state.myName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "This week: ${formatDistance(pixelsToMeters(state.weekPx, dpi))}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.signOut() }) { Text("Sign out") }
                        }
                        AnonymousToggle(state, vm)
                        TextButton(onClick = { vm.setOptIn(false) }) {
                            Text("Leave the leaderboard")
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Your friend code", style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatFriendCode(state.friendCode),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Anyone who adds this code lands on your board, and you on theirs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            }

            item {
                TabRow(selectedTabIndex = if (state.board == SocialViewModel.Board.FRIENDS) 0 else 1) {
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
            }

            if (state.entries.isEmpty() && !state.busy) {
                item {
                    Text(
                        if (state.board == SocialViewModel.Board.FRIENDS) {
                            "Just you out here. Send that code to someone."
                        } else {
                            "No trekkers on the board yet this week."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(state.entries) { index, entry ->
                val isMe = entry.uid == state.myUid
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isMe) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
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
                        Text(formatDistance(pixelsToMeters(entry.weekPixels, dpi)))
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
