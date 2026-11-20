package com.thumbtrek.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * The one way into Accessibility settings to turn tracking on.
 *
 * Google Play requires apps that use the AccessibilityService API without being an
 * accessibility tool to show a prominent disclosure first. It has to say what is accessed
 * and why, appear during normal use and not only in the privacy policy, and get an explicit
 * "agree" tap. Navigating away doesn't count as consent. Every "turn on tracking" button
 * goes through this, so none of them can skip it.
 *
 * Returns a function that opens the disclosure. Agreeing opens Accessibility settings.
 * Declining just closes it.
 */
@Composable
fun rememberTrackingConsent(): () -> Unit {
    val context = LocalContext.current
    var showing by rememberSaveable { mutableStateOf(false) }

    if (showing) {
        TrackingDisclosure(
            onAgree = {
                showing = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
            onDecline = { showing = false },
        )
    }
    return remember { { showing = true } }
}

@Composable
private fun TrackingDisclosure(onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
        shape = MaterialTheme.shapes.large,
        title = { Text("ThumbTrek uses the Accessibility service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "To measure how far you scroll, ThumbTrek uses Android's Accessibility " +
                        "Service API. Here is exactly what that means:",
                )
                Text(
                    "• It receives scroll events from the apps you chose to track, and " +
                        "keeps only the distance scrolled.",
                )
                Text(
                    "• It never reads text, images, links or anything else on your screen, " +
                        "and never taps, types or controls your phone.",
                )
                Text(
                    "• Distances are stored on this phone. They're only uploaded if you sign " +
                        "in and turn on leaderboards, and then only as distances per app per day.",
                )
                Text(
                    "Tap Agree to open Accessibility settings, then turn on ThumbTrek. You " +
                        "can turn it off there at any time.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAgree) { Text("Agree", color = Trek.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("No thanks", color = Trek.textDim) }
        },
    )
}
