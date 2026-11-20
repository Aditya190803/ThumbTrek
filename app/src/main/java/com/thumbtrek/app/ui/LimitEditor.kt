package com.thumbtrek.app.ui

import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thumbtrek.app.data.Prefs
import com.thumbtrek.app.stats.BASELINE_DAYS
import com.thumbtrek.app.stats.BILLING_ENFORCED
import com.thumbtrek.app.stats.MAX_DAILY_LIMIT_M
import com.thumbtrek.app.stats.MIN_DAILY_LIMIT_M
import com.thumbtrek.app.stats.comparison
import com.thumbtrek.app.stats.suggestedLimit
import com.thumbtrek.app.stats.formatDistance

// ---------------------------------------------------------------------------------------
// Daily limit editor
// ---------------------------------------------------------------------------------------

/**
 * The limit editor. Saves through the weekly free-edit quota in [Prefs]: the first change
 * each ISO week is free, further ones need premium, and the result line says which one
 * just happened so "Save" never looks dead.
 */
@Composable
internal fun LimitEditor(vm: SettingsViewModel, currentM: Float?, typicalM: Double?, learning: Boolean) {
    var input by remember(currentM) { mutableStateOf(currentM?.toInt()?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    // A hand-typed limit, held until the user confirms it.
    var pendingLimit by remember { mutableStateOf<Float?>(null) }
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
            focus.clearFocus()
            // Every hand-set limit is confirmed first; see ManualLimitDialog.
            pendingLimit = value
        }
    }

    pendingLimit?.let { pending ->
        ManualLimitDialog(
            newM = pending.toDouble(),
            typicalM = typicalM,
            currentM = currentM?.toDouble(),
            onConfirm = {
                vm.trySetLimit(pending)
                pendingLimit = null
            },
            onDismiss = { pendingLimit = null },
            onUseBaseline = if (learning) null else {
                {
                    vm.startBaseline()
                    pendingLimit = null
                }
            },
            onUseSuggested = { suggested ->
                vm.trySetLimit(suggested)
                pendingLimit = null
            },
        )
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
                focusedTextColor = Trek.text,
                unfocusedTextColor = Trek.text,
                errorTextColor = Trek.text,
                focusedLabelColor = Trek.textFaint,
                unfocusedLabelColor = Trek.textFaint,
                errorLabelColor = Trek.danger,
                focusedBorderColor = Trek.accent,
                unfocusedBorderColor = Trek.line,
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
                color = Trek.textDim,
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

/**
 * The caution step every hand-set limit goes through. Baseline is the default way to a
 * limit, so a manual one is confirmed against what is known about the user's scrolling,
 * and the dialog offers the better option where there is one. It never blocks.
 */
@Composable
internal fun ManualLimitDialog(
    newM: Double,
    typicalM: Double?,
    currentM: Double?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onUseBaseline: (() -> Unit)? = null,
    onUseSuggested: ((Float) -> Unit)? = null,
) {
    val suggested = typicalM?.let { suggestedLimit(it) }
    val verb = if (currentM == null) "Set" else "Change"
    val (title, body) = when {
        typicalM == null -> "Set a limit without a baseline?" to
            "ThumbTrek doesn’t know your usual day yet, so ${formatDistance(newM)} is a guess. " +
            "Too low and every day reads as over; too high and it never matters. Learning your " +
            "baseline takes $BASELINE_DAYS tracked days and sets a limit that fits you."
        newM > typicalM -> "$verb your limit to ${formatDistance(newM)}?" to
            "That’s above your usual day of about ${formatDistance(typicalM)}, so most days " +
            "would pass under it without you noticing. Limits work best a little below what " +
            "you normally scroll."
        newM < typicalM * 0.5 -> "$verb your limit to ${formatDistance(newM)}?" to
            "That’s less than half your usual day of about ${formatDistance(typicalM)}, so most " +
            "days will count as over. Big cuts are hard to keep. About " +
            "${formatDistance(suggested!!.toDouble())} is a steadier start."
        else -> "$verb your limit to ${formatDistance(newM)}?" to
            "That’s ${((1 - newM / typicalM) * 100).toInt()}% under your usual day of about " +
            "${formatDistance(typicalM)}. Clean days are judged against it from today."
    }
    val offerSuggested = typicalM != null && newM < typicalM * 0.5 && onUseSuggested != null &&
        suggested != null && suggested.toDouble() != newM
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Trek.surface,
        titleContentColor = Trek.text,
        textContentColor = Trek.textDim,
        shape = MaterialTheme.shapes.large,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                if (typicalM == null && onUseBaseline != null) {
                    TrekButton("Learn my baseline instead", onUseBaseline, Modifier.fillMaxWidth())
                }
                if (offerSuggested) {
                    TrekButton("Use ${formatDistance(suggested!!.toDouble())} instead", { onUseSuggested!!(suggested) }, Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("$verb to ${formatDistance(newM)}", color = Trek.text) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Trek.textDim) }
        },
    )
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
                    .border(1.dp, if (active) Trek.accent else Trek.line, CircleShape)
                    .selectable(selected = active, role = Role.RadioButton) { onPick(meters) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "$meters m",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Trek.accent else Trek.text,
                    maxLines = 1,
                )
            }
        }
    }
}
