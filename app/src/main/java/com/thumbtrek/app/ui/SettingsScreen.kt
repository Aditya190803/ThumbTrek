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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.track.ScrollDiagnostics
import com.thumbtrek.app.update.UpdateSettingsContent
import kotlinx.coroutines.launch

private val GUTTER = 20.dp

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
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

    // PRD 5.5 reminders are a notification, and on Android 13+ that needs a runtime grant.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.setStreakReminder(true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GUTTER),
        verticalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Spacer(modifier = Modifier.height(2.dp))

        // ---- Tracking -----------------------------------------------------------------
        Column {
            SectionHead("Tracking")
            StatusLine(
                live = state.trackingEnabled,
                liveText = "Counting scrolls",
                idleText = "Paused",
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                if (state.trackingEnabled) {
                    "The Accessibility Service is running. It sees scroll events from the apps " +
                        "below and nothing else."
                } else {
                    "Nothing is being measured. ThumbTrek needs its Accessibility Service " +
                        "switched on to count how far you scroll."
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
        Column {
            SectionHead(
                "Tracked apps",
                trailing = "${state.trackedApps.size} ON",
                trailingColor = if (state.trackedApps.isEmpty()) Trek.danger else Trek.inkMuted,
            )
            TRACKED_APPS.forEach { (pkg, name) ->
                ToggleRow(
                    title = name,
                    checked = pkg in state.trackedApps,
                    onCheckedChange = { vm.setAppTracked(pkg, it) },
                    leading = { SeriesDot(chartColor(pkg)) },
                )
            }
            state.customApps.forEach { (pkg, label) ->
                ToggleRow(
                    title = label,
                    subtitle = pkg,
                    checked = pkg in state.trackedApps,
                    onCheckedChange = { vm.setAppTracked(pkg, it) },
                    leading = { SeriesDot(chartColor(pkg)) },
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
        Column {
            SectionHead("Daily limit", trailing = "${state.limitM.toInt()} M / DAY")
            Text(
                "Stay at or under this far a day and the day counts as clean. Clean days in " +
                    "a row are the streak that matters here — this one rewards scrolling less, " +
                    "not more.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.inkMuted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LimitEditor(vm = vm, currentM = state.limitM)
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                when {
                    !BILLING_ENFORCED -> "Free while ThumbTrek finds its feet — change your " +
                        "limit as often as you like for now."
                    state.premium -> "Premium: unlimited changes."
                    state.freeEditsLeft > 0 -> "One free change per week — yours is still " +
                        "available this week."
                    else -> "This week's free change is used. The quota resets Monday; " +
                        "Premium (coming soon) unlocks unlimited changes."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }

        // ---- Tracking diagnostics -----------------------------------------------------
        Column {
            SectionHead("Tracking diagnostics", trailing = "DEBUG")
            Text(
                "Proof that measurement works. Open YouTube, scroll the home feed and a " +
                    "few Shorts, then come back here: its row should have moved. Foreground " +
                    "sightings without pixels mean events arrive but bank nothing; nothing at " +
                    "all means the toggle above or the system Accessibility switch. Counts " +
                    "reset with the process and never leave this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.inkMuted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            DiagnosticsTable(
                trackedApps = state.trackedApps,
                customLabels = state.customApps,
            )
        }

        // ---- Notifications ------------------------------------------------------------
        Column {
            SectionHead("Notifications")
            ToggleRow(
                title = "Streak reminder",
                subtitle = "One quiet nudge if a streak is about to lapse",
                checked = state.streakReminder,
                onCheckedChange = { enabled ->
                    if (enabled && needsNotificationPermission(context)) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setStreakReminder(enabled)
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
                "One row per app per day: dates and pixel counts, nothing else.",
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
                    "The service listens for one thing, scroll events from the apps you picked " +
                        "above, and requests no permission to read what is on screen. It cannot " +
                        "see your posts, your messages, or what you type.",
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

        // ---- About --------------------------------------------------------------------
        Column {
            SectionHead("About")

            // The self-updater rides here, above the Version row (the banner-ish slot the
            // old TODO reserved). Its stock container — UpdateCard's OutlinedCard — would
            // drop a foreign card shape into this screen's panel system, so we take
            // UpdateSettingsContent, contents only, and dress it in a TrekPanel the way
            // Privacy does. UpdateUi.kt is deliberately plain Material3 so it keeps
            // compiling when the design system moves; the dressing stays local to this
            // file. The content wires up its own ViewModel via the default viewModel(),
            // which shares state with the background worker through UpdateRepository.
            SectionHead("Updates")
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
                .background(if (live) Trek.moss else Trek.inkFaint, CircleShape),
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
private fun ToggleRow(
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
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Trek.onMoss,
                checkedTrackColor = Trek.moss,
                checkedBorderColor = Trek.moss,
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
private fun AppPickerDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInstalledApps() }

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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    apps.forEach { app ->
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
private fun LimitEditor(vm: SettingsViewModel, currentM: Float) {
    var input by remember(currentM) { mutableStateOf(currentM.toInt().toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    val result by vm.limitEdit.collectAsStateWithLifecycle()

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
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Trek.ink,
                unfocusedTextColor = Trek.ink,
                errorTextColor = Trek.ink,
                focusedLabelColor = Trek.inkFaint,
                unfocusedLabelColor = Trek.inkFaint,
                errorLabelColor = Trek.danger,
                focusedBorderColor = Trek.moss,
                unfocusedBorderColor = Trek.hairline,
                errorBorderColor = Trek.danger,
            ),
        )
        Spacer(modifier = Modifier.width(10.dp))
        TrekButton(
            "Save",
            onClick = {
                val value = input.toFloatOrNull()
                error = when {
                    value == null -> "Enter a number, e.g. 100."
                    value < MIN_DAILY_LIMIT_M.toFloat() || value > MAX_DAILY_LIMIT_M.toFloat() ->
                        "Between ${MIN_DAILY_LIMIT_M.toInt()} and " +
                            "${MAX_DAILY_LIMIT_M.toInt()} m, so a typo can't break the game."
                    else -> null
                }
                if (error == null && value != null) vm.trySetLimit(value)
            },
        )
    }
    when (val applied = result) {
        is Prefs.LimitEditResult.Applied -> {
            Spacer(modifier = Modifier.height(8.dp))
            val noun = if (applied.freeLeft == 1) "change" else "changes"
            Text(
                when {
                    !BILLING_ENFORCED -> "Saved. Limits are free for now — change it whenever."
                    applied.freeLeft == Int.MAX_VALUE -> "Saved. Premium: unlimited changes."
                    else -> "Saved. ${applied.freeLeft} free $noun left this week."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Trek.moss,
            )
        }
        Prefs.LimitEditResult.NeedsPremium -> {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "This week's free change is used — the quota resets Monday. Premium " +
                    "(coming soon) unlocks unlimited changes. Nothing was changed.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.danger,
            )
        }
        null -> Unit
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
 * differently — which is the entire point of the diagnostics.
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
                "Nothing tracked yet — switch an app on above and its row appears here.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        packages.forEachIndexed { index, pkg ->
            val diag = byPkg[pkg]
            val countedPx = diag?.countedPixels ?: 0L
            val detail = when {
                pkg !in trackedApps -> "off"
                diag == null -> "idle — no scroll heard yet"
                else -> "fg=${diag.foregroundSightings} · " +
                    "${formatDistance(pixelsToMeters(countedPx, dpi))}" +
                    (diag.dominantPath?.let { " · $it" } ?: "")
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
                "Counters cleared — scroll a feed to start them again.",
                style = MaterialTheme.typography.bodySmall,
                color = Trek.inkFaint,
            )
        }
    }
}
