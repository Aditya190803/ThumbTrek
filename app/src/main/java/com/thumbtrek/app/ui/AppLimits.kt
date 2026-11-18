package com.thumbtrek.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.MAX_DAILY_LIMIT_M
import com.thumbtrek.app.stats.formatDistance

/**
 * A separate limit for each app. One row per tracked app with what its limit is; tapping a
 * row opens a small dialog to set, change or remove it. An app limit never replaces the
 * daily limit: the day is still judged on the total, and this adds a second line for the
 * one app a person wants to watch.
 */
@Composable
internal fun AppLimitsEditor(
    apps: List<Pair<String, String>>,
    limits: Map<String, Float>,
    onSet: (pkg: String, meters: Float?) -> Unit,
) {
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column {
        Text("A separate limit for each app", style = MaterialTheme.typography.titleSmall, color = Trek.text)
        Text(
            "Optional. An app with its own limit shows it on Today, next to its lane.",
            style = MaterialTheme.typography.bodySmall,
            color = Trek.textDim,
        )
        apps.forEach { app ->
            val (pkg, label) = app
            val limit = limits[pkg]
            LaneLine(color = Trek.lineSoft)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (limit == null) "Set a limit for $label" else "Change the limit for $label",
                    ) { editing = app }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppToken(label, chartColor(pkg))
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = Trek.text)
                Text(
                    limit?.let { "${formatDistance(it.toDouble())} a day" } ?: "No limit",
                    style = if (limit == null) MaterialTheme.typography.bodyMedium else TrekFigure,
                    color = if (limit == null) Trek.textFaint else Trek.text,
                )
            }
        }
    }

    editing?.let { (pkg, label) ->
        AppLimitDialog(
            label = label,
            current = limits[pkg],
            onSave = {
                onSet(pkg, it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AppLimitDialog(
    label: String,
    current: Float?,
    onSave: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(current?.toInt()?.toString().orEmpty()) }
    val value = input.toFloatOrNull()
    val min = Prefs.MIN_APP_LIMIT_M.toInt()
    val max = MAX_DAILY_LIMIT_M.toInt()
    val valid = value != null && value >= min && value <= max

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
        shape = MaterialTheme.shapes.large,
        title = { Text("Limit for $label") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter(Char::isDigit).take(5) },
                    label = { Text("Metres per day") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    isError = input.isNotEmpty() && !valid,
                    supportingText = { Text("Between $min and $max m.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Trek.text,
                        unfocusedTextColor = Trek.text,
                        errorTextColor = Trek.text,
                        focusedLabelColor = Trek.textFaint,
                        unfocusedLabelColor = Trek.textFaint,
                        focusedBorderColor = Trek.accent,
                        unfocusedBorderColor = Trek.line,
                        errorBorderColor = Trek.danger,
                    ),
                )
                if (current != null) {
                    TextButton(onClick = { onSave(null) }) { Text("Remove this limit", color = Trek.danger) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = valid) { Text("Save", color = Trek.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.textDim) }
        },
    )
}
