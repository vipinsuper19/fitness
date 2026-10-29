package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.PastSet
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CorrectionTags {
    const val SECTION = "correction_section"
    const val CLOSED = "correction_closed"
    const val RESULT = "correction_result"
    const val BADGES = "correction_badges"
    const val ERROR = "correction_error"
    const val EDIT_DETAILS = "correction_edit_details"
    const val DISCARD = "correction_discard"
    const val DISCARD_CONFIRM = "correction_discard_confirm"
    const val REPS = "correction_reps"
    const val WEIGHT = "correction_weight"
    const val DONE = "correction_done"
    const val SAVE = "correction_save"
    const val DELETE = "correction_delete"
    const val TIME = "correction_time"
    const val DIFFICULTY = "correction_difficulty"
    const val NOTES = "correction_notes"
    fun editSet(clientSetId: String) = "correction_edit_$clientSetId"
}

private fun CorrectionError.message() = when (this) {
    CorrectionError.WINDOW_CLOSED -> R.string.correction_window_closed
    CorrectionError.SESSION_EMPTY -> R.string.correction_session_empty
    CorrectionError.INVALID_TIME -> R.string.correction_invalid_time
    CorrectionError.INVALID -> R.string.correction_invalid
    CorrectionError.OFFLINE -> R.string.correction_offline
    CorrectionError.FAILED -> R.string.correction_failed
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "+5 XP", "−5 XP" (U+2212) or "0 XP". */
internal fun xpDeltaText(delta: Int): String = when {
    delta > 0 -> "+$delta XP"
    delta < 0 -> "\u2212${-delta} XP"
    else -> "0 XP"
}

/** [hhmm] on [current]'s local date in [zone]; null if it doesn't parse. */
internal fun completedAtFrom(hhmm: String, current: Instant, zone: ZoneId): Instant? {
    val t = runCatching { LocalTime.parse(hhmm.trim(), timeFormat) }.getOrNull() ?: return null
    return current.atZone(zone).toLocalDate().atTime(t).atZone(zone).toInstant()
}

@Composable
internal fun CorrectionSection(
    c: CorrectionState,
    editable: Boolean,
    session: PastSession?,
    units: UnitSystem,
    onSaveDetails: (Instant?, Int?, String?) -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).testTag(CorrectionTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        c.outcome?.let { o ->
            Card(Modifier.fillMaxWidth().testTag(CorrectionTags.RESULT)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.correction_saved, xpDeltaText(o.xpDelta)), style = MaterialTheme.typography.titleSmall)
                    if (o.newBadges.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.correction_new_badges, o.newBadges.size, o.newBadges.joinToString { it.title }),
                            modifier = Modifier.testTag(CorrectionTags.BADGES),
                        )
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.correction_ok)) }
                }
            }
        }
        c.error?.let { e ->
            Text(stringResource(e.message()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CorrectionTags.ERROR))
        }
        if (editable) {
            OutlinedButton(onClick = { details = true }, enabled = !c.busy, modifier = Modifier.testTag(CorrectionTags.EDIT_DETAILS)) {
                Text(stringResource(R.string.correction_edit_details))
            }
        } else if (session?.completedAt != null) {
            Text(stringResource(R.string.correction_closed_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(CorrectionTags.CLOSED))
        }
        // Discard works at any age (no correction window).
        OutlinedButton(onClick = { confirmDiscard = true }, enabled = !c.busy, modifier = Modifier.testTag(CorrectionTags.DISCARD)) {
            Text(stringResource(R.string.correction_discard))
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.correction_discard_title)) },
            text = { Text(stringResource(R.string.correction_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }, modifier = Modifier.testTag(CorrectionTags.DISCARD_CONFIRM)) {
                    Text(stringResource(R.string.correction_discard))
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.correction_cancel)) } },
        )
    }
    if (details && session != null) {
        DetailsDialog(session, c.zone, onSave = { at, d, n -> details = false; onSaveDetails(at, d, n) }, onDismiss = { details = false })
    }
}

@Composable
private fun DetailsDialog(session: PastSession, zone: ZoneId, onSave: (Instant?, Int?, String?) -> Unit, onDismiss: () -> Unit) {
    val current = session.completedAt
    val initialTime = current?.atZone(zone)?.format(timeFormat).orEmpty()
    var time by remember { mutableStateOf(initialTime) }
    var difficulty by remember { mutableStateOf(session.perceivedDifficulty?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(session.notes.orEmpty()) }
    var timeError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.correction_edit_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    time, { time = it; timeError = false }, label = { Text(stringResource(R.string.correction_finished_at)) },
                    supportingText = { Text(stringResource(if (timeError) R.string.correction_invalid_time else R.string.correction_time_hint)) },
                    isError = timeError, singleLine = true, modifier = Modifier.testTag(CorrectionTags.TIME),
                )
                OutlinedTextField(
                    difficulty, { difficulty = it.filter(Char::isDigit).take(1) }, label = { Text(stringResource(R.string.correction_difficulty)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.testTag(CorrectionTags.DIFFICULTY),
                )
                OutlinedTextField(notes, { notes = it.take(1000) }, label = { Text(stringResource(R.string.correction_notes)) }, modifier = Modifier.testTag(CorrectionTags.NOTES))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val at = if (time == initialTime || current == null) null else completedAtFrom(time, current, zone)
                if (time != initialTime && at == null) { timeError = true; return@TextButton }
                val d = difficulty.toIntOrNull()?.takeIf { it in 1..5 && it != session.perceivedDifficulty }
                val n = notes.takeIf { it != session.notes.orEmpty() }
                if (at == null && d == null && n == null) onDismiss() else onSave(at, d, n)
            }, modifier = Modifier.testTag(CorrectionTags.SAVE)) { Text(stringResource(R.string.correction_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.correction_cancel)) } },
    )
}

@Composable
internal fun EditSetDialog(
    set: PastSet,
    units: UnitSystem,
    canDelete: Boolean,
    onSave: (PastSet) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var reps by remember { mutableStateOf(set.reps?.toString().orEmpty()) }
    var weight by remember { mutableStateOf(set.weightKg?.let { trimNumber(UnitConversion.displayWeight(it, units) ?: it).replace(",", "") }.orEmpty()) }
    var done by remember { mutableStateOf(set.completed) }
    val unit = if (units == UnitSystem.IMPERIAL) "lb" else "kg"
    val repsValue = reps.toIntOrNull()
    val weightValue = weight.toDoubleOrNull()
    val valid = (reps.isEmpty() || repsValue in 0..1000) && (weight.isEmpty() || (weightValue != null && weightValue >= 0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_set_n, set.setIndex + 1)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (set.reps != null || set.weightKg != null) {
                    OutlinedTextField(
                        reps, { reps = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.correction_reps)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.testTag(CorrectionTags.REPS),
                    )
                }
                if (set.weightKg != null) {
                    OutlinedTextField(
                        weight, { weight = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) }, label = { Text(stringResource(R.string.correction_weight, unit)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.testTag(CorrectionTags.WEIGHT),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(done, { done = it }, modifier = Modifier.testTag(CorrectionTags.DONE))
                    Text(stringResource(R.string.correction_done))
                }
                if (canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag(CorrectionTags.DELETE)) {
                        Text(stringResource(R.string.correction_delete_set), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        set.copy(
                            reps = if (set.reps != null || set.weightKg != null) repsValue else set.reps,
                            weightKg = if (set.weightKg != null) UnitConversion.weightToKg(weightValue, units) else set.weightKg,
                            completed = done,
                        ),
                    )
                },
                modifier = Modifier.testTag(CorrectionTags.SAVE),
            ) { Text(stringResource(R.string.correction_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.correction_cancel)) } },
    )
}
