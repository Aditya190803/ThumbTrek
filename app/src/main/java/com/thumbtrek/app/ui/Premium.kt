@file:OptIn(ExperimentalMaterial3Api::class)

package com.thumbtrek.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.stats.BILLING_ENFORCED

// Premium, as the user sees it (DESIGN.md, "Premium"). The features exist and are open to
// everyone: BILLING_ENFORCED is false, so nothing is locked and the button cannot be
// pressed. When billing lands, the entitlement record decides (sync-protocol §9).
// The list and the prices are the same strings on the site, the dashboard and the extension.

/** What Premium includes. All built; all free until Premium goes on sale. */
val PREMIUM_FEATURES = listOf(
    "One limit across your phone and your browser",
    "A separate limit for each app",
    "Weekly and monthly reports",
    "Private groups and challenges with friends",
    "Backup and restore of your full history",
)

const val PREMIUM_PRICE = "₹99 a month"
const val PREMIUM_TERMS = "Or ₹799 a year. Planned pricing, not on sale yet. Everything below is free until then."

/**
 * A planned Premium feature as an ordinary row: its name, one line on how things work
 * today, and a small chip. Nothing is blurred or hidden. Tapping it opens the upgrade sheet.
 */
@Composable
fun PremiumRow(title: String, today: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "See what Premium adds", onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Trek.text)
            Text(today, style = MaterialTheme.typography.bodySmall, color = Trek.textDim)
        }
        PremiumChip()
    }
}

/** The plan itself, on track blue: what it adds, the price, and one primary button. */
@Composable
fun PremiumPlan(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(TrackBlue, RoundedCornerShape(20.dp))
            .padding(20.dp),
    ) {
        Text(
            "Premium",
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            color = Chalk,
        )
        Spacer(Modifier.height(10.dp))
        Text(PREMIUM_PRICE, style = MaterialTheme.typography.displaySmall, color = Chalk)
        Spacer(Modifier.height(4.dp))
        Text(PREMIUM_TERMS, style = MaterialTheme.typography.bodyMedium, color = ChalkDim)
        Spacer(Modifier.height(16.dp))
        PREMIUM_FEATURES.forEach { feature ->
            LaneLine(color = Chalk.copy(alpha = 0.4f))
            Text(
                feature,
                modifier = Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Chalk,
            )
        }
        LaneLine(color = Chalk.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {},
            // There is nothing to buy until billing exists, whatever the flag says.
            enabled = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Volt,
                contentColor = OnVolt,
                disabledContainerColor = Volt.copy(alpha = 0.55f),
                disabledContentColor = OnVolt,
            ),
        ) {
            Text(
                if (BILLING_ENFORCED) "Not available yet" else "Not on sale yet",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The upgrade sheet: one promise about what stays free, then the plan. */
@Composable
fun PremiumSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Trek.bg,
        contentColor = Trek.text,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Premium, free for now",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineMedium,
                color = Trek.text,
            )
            Text(
                "Tracking is free, needs no account, and stays that way. These five " +
                    "features will be Premium once it goes on sale. Until then they are " +
                    "open to everyone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Trek.textDim,
            )
            Spacer(Modifier.height(4.dp))
            PremiumPlan()
        }
    }
}
