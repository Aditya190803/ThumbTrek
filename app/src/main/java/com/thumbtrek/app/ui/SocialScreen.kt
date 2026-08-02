package com.thumbtrek.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters

@Composable
fun SocialScreen(modifier: Modifier = Modifier, vm: SocialViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }

    LaunchedEffect(state.signedIn) {
        if (state.signedIn) vm.refresh()
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
                            "Sign in to sync your weekly trek and see how your scrolling stacks up. " +
                                "Resets every Monday.",
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
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
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
                }
            }

            if (state.entries.isEmpty() && !state.busy) {
                item {
                    Text(
                        "No trekkers on the board yet this week.",
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
                } else if (state.signedIn) {
                    TextButton(onClick = { vm.refresh() }) { Text("Refresh") }
                }
            }
        }
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
