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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
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
import com.thumbtrek.app.stats.BASELINE_DAYS
import com.thumbtrek.app.share.BACKUP_MIME
import com.thumbtrek.app.share.BackupStore
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.track.ScrollTrackerService
import com.thumbtrek.app.update.UpdateSettingsContent
import kotlinx.coroutines.launch


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
    val baseline by vm.baseline.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Read once per visit to the screen. The package manager is not observable, but that
    // is fine: an in-place update restarts the process, so a stale read cannot survive it.
    val installedVersion = remember { installedVersionName(context) }
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }
    var exportNote by remember { mutableStateOf<String?>(null) }
    // What the last backup or restore did, in a sentence; the Boolean is "it failed".
    var backupNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { target ->
        if (target != null) {
            scope.launch {
                backupNote = runCatching { BackupStore.write(context, target) }.fold(
                    onSuccess = { "Backup saved. Keep the file somewhere safe." to false },
                    onFailure = { (it.message ?: "Could not write the backup.") to true },
                )
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { source ->
        if (source != null) {
            scope.launch {
                backupNote = runCatching { BackupStore.restore(context, source) }.fold(
                    onSuccess = {
                        val days = if (it.days == 1) "1 day" else "${it.days} days"
                        "Restored $days of history, from ${it.from} to ${it.to}." to false
                    },
                    onFailure = { (it.message ?: "Could not read that backup.") to true },
                )
            }
        }
    }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    var showPremium by rememberSaveable { mutableStateOf(false) }

    // PRD 5.5 reminders are a notification, and on Android 13+ that needs a runtime grant.
    // The grant callback enables whichever toggle asked for it. Hardcoding one toggle here
    // is how a limit-nudge tap would silently switch on the streak reminder instead.
    var pendingNotifyToggle by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            when (pendingNotifyToggle) {
                "limit" -> vm.setLimitNudge(true)
                "report" -> vm.setWeeklyReport(true)
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
        PageHeading("Settings", "Tracking, your limit, notifications and your data.")
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
                color = Trek.textDim,
            )
            if (!state.trackingEnabled) {
                Spacer(modifier = Modifier.height(14.dp))
                TrekButton(
                    "Turn on tracking",
                    onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            } else {
                // Google Pay and many UPI/bank apps block payments while a non-Play app has
                // an accessibility service on. See ScrollTrackerService.pause.
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    "Google Pay or a bank app refusing to open? They block any accessibility " +
                        "service not installed from the Play Store. Pause tracking, pay, then " +
                        "turn it back on here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.textDim,
                )
                Spacer(modifier = Modifier.height(10.dp))
                TrekGhostButton(
                    "Pause for payments",
                    onClick = {
                        if (!ScrollTrackerService.pause()) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        vm.refreshTrackingState()
                    },
                )
            }
        }

        // ---- Tracked apps -------------------------------------------------------------
        TrekPanel {
            SectionHead(
                "Tracked apps",
                trailing = "${state.trackedApps.size} enabled",
                trailingColor = if (state.trackedApps.isEmpty()) Trek.danger else Trek.textDim,
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
                            Text("Remove", color = Trek.textFaint)
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
            val learning = baseline.since != null && state.limitM == null
            SectionHead(
                "Daily limit",
                trailing = when {
                    state.limitM != null -> "${state.limitM!!.toInt()} m / day"
                    learning -> "Learning, day ${baseline.days} of $BASELINE_DAYS"
                    else -> "Not set"
                },
            )
            Text(
                when {
                    state.limitM != null -> "Stay within your limit to earn a clean day. Your limit is always your choice."
                    learning -> "ThumbTrek is learning your usual day. After $BASELINE_DAYS tracked days your limit is set about 10% under it. You can also set one yourself now."
                    else -> "No limit and no clean-day scores. Learn your baseline and ThumbTrek will suggest one, or set your own below."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
            baseline.typicalM?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Your usual day lately: about ${formatDistance(it)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.textFaint,
                )
            }
            if (state.limitM == null && !learning) {
                Spacer(modifier = Modifier.height(12.dp))
                TrekButton("Learn my baseline", onClick = { vm.startBaseline() }, modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(12.dp))
            LimitEditor(vm = vm, currentM = state.limitM, typicalM = baseline.typicalM, learning = learning)
            if (state.limitM != null || learning) {
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
                        "Premium will unlock unlimited changes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.textFaint,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LaneLine(color = Trek.lineSoft)
            val canShare = vm.canShareLimit
            ToggleRow(
                title = "One limit across your phone and your browser",
                subtitle = when {
                    !canShare -> "Needs sync. Sign in and turn it on in the Friends tab first."
                    state.sharedLimit -> "On. Browser scrolling counts against this limit, as of its last sync."
                    else -> "Keep one limit in your account, and count browser scrolling against it."
                },
                checked = state.sharedLimit && canShare,
                onCheckedChange = { if (canShare) vm.setSharedLimit(it) },
            )
            LaneLine(color = Trek.lineSoft)
            Spacer(modifier = Modifier.height(12.dp))
            AppLimitsEditor(
                apps = (TRACKED_APPS.keys + state.customApps.keys)
                    .filter { it in state.trackedApps }
                    .map { it to appName(it, state.customApps) },
                limits = state.appLimits,
                onSet = vm::setAppLimit,
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
            if (state.limitM != null || state.appLimits.isNotEmpty()) ToggleRow(
                title = "Limit nudge",
                subtitle = "One heads-up near 80% of a limit, one if you pass it",
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
            ToggleRow(
                title = "Weekly report",
                subtitle = "One summary of last week, at the start of each week",
                checked = state.weeklyReport,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        pendingNotifyToggle = "report"
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setWeeklyReport(enabled)
                    }
                },
            )
            Text(
                "Off by default. ThumbTrek is not here to nag you about your screen time.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.textFaint,
            )
        }

        // ---- Premium ------------------------------------------------------------------
        Column {
            SectionHead("Premium", trailing = "Not on sale yet")
            Text(
                "Tracking is free and stays free. The Premium features are already here " +
                    "and open to everyone until Premium goes on sale.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TrekGhostButton(
                text = "See what Premium includes",
                onClick = { showPremium = true },
                modifier = Modifier.fillMaxWidth(),
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
                                        "Export your data",
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
                color = Trek.textFaint,
            )
            exportNote?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Trek.danger)
            }
            Spacer(modifier = Modifier.height(16.dp))
            LaneLine(color = Trek.lineSoft)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Backup and restore", style = MaterialTheme.typography.titleSmall, color = Trek.text)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "One file with your whole history, your limits and your added apps. " +
                    "Restoring merges it in: nothing already on this phone is lost.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.textDim,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TrekGhostButton(
                text = "Save a backup file",
                onClick = { saveBackup.launch(BackupStore.suggestedName()) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            TrekGhostButton(
                text = "Restore from a backup file",
                // Not every file manager labels JSON as JSON, so the picker is left open
                // and the file is checked when it is read.
                onClick = { openBackup.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            )
            backupNote?.let { (message, failed) ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) Trek.danger else Trek.textDim,
                )
            }
        }

        // ---- Privacy ------------------------------------------------------------------
        Column {
            SectionHead("Privacy")
            TrekPanel {
                Text(
                    "ThumbTrek measures distance, never content.",
                    style = MaterialTheme.typography.titleMedium,
                    color = Trek.text,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Your posts, messages and typing stay private. ThumbTrek only counts scroll events in the apps you choose.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.textDim,
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Distances live in a database on this phone. Nothing is uploaded until you " +
                        "opt into a leaderboard on the Friends tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Trek.textDim,
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
                Text("Live counters for troubleshooting. Open a tracked app, scroll, then return here. These counters stay on this phone.", style = MaterialTheme.typography.bodyMedium, color = Trek.textDim)
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

    if (showPremium) PremiumSheet(onDismiss = { showPremium = false })

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
                .background(if (live) Trek.accent else Trek.textFaint, CircleShape),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            if (live) liveText else idleText,
            style = MaterialTheme.typography.titleLarge,
            color = if (live) Trek.text else Trek.textDim,
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
                color = Trek.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.textFaint,
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
                uncheckedThumbColor = Trek.textFaint,
                uncheckedTrackColor = Trek.sunken,
                uncheckedBorderColor = Trek.line,
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
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Trek.text)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Trek.textFaint,
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
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
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
                            Icon(Icons.Default.Search, contentDescription = null, tint = Trek.textFaint)
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Trek.accent,
                            unfocusedBorderColor = Trek.line,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (shown.isEmpty()) {
                        Text(
                            if (query.isBlank()) "Every installed app is already on your list."
                            else "No apps match \"${query.trim()}\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Trek.textDim,
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
                                    color = Trek.text,
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
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.textDim) }
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
