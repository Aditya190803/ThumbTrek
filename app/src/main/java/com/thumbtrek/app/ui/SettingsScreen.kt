package com.thumbtrek.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thumbtrek.app.data.TRACKED_APPS
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.share.DataExporter
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.MAX_DAILY_LIMIT_M
import com.thumbtrek.app.stats.MIN_DAILY_LIMIT_M
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.track.ScrollDiagnostics
import com.thumbtrek.app.update.UpdateSettingsContent
import kotlinx.coroutines.launch

private val GUTTER = 24.dp

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = viewModel(),
    scrollState: ScrollState = rememberScrollState(),
) {
    LifecycleResumeEffect(Unit) {
        vm.refreshTrackingState()
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Read once per visit to the screen. The package manager is not observable, but that
    // is fine: an in-place update restarts the process, so a stale read cannot survive it.
    val installedVersion = remember { installedVersionName(context) }
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    // PRD 5.5 reminders are a notification, and on Android 13+ that needs a runtime grant.
    // The grant callback enables whichever toggle asked for it · hardcoding one toggle here
    // is how a limit-nudge tap would silently switch on the streak reminder instead.
    var pendingNotifyToggle by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (pendingNotifyToggle) {
                "limit" -> vm.setLimitNudge(true)
                else -> vm.setStreakReminder(true)
            }
        }
        pendingNotifyToggle = null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = GUTTER)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PageHeading("Settings", "Make room for what matters.")
        ActionRow("Review setup", "Apps, permissions, daily limit and account", {
            Prefs.get(context).setOnboardingStep(0)
        })

        // ---- Tracking -----------------------------------------------------------------
        TrekPanel {
            SectionHead("Tracking")
            StatusLine(
                live = state.trackingEnabled,
                liveText = "Counting scrolls",
                idleText = "Paused",
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                if (state.trackingEnabled) {
                    "Tracking is on. Only scroll distance is measured, never what’s on your screen."
                } else {
                    "Enable ThumbTrek in Accessibility settings to start measuring scroll distance."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.inkMuted,
            )
            if (!state.trackingEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                TrekButton(
                    "Turn on tracking",
                    onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }

        // ---- Tracked apps -------------------------------------------------------------
        TrekPanel {
            SectionHead(
                "Tracked apps",
                trailing = "${state.trackedApps.size} enabled",
                trailingColor = if (state.trackedApps.isEmpty()) Trek.danger else Trek.inkMuted,
            )
            TRACKED_APPS.forEach { (pkg, name) ->
                ToggleRow(
                    title = name,
                    checked = pkg in state.trackedApps,
                    onCheckedChange = { vm.setAppTracked(pkg, it) },
                    leading = { AppToken(appName(pkg, state.customApps), chartColor(pkg)) },
                )
            }
            state.customApps.forEach { (pkg, label) ->
                ToggleRow(
                    title = label,
                    subtitle = pkg,
                    checked = pkg in state.trackedApps,
                    onCheckedChange = { vm.setAppTracked(pkg, it) },
                    leading = { AppToken(appName(pkg, state.customApps), chartColor(pkg)) },
                    trailing = {
                        TextButton(onClick = { vm.removeCustomApp(pkg) }) {
                            Text("Remove", color = Trek.inkFaint)
                        }
                    },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            TrekGhostButton(
                text = "Add another app",
                onClick = {
                    vm.loadInstalledApps()
                    showPicker = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.trackedApps.isEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Every app is off, so nothing will be measured. Switch at least one back " +
                        "on to keep your trek going.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.danger,
                )
            }
        }

        // ---- Daily limit --------------------------------------------------------------
        TrekPanel {
            SectionHead("Daily limit", trailing = state.limitM?.let { "${it.toInt()} m / day" } ?: "Not set")
            Text(
                if (state.limitM == null) "Track your usual scrolling first, or choose a daily cap below." else "Stay within your limit to earn a clean day. Your limit is always your choice.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.inkMuted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LimitEditor(vm = vm, currentM = state.limitM)
            if (state.limitM != null) {
                TextButton(onClick = { vm.clearLimit() }) { Text("Track without a limit") }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                when {
                    !BILLING_ENFORCED -> "Change your limit as often as you like. It’s free for now."
                    state.premium -> "Premium: unlimited changes."
                    state.freeEditsLeft > 0 -> "One free change per week. Yours is still " +
                        "available this week."
                    else -> "This week's free change is used. The quota resets Monday; " +
                        "Premium (coming soon) unlocks unlimited changes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }

        // ---- Notifications ------------------------------------------------------------
        TrekPanel {
            SectionHead("Notifications")
            ToggleRow(
                title = "Streak reminder",
                subtitle = "One quiet nudge if a streak is about to lapse",
                checked = state.streakReminder,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingNotifyToggle = "streak"
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setStreakReminder(enabled)
                    }
                },
            )
            if (state.limitM != null) ToggleRow(
                title = "Limit nudge",
                subtitle = "One heads-up near 80% of your limit, one if you pass it",
                checked = state.limitNudge,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingNotifyToggle = "limit"
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setLimitNudge(enabled)
                    }
                },
            )
            Text(
                "Off by default. ThumbTrek is not here to nag you about your screen time.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }

        // ---- Data ---------------------------------------------------------------------
        Column {
            SectionHead("Your data")
            TrekGhostButton(
                text = "Export everything as CSV",
                onClick = {
                    scope.launch {
                        runCatching { DataExporter.export(context) }
                            .onSuccess { uri ->
                                exportNote = null
                                context.startActivity(
                                    Intent.createChooser(
                                        DataExporter.shareIntent(uri),
                                        "Export trek",
                                    ),
                                )
                            }
                            .onFailure {
                                exportNote = "Could not write the export file. Free up a " +
                                    "little storage and try again."
                            }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Download your complete history, organized by app and date.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
            exportNote?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Trek.danger)
            }
        }

        // ---- Privacy ------------------------------------------------------------------
        Column {
            SectionHead("Privacy")
            TrekPanel {
                Text(
                    "ThumbTrek measures distance, never content.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Trek.ink,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Your posts, messages and typing stay private. ThumbTrek only counts scroll events in the apps you choose.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.inkMuted,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Distances live in a database on this phone. Nothing is uploaded until you " +
                        "opt into a leaderboard on the Social tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.inkMuted,
                )
            }
        }

        TrekGhostButton(
            text = if (showDiagnostics) "Hide tracking details" else "Tracking details",
            onClick = { showDiagnostics = !showDiagnostics },
            modifier = Modifier.fillMaxWidth(),
        )
        if (showDiagnostics) {
            Column {
                SectionHead("Tracking details")
                Text("Live counters for troubleshooting. Open a tracked app, scroll, then return here. These counters stay on this phone.", style = MaterialTheme.typography.bodyMedium, color = Trek.inkMuted)
                Spacer(Modifier.height(12.dp))
                DiagnosticsTable(trackedApps = state.trackedApps, customLabels = state.customApps)
            }
        }

        // ---- About --------------------------------------------------------------------
        Column {
            SectionHead("About ThumbTrek")

            // The self-updater rides here, above the Version row (the banner-ish slot the
            // old TODO reserved). Its stock container · UpdateCard's OutlinedCard · would
            // drop a foreign card shape into this screen's panel system, so we take
            // UpdateSettingsContent, contents only, and dress it in a TrekPanel the way
            // Privacy does. UpdateUi.kt is deliberately plain Material3 so it keeps
            // compiling when the design system moves; the dressing stays local to this
            // file. The content wires up its own ViewModel via the default viewModel(),
            // which shares state with the background worker through UpdateRepository.
            TrekPanel { UpdateSettingsContent() }

            Spacer(modifier = Modifier.height(14.dp))

            ActionRow(
                title = "Version",
                // Same PackageManager lookup UpdateRepository performs for the updater's
                // own "Installed:" line, so the two can never disagree.
                subtitle = "ThumbTrek $installedVersion",
                onClick = null,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showPicker) {
        AppPickerDialog(vm = vm, onDismiss = { showPicker = false })
    }
}

// ---------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------

/** A live/idle indicator with a dot, so tracking state is readable without parsing prose. */
@Composable
private fun StatusLine(live: Boolean, liveText: String, idleText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(if (live) Trek.accent else Trek.inkFaint, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            if (live) liveText else idleText,
            style = MaterialTheme.typography.titleLarge,
            color = if (live) Trek.ink else Trek.inkMuted,
        )
    }
}

/**
 * One setting. The whole row toggles, not just the switch, which is both a bigger target
 * and how every other Android settings screen behaves.
 */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = Trek.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.inkFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Trek.onAccent,
                checkedTrackColor = Trek.accent,
                checkedBorderColor = Trek.accent,
                uncheckedThumbColor = Trek.inkFaint,
                uncheckedTrackColor = Trek.groundSunken,
                uncheckedBorderColor = Trek.hairline,
            ),
        )
    }
}

/**
 * A tappable settings row. [onClick] may be null for read-only entries, which then take no
 * press feedback and stay out of the focus order.
 */
@Composable
private fun ActionRow(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Trek.ink)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.inkFaint,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// App picker
// ---------------------------------------------------------------------------------------

@Composable
internal fun AppPickerDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    val settings by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    // Apps already on the list would only be added twice, so they are left out here.
    val known = TRACKED_APPS.keys + settings.customApps.keys
    val shown = remember(apps, known, query) {
        apps.filter { it.packageName !in known }
            .filter { query.isBlank() || it.label.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.groundRaised,
        titleContentColor = Trek.ink,
        textContentColor = Trek.inkMuted,
        shape = MaterialTheme.shapes.large,
        title = { Text("Track another app") },
        text = {
            if (apps.isEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(4) { Skeleton(modifier = Modifier.fillMaxWidth(), height = 20.dp) }
                }
            } else {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search apps") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = Trek.inkFaint)
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Trek.accent,
                            unfocusedBorderColor = Trek.hairline,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isBlank()) "Every installed app is already on your list."
                            else "No apps match \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Trek.inkMuted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    ) {
                        shown.forEach { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable(role = Role.Button) {
                                        vm.addCustomApp(app.packageName, app.label)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    app.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = Trek.ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.inkMuted) }
        },
    )
}

/**
 * The installed version name, read the same way the updater's UpdateRepository reads it so
 * the Version row and the updater's own "Installed:" line can never disagree. A bare "?"
 * keeps the row intact if the package manager somehow cannot see our own package.
 */
private fun installedVersionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------------------------------
// Daily limit editor
// ---------------------------------------------------------------------------------------

/**
 * The limit editor. Saves through the weekly free-edit quota in [Prefs]: the first change
 * each ISO week is free, further ones need premium, and the result line says which one
 * just happened so "Save" never looks dead.
 */
@Composable
private fun LimitEditor(vm: SettingsViewModel, currentM: Float?) {
    var input by remember(currentM) { mutableStateOf(currentM?.toInt()?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val result by vm.limitEdit.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    val save = {
        val value = input.toFloatOrNull()
        error = when {
            value == null -> "Enter your daily limit in metres."
            value < MIN_DAILY_LIMIT_M.toFloat() || value > MAX_DAILY_LIMIT_M.toFloat() ->
                "Between ${MIN_DAILY_LIMIT_M.toInt()} and " +
                    "${MAX_DAILY_LIMIT_M.toInt()} m, so a typo can't break the game."
            else -> null
        }
        if (error == null && value != null) {
            vm.trySetLimit(value)
            focus.clearFocus()
        }
    }

    LimitPresets(
        selected = input.toIntOrNull(),
        onPick = {
            input = it.toString()
            error = null
            vm.clearLimitEdit()
        },
    )
    Spacer(modifier = Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                // Digits only; the range check happens on save, where it can explain itself.
                input = it.filter { char -> char.isDigit() }.take(5)
                error = null
                vm.clearLimitEdit()
            },
            modifier = Modifier.weight(1f),
            label = { Text("Metres per day") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { save() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Trek.ink,
                unfocusedTextColor = Trek.ink,
                errorTextColor = Trek.ink,
                focusedLabelColor = Trek.inkFaint,
                unfocusedLabelColor = Trek.inkFaint,
                errorLabelColor = Trek.danger,
                focusedBorderColor = Trek.accent,
                unfocusedBorderColor = Trek.hairline,
                errorBorderColor = Trek.danger,
            ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        TrekButton(
            "Save",
            onClick = save,
            // Saving the value that is already set would only spend a weekly edit.
            enabled = input.toFloatOrNull() != currentM,
        )
    }
    // What the typed number means, before it is saved: the same landmark line onboarding
    // shows, so "250" reads as a distance rather than an arbitrary figure.
    input.toDoubleOrNull()
        ?.takeIf { it in MIN_DAILY_LIMIT_M..MAX_DAILY_LIMIT_M && error == null && result == null }
        ?.let {
            Text(
                comparison(it),
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkMuted,
            )
        }
    when (val applied = result) {
        is Prefs.LimitEditResult.Applied -> {
            Spacer(modifier = Modifier.height(8.dp))
            val noun = if (applied.freeLeft == 1) "change" else "changes"
            Text(
                when {
                    !BILLING_ENFORCED -> "Saved. Limits are free for now, so change it whenever you like."
                    applied.freeLeft == Int.MAX_VALUE -> "Saved. Premium: unlimited changes."
                    else -> "Saved. ${applied.freeLeft} free $noun left this week."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.accent,
            )
        }
        Prefs.LimitEditResult.NeedsPremium -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This week's free change is used. The quota resets Monday. Premium " +
                    "(coming soon) unlocks unlimited changes. Nothing was changed.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.danger,
            )
        }
        null -> Unit
    }
}

/** Common starting points, so choosing a limit doesn't begin with a blank number field. */
private val LIMIT_PRESETS = listOf(50, 100, 250, 500)

/**
 * One-tap limit presets, shared by onboarding and Settings. A preset is only a suggestion:
 * it fills the field, and the caller still decides when to save.
 */
@Composable
internal fun LimitPresets(selected: Int?, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LIMIT_PRESETS.forEach { meters ->
            val active = meters == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(if (active) Trek.accentWash else Color.Transparent)
                    .border(1.dp, if (active) Trek.accent else Trek.hairline, CircleShape)
                    .selectable(selected = active, role = Role.RadioButton) { onPick(meters) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$meters m",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Trek.accent else Trek.ink,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------
// Tracking diagnostics
// ---------------------------------------------------------------------------------------

/**
 * Per-app proof that measurement works, read straight from [ScrollDiagnostics]. The
 * service and the UI share a process, so this is live counters, not a log scrape: scroll
 * YouTube, come back, and its row moves. Apps with no row yet show as idle rather than
 * vanishing, so "YouTube measures nothing" and "YouTube never heard a scroll" read
 * differently · which is the entire point of the diagnostics.
 */
@Composable
private fun DiagnosticsTable(
    trackedApps: Set<String>,
    customLabels: Map<String, String>,
) {
    val context = LocalContext.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val revision by ScrollDiagnostics.revision.collectAsStateWithLifecycle()
    val snapshot = remember(revision) { ScrollDiagnostics.snapshot() }
    val byPkg = remember(snapshot) { snapshot.associateBy { it.packageName } }
    var clearedNote by remember { mutableStateOf(false) }

    val packages = remember(trackedApps, customLabels, snapshot) {
        (TRACKED_APPS.keys + customLabels.keys + snapshot.map { it.packageName })
            .distinct()
            .sortedWith(
                compareBy<String> { it !in trackedApps }.thenBy { appName(it, customLabels) },
            )
    }

    TrekPanel(padding = PaddingValues(4.dp)) {
        if (packages.isEmpty()) {
            Text(
                "Nothing tracked yet. Switch an app on above and its row appears here.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        packages.forEachIndexed { index, pkg ->
            val diag = byPkg[pkg]
            val countedPx = diag?.countedPixels ?: 0L
            val detail = when {
                pkg !in trackedApps -> "Not tracked"
                diag == null -> "Idle. No scroll heard yet"
                else -> "Seen in foreground ${diag.foregroundSightings}× · " +
                    "${formatDistance(pixelsToMeters(countedPx, dpi))} counted" +
                    (diag.dominantPath?.let { " · via $it" } ?: "")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SeriesDot(chartColor(pkg))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        appName(pkg, customLabels),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (pkg in trackedApps) Trek.ink else Trek.inkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Trek.inkFaint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (index < packages.lastIndex) Hairline(modifier = Modifier.padding(horizontal = 14.dp))
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrekGhostButton(
            text = "Reset counters",
            onClick = {
                ScrollDiagnostics.clear()
                clearedNote = true
            },
        )
        if (clearedNote) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Counters cleared. Scroll a feed to start them again.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }
    }
}
