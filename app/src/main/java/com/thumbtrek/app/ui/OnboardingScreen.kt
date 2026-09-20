package com.thumbtrek.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.data.TRACKED_APPS
import com.thumbtrek.app.data.parseDailyLimit
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.weekKey

/** Setup choices persist before leaving for Android permissions or account selection. */
@Composable
fun OnboardingScreen(settings: SettingsViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val step by prefs.onboardingStep.collectAsStateWithLifecycle()
    val state by settings.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf(prefs.limitDraft) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var notificationsAllowed by remember { mutableStateOf(false) }
    val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = it
    }
    LifecycleResumeEffect(Unit) {
        settings.refreshTrackingState()
        notificationsAllowed = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }
    BackHandler(enabled = step > 0) { prefs.setOnboardingStep(step - 1) }
    if (showPicker) AppPickerDialog(settings) { showPicker = false }

    Scaffold(containerColor = Trek.ground, contentColor = Trek.ink) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(key(step) { rememberScrollState() }).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandMark(Modifier.size(36.dp))
                Spacer(Modifier.weight(1f))
                Text("${step + 1} of 4", style = TrekOverline, color = Trek.inkMuted)
                if (step > 0) TextButton(onClick = { prefs.setOnboardingStep(step - 1) }) { Text("Back") }
            }
            Rail((step + 1) / 4f, height = 4.dp)
            when (step) {
                0 -> {
                    PageHeading("A little more awareness.", "Make ThumbTrek yours in a few small steps.")
                    TrekPanel {
                        Text("Your scrolls, made visible", style = MaterialTheme.typography.titleLarge, color = Trek.ink)
                        Spacer(Modifier.height(12.dp))
                        Text("See how far your thumb travels across the apps you choose. Distance is an estimate, not time spent on your phone.", color = Trek.inkMuted)
                    }
                    Text("Only distance is measured", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                    Text("ThumbTrek never reads posts, messages, passwords or other screen content. Tracking stays on this device unless you explicitly turn on sharing in Social.", color = Trek.inkMuted)
                    Text("Your pace, your choice", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                    Text("Choose your apps, decide whether you want a limit, and sign in only if you want to. You can change these choices in Settings.", color = Trek.inkMuted)
                    TrekButton("Get started", { prefs.setOnboardingStep(1) }, Modifier.fillMaxWidth())
                }
                1 -> {
                    PageHeading("What would you like to track?", "Choose the apps you want a little perspective on.")
                    Column {
                        (TRACKED_APPS + state.customApps).forEach { (pkg, label) ->
                            ToggleRow(label, pkg in state.trackedApps, { settings.setAppTracked(pkg, it) },
                                leading = { AppToken(label, chartColor(pkg)) })
                        }
                        TextButton(onClick = { settings.loadInstalledApps(); showPicker = true }) { Text("Add another app") }
                    }
                    TrekPanel {
                        Text(if (state.trackingEnabled) "Tracking is enabled" else "Allow scroll measurement", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                        Spacer(Modifier.height(8.dp))
                        Text("Android’s Accessibility permission lets ThumbTrek count scroll movements in your selected apps. It does not read screen content or control your phone. You can turn it off at any time.", color = Trek.inkMuted)
                        if (!state.trackingEnabled) {
                            Spacer(Modifier.height(16.dp))
                            TrekGhostButton("Open Accessibility settings", {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }, Modifier.fillMaxWidth())
                        }
                    }
                    if (state.trackedApps.isEmpty()) Text("No apps selected. Nothing will be measured until you choose an app in Settings.", color = Trek.inkMuted)
                    TrekButton(if (state.trackingEnabled) "Continue" else "Continue for now", { prefs.setOnboardingStep(2) }, Modifier.fillMaxWidth())
                    if (!state.trackingEnabled) Text("You can enable tracking later in Settings.", style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
                }
                2 -> {
                    PageHeading("Find your own pace.", "There is no one-size-fits-all daily limit.")
                    Text("Choose a distance that feels right for you, or track your usual scrolling first. A limit is a gentle reference, not a block on your apps.", color = Trek.inkMuted)
                    val limit = parseDailyLimit(input)
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it.filter(Char::isDigit).take(5); prefs.limitDraft = input; saveError = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Your daily limit in metres") },
                        supportingText = { Text(if (input.isNotEmpty() && limit == null) "Enter between 10 and 10,000 metres." else "10–10,000 metres. You can change this later.") },
                        isError = input.isNotEmpty() && limit == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    if (limit != null) Text(comparison(limit.toDouble()), color = Trek.inkMuted)
                    saveError?.let { Text(it, color = Trek.danger) }
                    TrekButton("Use my limit", {
                        if (limit != null) {
                            when (prefs.trySetDailyLimit(limit, weekKey())) {
                                is Prefs.LimitEditResult.Applied -> prefs.setOnboardingStep(3)
                                Prefs.LimitEditResult.NeedsPremium -> saveError = "Your limit could not be changed. Keep your current limit or track without one."
                            }
                        }
                    }, Modifier.fillMaxWidth(), enabled = limit != null)
                    TrekGhostButton("Learn my baseline first", {
                        prefs.clearDailyLimit()
                        input = ""
                        prefs.setOnboardingStep(3)
                    }, Modifier.fillMaxWidth())
                    Text("No limit, warnings or clean-day scores. Review your history after a few days, then set a limit in Settings when you’re ready.", style = MaterialTheme.typography.bodyMedium, color = Trek.inkMuted)
                }
                3 -> {
                    val social: SocialViewModel = viewModel()
                    val account by social.state.collectAsStateWithLifecycle()
                    PageHeading("Your trek. Your choice.", "An account is completely optional.")
                    TrekPanel {
                        Text(if (account.signedIn) "You’re signed in" else "Bring your people along", style = MaterialTheme.typography.titleLarge, color = Trek.ink)
                        Spacer(Modifier.height(12.dp))
                        Text("Sign in to get ready for friend comparisons and syncing. Signing in alone shares nothing. You’ll decide whether to publish and sync from the Social tab.", color = Trek.inkMuted)
                        if (!account.signedIn) {
                            Spacer(Modifier.height(16.dp))
                            TrekButton("Continue with Google", { social.signIn(context) }, Modifier.fillMaxWidth(), loading = account.busy)
                        }
                        account.error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Trek.danger) }
                    }
                    Text("A daily check-in, if you want one", style = MaterialTheme.typography.titleMedium, color = Trek.ink)
                    Text("Allow notifications for daily distance summaries. Streak reminders and limit nudges stay off unless you enable them in Settings.", color = Trek.inkMuted)
                    if (notificationsAllowed) {
                        Text("Notifications are allowed. Manage them in Android settings.", color = Trek.inkMuted)
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        TrekGhostButton("Allow notifications", { notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS) }, Modifier.fillMaxWidth())
                    }
                    TrekButton(if (account.signedIn) "Start my trek" else "Continue without an account", {
                        prefs.setOnboardingStep(4)
                    }, Modifier.fillMaxWidth(), enabled = !account.busy)
                    Text("Your choices are saved. Revisit them anytime in Settings → Review setup.", style = MaterialTheme.typography.bodySmall, color = Trek.inkMuted)
                }
            }
        }
    }
}
