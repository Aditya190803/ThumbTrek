package com.thumbtrek.app.update

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * The updater's settings surface.
 *
 * Two entry points so whoever owns `ui/SettingsScreen.kt` can pick:
 *
 * ```kotlin
 * // inside the screen's own card helper:
 * SettingsCard("Updates") { UpdateSettingsContent() }
 *
 * // or standalone, with its own container:
 * UpdateCard()
 * ```
 *
 * Intentionally built from plain Material3 components rather than the app's own panel
 * helpers, so this file does not break when the design system moves underneath it. Restyle
 * freely — the state holder is [UpdateViewModel] and nothing here holds state of its own.
 */
@Composable
fun UpdateCard(
    modifier: Modifier = Modifier,
    vm: UpdateViewModel = viewModel(),
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Updates", style = MaterialTheme.typography.titleMedium)
            UpdateSettingsContent(vm = vm)
        }
    }
}

/**
 * The contents only — no card, no section title — for dropping into an existing settings
 * card. Everything the user can do with the updater is here: the automatic-check switch,
 * a manual check, the one-time "install unknown apps" grant, and the install button.
 */
@Composable
fun UpdateSettingsContent(
    modifier: Modifier = Modifier,
    vm: UpdateViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // No broadcast exists for "user changed the install-unknown-apps toggle", so re-read it
    // every time this screen comes back — typically right after the trip to Settings below.
    LifecycleResumeEffect(Unit) {
        vm.refreshInstallPermission()
        onPauseOrDispose { }
    }

    val unknownSources = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.refreshInstallPermission() }

    // When PackageInstaller asks for confirmation while this screen is up, we are the
    // foreground activity, so the system dialog can be launched straight from here.
    LaunchedEffect(Unit) {
        vm.confirmIntents.collect { intent ->
            vm.consumeConfirmIntent()
            runCatching { context.startActivity(intent) }
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Installed: ${state.installedVersionName} (build ${state.installedVersionCode})",
            style = MaterialTheme.typography.bodyMedium,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Check for updates automatically", modifier = Modifier.padding(end = 12.dp))
            Switch(
                checked = state.autoCheckEnabled,
                onCheckedChange = vm::setAutoCheck,
            )
        }
        Text(
            "ThumbTrek isn't on the Play Store, so it looks for new builds itself: every " +
                "six hours it asks GitHub whether a newer release exists and downloads it " +
                "over Wi-Fi. The check only ever contacts github.com and sends nothing " +
                "about you or your scrolling.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // --- the one-time system grant ---------------------------------------------
        if (!state.canInstallUnknownApps) {
            Text(
                "Android needs your permission before ThumbTrek can install its own " +
                    "updates. This is a one-time switch in system settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = {
                    unknownSources.launch(ApkInstaller.unknownAppSourcesIntent(context))
                },
            ) {
                Text("Allow ThumbTrek to install updates")
            }
        }

        UpdateStatusBlock(state = state, vm = vm)

        Text(
            lastCheckedLabel(state.lastCheckedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TextButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(UpdateConfig.RELEASES_PAGE)),
                    )
                }
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text("View releases on GitHub")
        }
    }
}

/** The bit that changes: whatever the state machine is currently doing. */
@Composable
private fun UpdateStatusBlock(state: UpdateUiState, vm: UpdateViewModel) {
    when (val status = state.status) {

        UpdateStatus.Idle, UpdateStatus.UpToDate -> {
            if (status == UpdateStatus.UpToDate) {
                Text(
                    "You're on the newest release.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedButton(onClick = vm::checkNow) { Text("Check for updates") }
        }

        UpdateStatus.Checking -> {
            Text("Checking GitHub…", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is UpdateStatus.Available -> {
            Text(
                "ThumbTrek ${status.manifest.versionName} is available.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReleaseNotes(status.manifest)
            Button(onClick = { vm.download(status.manifest) }) {
                Text("Download ${sizeLabel(status.manifest.sizeBytes)}")
            }
        }

        is UpdateStatus.Downloading -> {
            Text(
                "Downloading ${status.manifest.versionName}…",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (status.percent >= 0) {
                LinearProgressIndicator(
                    progress = { status.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        is UpdateStatus.ReadyToInstall -> {
            Text(
                "ThumbTrek ${status.manifest.versionName} is downloaded and its checksum " +
                    "matches the release.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ReleaseNotes(status.manifest)
            // The honest bit. Do not dress this up: a sideloaded app cannot install itself
            // behind the user's back, and pretending otherwise makes the system dialog
            // look like something has gone wrong.
            Text(
                "Tapping Install hands the file to Android, which will ask you to confirm. " +
                    "Apps installed outside the Play Store can't skip that prompt — " +
                    "everything before it (finding, downloading, verifying) already happened " +
                    "in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = vm::install,
                enabled = state.canInstallUnknownApps,
            ) {
                Text("Install ${status.manifest.versionName}")
            }
        }

        is UpdateStatus.Installing -> {
            Text(
                "Handing ${status.manifest.versionName} to Android — confirm the install " +
                    "when it asks.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is UpdateStatus.Failed -> {
            Text(
                status.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::checkNow) { Text("Try again") }
                TextButton(onClick = vm::dismissStatus) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun ReleaseNotes(manifest: UpdateManifest) {
    if (manifest.notes.isBlank()) return
    Text(
        manifest.notes,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun lastCheckedLabel(millis: Long): String =
    if (millis <= 0L) {
        "Never checked."
    } else {
        "Last checked " + DateUtils.getRelativeTimeSpanString(
            millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        )
    }

private fun sizeLabel(bytes: Long): String =
    if (bytes <= 0L) "" else "(%.1f MB)".format(bytes / 1024.0 / 1024.0)
