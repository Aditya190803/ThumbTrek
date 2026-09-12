package com.thumbtrek.app.ui

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.thumbtrek.app.social.LeaderboardEntry
import com.thumbtrek.app.social.Period
import com.thumbtrek.app.social.PodiumEntry
import com.thumbtrek.app.social.SOURCE_ANDROID
import com.thumbtrek.app.social.SOURCE_WEB
import com.thumbtrek.app.social.formatFriendCode
import com.thumbtrek.app.social.inviteMessage
import com.thumbtrek.app.social.micrometresToMeters
import com.thumbtrek.app.social.sourceLabel
import com.thumbtrek.app.social.syncedAgo
import com.thumbtrek.app.social.weekUmIn
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.stats.weekKey
import kotlinx.coroutines.delay

private val GUTTER = 20.dp

/**
 * [inviteCode] is a friend code that arrived by invite link (`thumbtrek://i/<code>` or
 * https://thumbtrek.adityamer.dev/i/<code>). It is prefilled into the add-a-trekker box rather than
 * sent automatically: adding someone is a social act, and a link tapped by accident should
 * not silently fire a request at a stranger.
 */
@Composable
fun SocialScreen(
    modifier: Modifier = Modifier,
    vm: SocialViewModel = viewModel(),
    inviteCode: String? = null,
    onInviteConsumed: () -> Unit = {},
) {
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
    // Declared after the clearing effect on purpose: both run on first composition, in
    // declaration order, so an invite that arrives with the screen wins the box instead of
    // being wiped by the initial friend-count emission.
    LaunchedEffect(inviteCode) {
        if (inviteCode != null) {
            codeInput = inviteCode
            onInviteConsumed()
        }
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
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 28.dp),
    ) {
        when {
            !state.signedIn -> item("signin") { SignInPitch(state, vm, context) }
            !state.optedIn -> item("consent") { ConsentPanel(state, vm) }
            else -> item("standing") { MyStanding(state, vm, dpi) }
        }

        // Nothing below the fold means anything until the board is switched on.
        if (!state.optedIn) return@LazyColumn

        if (state.requests.isNotEmpty()) {
            item("requests") {
                Column {
                    SectionHead("Trek requests", trailing = "${state.requests.size}")
                    state.requests.forEach { request ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(request.photoUrl, request.displayName)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                request.displayName,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Trek.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { vm.respondToRequest(request.uid, true) }) {
                                Text("Accept", color = Trek.moss)
                            }
                            TextButton(onClick = { vm.respondToRequest(request.uid, false) }) {
                                Text("Decline", color = Trek.inkFaint)
                            }
                        }
                    }
                }
            }
        }

        if (state.podium.isNotEmpty()) {
            item("podium") { PodiumBlock(state.podium) }
        }

        item("invite") { FriendCodeBlock(state, vm, codeInput, { codeInput = it }, context) }

        item("boardControls") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHead("Leaderboard")
                TrekSegmented(
                    options = listOf("Friends", "Global"),
                    selected = if (state.board == SocialViewModel.Board.FRIENDS) 0 else 1,
                    onSelect = {
                        vm.showBoard(
                            if (it == 0) SocialViewModel.Board.FRIENDS
                            else SocialViewModel.Board.GLOBAL,
                        )
                    },
                )
                val periods = Period.entries.toList()
                TrekSegmented(
                    options = periods.map { it.label },
                    selected = periods.indexOf(state.period).coerceAtLeast(0),
                    onSelect = { vm.showPeriod(periods[it]) },
                )
            }
        }

        state.error?.let { message ->
            item("boardError") { ErrorNote(message) { vm.refresh() } }
        }

        if (state.busy && state.entries.isEmpty()) {
            item("loading") {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    repeat(4) { BoardRowSkeleton() }
                }
            }
        } else if (state.entries.isEmpty()) {
            item("emptyBoard") {
                if (state.board == SocialViewModel.Board.FRIENDS) {
                    EmptyState(
                        title = "Nobody here but you",
                        body = "Friend codes are the only way onto this board. Send yours to " +
                            "one person and it stops being a solo sport.",
                    )
                } else {
                    EmptyState(
                        title = "The global board is empty",
                        body = "Nobody has published a score for this period yet. Pull it " +
                            "again in a bit, or check the friends board.",
                    )
                }
            }
        } else {
            val leader = state.entries.maxOfOrNull { it.score(state.period) } ?: 1L
            itemsIndexed(state.entries, key = { _, entry -> entry.uid }) { index, entry ->
                BoardRow(
                    rank = index + 1,
                    entry = entry,
                    period = state.period,
                    leaderScore = leader,
                    isMe = entry.uid == state.myUid,
                    expanded = expandedUid == entry.uid,
                    removable = state.board == SocialViewModel.Board.FRIENDS &&
                        entry.uid != state.myUid,
                    onToggle = {
                        expandedUid = if (expandedUid == entry.uid) null else entry.uid
                    },
                    onRemove = { removeTarget = entry },
                )
            }
        }

        if (state.hasMore) {
            item("more") {
                TrekGhostButton(
                    text = "Load more",
                    onClick = { vm.loadMore() },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.board == SocialViewModel.Board.FRIENDS &&
            state.friendCount > 0 &&
            state.entries.size < state.friendCount + 1
        ) {
            item("hiddenFriends") {
                Text(
                    "Friends who have not turned leaderboards on stay hidden here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.inkFaint,
                )
            }
        }

        item("refresh") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                TrekGhostButton(
                    text = if (state.busy) "Refreshing" else "Refresh",
                    onClick = { vm.refresh() },
                    enabled = !state.busy,
                    contentColor = Trek.inkMuted,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Onboarding into the social layer
// ---------------------------------------------------------------------------------------

@Composable
private fun SignInPitch(
    state: SocialViewModel.UiState,
    vm: SocialViewModel,
    context: android.content.Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("SOCIAL", style = TrekOverline, color = Trek.inkFaint)
        Text(
            "See how your week stacks up.",
            style = MaterialTheme.typography.displaySmall,
            color = Trek.ink,
        )
        Text(
            "Weekly, monthly and all-time boards against friends you invite by code, or " +
                "against everyone. Signing in publishes nothing on its own: you choose what " +
                "gets shared on the next step, and you can leave at any time.",
            style = MaterialTheme.typography.bodyLarge,
            color = Trek.inkMuted,
        )
        TrekButton(
            text = "Continue with Google",
            onClick = { vm.signIn(context) },
            loading = state.busy,
        )
        state.error?.let { ErrorNote(it) { vm.signIn(context) } }
    }
}

/**
 * Just-in-time consent. Exactly what leaves the phone is listed at the moment of the ask,
 * next to the switch that decides whether your name is one of them.
 *
 * The list now has two halves, because syncing does. Three distances and a name are
 * *published* — anyone signed in can read them, that is the board. The day-by-day history
 * is *synced*, to your account only, so the dashboard on another device can chart it and
 * so it survives a reinstall. Rolling both into one "here's what we upload" line would be
 * the easy copy and the dishonest one: they have different audiences.
 */
@Composable
private fun ConsentPanel(state: SocialViewModel.UiState, vm: SocialViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("ONE MORE STEP", style = TrekOverline, color = Trek.inkFaint)
        Text(
            "Publish your trek?",
            style = MaterialTheme.typography.displaySmall,
            color = Trek.ink,
        )
        TrekPanel {
            ConsentLine("An anonymous handle", "or your Google name, your call")
            Hairline(modifier = Modifier.padding(vertical = 12.dp))
            ConsentLine("No profile photo", "unless you switch your name on")
            Hairline(modifier = Modifier.padding(vertical = 12.dp))
            ConsentLine("Three distances", "this week, this month, all time")
            Hairline(modifier = Modifier.padding(vertical = 12.dp))
            ConsentLine(
                "Your day-by-day history",
                "private to your account — never on the board",
            )
        }
        Text(
            "The first three are the board: anyone signed in can see them. Your history and " +
                "per-app split sync privately, so your own dashboard can chart them and they " +
                "survive a reinstall — nobody else can read them. Never a URL, a message, or " +
                "anything you scrolled past. Boards reset every Monday, and switching this " +
                "off deletes all of it from the server.",
            style = MaterialTheme.typography.bodyMedium,
            color = Trek.inkMuted,
        )
        AnonymousToggle(state, vm)
        TrekButton(
            text = "Turn on leaderboards",
            onClick = { vm.setOptIn(true) },
            loading = state.busy,
        )
        TextButton(onClick = { vm.signOut() }) {
            Text("Sign out", color = Trek.inkFaint)
        }
        state.error?.let { ErrorNote(it) { vm.setOptIn(true) } }
    }
}

@Composable
private fun ConsentLine(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Spacer(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .background(Trek.moss, CircleShape),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Trek.ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Trek.inkFaint)
        }
    }
}

// ---------------------------------------------------------------------------------------
// Signed in
// ---------------------------------------------------------------------------------------

@Composable
private fun MyStanding(state: SocialViewModel.UiState, vm: SocialViewModel, dpi: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(state.myPhotoUrl, state.myName, size = 44.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.myName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Trek.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.myRank?.let { "Ranked #$it globally" } ?: "Published",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.inkFaint,
                )
            }
            TextButton(onClick = { vm.signOut() }) {
                Text("Sign out", color = Trek.inkFaint)
            }
        }

        Column {
            Text("YOUR WEEK", style = TrekOverline, color = Trek.inkFaint)
            Spacer(modifier = Modifier.height(2.dp))
            CountedDistance(
                pixelsToMeters(state.weekPx, dpi),
                style = MaterialTheme.typography.displayMedium,
            )
        }

        SyncSplit(state)
        AnonymousToggle(state, vm)
        TextButton(onClick = { vm.setOptIn(false) }) {
            Text("Leave the leaderboard", color = Trek.inkFaint)
        }
    }
}

/**
 * Where the figures on your row came from, and when they last moved.
 *
 * The phone stopped being the only writer the day the browser extension shipped, and a
 * total that silently doubles reads as a bug rather than as a feature. Naming each source
 * also gives someone who has just installed the extension somewhere to watch it show up.
 *
 * A section head and measure rows rather than a card: this is part of your standing, not a
 * separate object, and boxing it is exactly what Surfaces.kt rejected.
 */
@Composable
private fun SyncSplit(state: SocialViewModel.UiState) {
    Column {
        SectionHead(
            "Sources",
            trailing = syncedAgo(state.lastSyncedAt)?.uppercase() ?: "NOT SYNCED YET",
        )
        if (state.sources.isEmpty()) {
            // Before the first sync lands there is nothing true to show, and a zeroed row
            // would claim the phone had contributed nothing.
            Text(
                "Your first sync is still on its way.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        } else {
            // Read through the same gate the rollup uses, so a client that stopped syncing
            // last week shows the nothing it contributed rather than last week's figure
            // under this week's heading.
            val thisWeek = weekKey()
            val weekly = state.sources.associateWith { it.weekUmIn(thisWeek) }
            val leader = weekly.values.max().coerceAtLeast(1L)
            state.sources.forEach { source ->
                val um = weekly.getValue(source)
                MeasureRow(
                    label = sourceLabel(source.source),
                    value = formatDistance(micrometresToMeters(um)),
                    fraction = um.toFloat() / leader,
                    // Moss is this device; everything arriving from elsewhere is slate, the
                    // same distinction the leaderboard already draws between you and others.
                    color = if (source.source == SOURCE_ANDROID) Trek.moss else Trek.slate,
                )
            }
        }
        if (state.sources.none { it.source == SOURCE_WEB }) {
            Text(
                "Add the browser extension and your desktop scrolling lands here too, on " +
                    "the same board row.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }
    }
}

/**
 * Last week's top three as an actual podium. It is the one piece of pure delight on this
 * screen, and it earns its place: a ranked list of three names reads as data, three blocks
 * of different heights reads as a result.
 */
@Composable
private fun PodiumBlock(podium: List<PodiumEntry>) {
    // Second, first, third: the shape everyone already knows.
    val order = listOf(1, 0, 2).filter { it <= podium.lastIndex }
    val heights = mapOf(0 to 74.dp, 1 to 54.dp, 2 to 40.dp)
    val medals = Trek.medals

    Column {
        SectionHead("Last week's podium")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            order.forEach { index ->
                val entry = podium[index]
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "Number ${index + 1}, ${entry.displayName}, " +
                                formatDistance(micrometresToMeters(entry.um))
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Avatar(entry.photoUrl, entry.displayName, size = if (index == 0) 44.dp else 34.dp)
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Trek.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        formatDistance(micrometresToMeters(entry.um)),
                        style = MaterialTheme.typography.labelSmall,
                        color = Trek.inkFaint,
                        maxLines = 1,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(heights[index] ?: 40.dp)
                            .background(
                                medals[index].copy(alpha = 0.16f),
                                MaterialTheme.shapes.small,
                            )
                            .border(1.dp, medals[index].copy(alpha = 0.5f), MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${index + 1}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = medals[index],
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendCodeBlock(
    state: SocialViewModel.UiState,
    vm: SocialViewModel,
    codeInput: String,
    onCodeInput: (String) -> Unit,
    context: android.content.Context,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1800)
            copied = false
        }
    }

    Column {
        SectionHead("Your friend code")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .background(Trek.groundSunken, MaterialTheme.shapes.small)
                .border(1.dp, Trek.hairline, MaterialTheme.shapes.small)
                .clickable(
                    enabled = state.friendCode.isNotBlank(),
                    role = Role.Button,
                ) {
                    clipboard.setText(AnnotatedString(state.friendCode))
                    copied = true
                }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics {
                    contentDescription = "Friend code ${formatFriendCode(state.friendCode)}. " +
                        "Tap to copy."
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatFriendCode(state.friendCode).ifBlank { "Generating" },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                color = Trek.ink,
                maxLines = 1,
            )
            Text(
                if (copied) "COPIED" else "TAP TO COPY",
                style = TrekOverline,
                color = if (copied) Trek.moss else Trek.inkFaint,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Anyone who enters this sends you a request. Accept it and you land on each " +
                "other's boards.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkFaint,
        )
        Spacer(modifier = Modifier.height(14.dp))
        TrekGhostButton(
            text = "Share invite",
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, inviteMessage(state.friendCode))
                }
                context.startActivity(Intent.createChooser(send, "Invite a trekker"))
            },
            enabled = state.friendCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = onCodeInput,
                modifier = Modifier.weight(1f),
                label = { Text("Add by code") },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Trek.moss,
                    unfocusedBorderColor = Trek.hairline,
                    focusedContainerColor = Trek.groundSunken,
                    unfocusedContainerColor = Trek.groundSunken,
                ),
            )
            TrekButton(
                text = "Add",
                onClick = { vm.addFriend(codeInput) },
                enabled = codeInput.isNotBlank(),
                loading = state.busy && codeInput.isNotBlank(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------
// Board rows
// ---------------------------------------------------------------------------------------

@Composable
private fun BoardRow(
    rank: Int,
    entry: LeaderboardEntry,
    period: Period,
    leaderScore: Long,
    isMe: Boolean,
    expanded: Boolean,
    removable: Boolean,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
) {
    val score = entry.score(period)
    val meters = micrometresToMeters(score)
    val fraction = if (leaderScore > 0) score.toFloat() / leaderScore else 0f
    val name = if (isMe) "${entry.displayName} (you)" else entry.displayName

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isMe) Trek.mossWash else Trek.groundRaised,
                MaterialTheme.shapes.medium,
            )
            .border(
                1.dp,
                if (isMe) Trek.moss.copy(alpha = 0.4f) else Trek.hairline,
                MaterialTheme.shapes.medium,
            )
            .clickable(role = Role.Button, onClick = onToggle)
            .animateContentSize(tween(trekDuration(TrekDur.SMALL), easing = TrekEase))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Rank $rank, $name, ${formatDistance(meters)}"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RankMark(rank, highlight = isMe)
            Spacer(modifier = Modifier.width(6.dp))
            Avatar(entry.photoUrl, entry.displayName, size = 32.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = Trek.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(formatDistance(meters), style = TrekFigure, color = Trek.ink)
            if (removable) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onRemove)
                        .semantics { contentDescription = "Remove ${entry.displayName}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = null,
                        tint = Trek.inkFaint,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Rail(
            fraction = fraction,
            color = if (isMe) Trek.moss else Trek.slate,
            height = 4.dp,
        )
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow("This month", micrometresToMeters(entry.monthUm))
                DetailRow("All time", micrometresToMeters(entry.totalUm))
            }
        }
    }
}

@Composable
private fun BoardRowSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Trek.groundRaised, MaterialTheme.shapes.medium)
            .border(1.dp, Trek.hairlineSoft, MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Skeleton(modifier = Modifier.size(32.dp), height = 32.dp, shape = CircleShape)
            Spacer(modifier = Modifier.width(12.dp))
            Skeleton(modifier = Modifier.weight(1f), height = 14.dp)
            Spacer(modifier = Modifier.width(40.dp))
        }
        Skeleton(height = 4.dp)
    }
}

@Composable
private fun DetailRow(label: String, meters: Double) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkFaint,
        )
        Text(
            formatDistance(meters),
            style = MaterialTheme.typography.bodySmall,
            color = Trek.inkMuted,
        )
    }
}

// ---------------------------------------------------------------------------------------
// Shared bits
// ---------------------------------------------------------------------------------------

@Composable
private fun ErrorNote(message: String, onRetry: () -> Unit) {
    TrekPanel(fill = Trek.dangerWash, border = Trek.danger.copy(alpha = 0.4f)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Trek.ink)
        Spacer(modifier = Modifier.height(12.dp))
        TrekGhostButton("Try again", onRetry, contentColor = Trek.danger)
    }
}

@Composable
private fun RemoveFriendDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.groundRaised,
        titleContentColor = Trek.ink,
        textContentColor = Trek.inkMuted,
        shape = MaterialTheme.shapes.large,
        title = { Text("Remove $name?") },
        text = {
            Text(
                "You will drop off each other's boards. Friend codes still work, so you can " +
                    "add each other back later.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Remove", color = Trek.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep", color = Trek.inkMuted) }
        },
    )
}

/**
 * Opt-in to be named: everyone treks under a stable pseudonym unless they flip this on.
 * The switch reads as the reveal (checked = Google name and photo on the boards), so the
 * default-off position matches the default-anonymous account.
 */
@Composable
private fun AnonymousToggle(state: SocialViewModel.UiState, vm: SocialViewModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Show my name", style = MaterialTheme.typography.bodyLarge, color = Trek.ink)
            Text(
                if (state.anonymous) {
                    "Boards show \"${state.myName}\" and no photo"
                } else {
                    "Boards show your Google name and photo"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = !state.anonymous,
            onCheckedChange = { vm.setAnonymous(!it) },
            enabled = !state.busy,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Trek.onMoss,
                checkedTrackColor = Trek.moss,
                uncheckedThumbColor = Trek.inkFaint,
                uncheckedTrackColor = Trek.groundSunken,
                uncheckedBorderColor = Trek.hairline,
            ),
        )
    }
}

@Composable
private fun Avatar(photoUrl: String, name: String, size: Dp = 36.dp) {
    if (photoUrl.isBlank()) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(Trek.mossWash)
                .border(1.dp, Trek.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = Trek.moss,
            )
        }
    } else {
        AsyncImage(
            model = photoUrl,
            contentDescription = null,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    }
}
