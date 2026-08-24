package com.thumbtrek.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thumbtrek.app.data.TRACKED_APPS
import com.thumbtrek.app.share.DataExporter
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: SettingsViewModel = viewModel()) {
    LifecycleResumeEffect(Unit) {
        vm.refreshTrackingState()
        onPauseOrDispose { }
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showPicker by remember { mutableStateOf(false) }

    // PRD §5.5 reminders are a notification, and on Android 13+ that needs a runtime grant.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) vm.setStreakReminder(true)
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCard("Tracking") {
            Text(
                if (state.trackingEnabled) {
                    "ThumbTrek's Accessibility Service is on and counting your scrolls."
                } else {
                    "Tracking is off. ThumbTrek needs its Accessibility Service to count how " +
                        "far you scroll."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.trackingEnabled) {
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text("Enable tracking")
                }
            }
        }

        SettingsCard("Tracked apps") {
            Text(
                "Pick which feeds count towards your trek.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TRACKED_APPS.forEach { (pkg, name) ->
                SettingsToggleRow(
                    label = name,
                    checked = pkg in state.trackedApps,
                    onCheckedChange = { vm.setAppTracked(pkg, it) },
                )
            }
            state.customApps.forEach { (pkg, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsToggleRow(
                        label = label,
                        checked = pkg in state.trackedApps,
                        onCheckedChange = { vm.setAppTracked(pkg, it) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.removeCustomApp(pkg) }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            OutlinedButton(onClick = {
                vm.loadInstalledApps()
                showPicker = true
            }) {
                Text("Add another app")
            }
            if (TRACKED_APPS.keys.none { it in state.trackedApps } && state.customApps.isEmpty()) {
                Text(
                    "Every app is switched off, so nothing will be measured. Turn at least one " +
                        "back on to keep your trek going.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        SettingsCard("Calibration") {
            Text(
                "Different apps report scroll pixels differently. Nudge an app's multiplier " +
                    "if its distances feel off. Applies to future scrolls only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val knownApps = TRACKED_APPS.keys + state.customApps.keys
            knownApps.forEach { pkg ->
                CalibrationRow(
                    label = TRACKED_APPS[pkg] ?: state.customApps[pkg] ?: pkg,
                    factor = state.calibration[pkg] ?: 1f,
                    onFactorChange = { vm.setCalibration(pkg, it) },
                )
            }
        }

        SettingsCard("Notifications") {
            SettingsToggleRow(
                label = "Streak reminder",
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
                "Off by default. One quiet nudge if your streak is about to lapse — ThumbTrek " +
                    "is not here to nag you about your screen time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Your data") {
            Button(
                onClick = {
                    scope.launch {
                        runCatching { DataExporter.export(context) }
                            .onSuccess { uri ->
                                context.startActivity(
                                    Intent.createChooser(DataExporter.shareIntent(uri), "Export trek"),
                                )
                            }
                    }
                },
            ) {
                Text("Export my data (CSV)")
            }
            Text(
                "Everything stays on your device unless you opt into the leaderboard. The CSV " +
                    "contains one row per app per day — dates and pixel counts, nothing else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Privacy") {
            Text(
                "ThumbTrek measures distance, never content. The service listens for one thing — " +
                    "scroll events from the apps you picked above — and asks for no permission to " +
                    "read what is on your screen. It cannot see your posts, messages, or what you " +
                    "type.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Scroll distances are stored in a database on this phone. Nothing is uploaded " +
                    "anywhere unless you opt into the leaderboard on the Social tab.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "ThumbTrek 0.1.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showPicker) {
        AppPickerDialog(
            vm = vm,
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AppPickerDialog(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val apps by vm.installedApps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track another app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (apps.isEmpty()) {
                    Text(
                        "No other launchable apps found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        apps.forEach { app ->
                            TextButtonWithLabel(app.label) {
                                vm.addCustomApp(app.packageName, app.label)
                                onDismiss()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun TextButtonWithLabel(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun CalibrationRow(label: String, factor: Float, onFactorChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "×${String.format(java.util.Locale.US, "%.2f", factor)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = factor,
            onValueChange = onFactorChange,
            valueRange = CALIBRATION_MIN..CALIBRATION_MAX,
            steps = CALIBRATION_STEPS,
        )
    }
}

/** 0.25 → 3.00 in quarters: 11 stops, 10 gaps between them. */
private const val CALIBRATION_MIN = 0.25f
private const val CALIBRATION_MAX = 3f
private const val CALIBRATION_STEPS = 10

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    TrekCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
