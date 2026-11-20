package com.thumbtrek.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.TRACKED_APPS
import com.thumbtrek.app.data.appName
import com.thumbtrek.app.stats.formatDistance
import com.thumbtrek.app.stats.pixelsToMeters
import com.thumbtrek.app.track.ScrollDiagnostics

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
internal fun DiagnosticsTable(
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
                color = Trek.textFaint,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        packages.forEachIndexed { index, pkg ->
            val diag = byPkg[pkg]
            val countedPx = diag?.countedPixels ?: 0L
            val detail = when {
                pkg !in trackedApps -> "Not tracked"
                diag == null -> "Idle. No scroll heard yet"
                else -> "Seen in foreground ${diag.foregroundSightings} times, " +
                    "${formatDistance(pixelsToMeters(countedPx, dpi))} counted" +
                    (diag.dominantPath?.let { ", via $it" } ?: "")
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
                        color = if (pkg in trackedApps) Trek.text else Trek.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Trek.textFaint,
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
                color = Trek.textFaint,
            )
        }
    }
}
