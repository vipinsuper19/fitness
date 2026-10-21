package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.session.SessionExercise
import com.vipin.fitness.core.data.session.SessionSet
import com.vipin.fitness.core.data.workout.TrackingType

@Composable
fun ActiveSessionRoute(
    onFinished: (String) -> Unit,
    onReplace: (position: Int) -> Unit,
    onOpenExercise: (String) -> Unit,
    onNoSession: () -> Unit,
    viewModel: ActiveSessionViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.finishedId) { s.finishedId?.let(onFinished) }
    LaunchedEffect(s.loading, s.session) { if (!s.loading && s.session == null && s.finishedId == null) onNoSession() }
    val session = s.session
    if (session == null) {
        LoadingBox()
        return
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.name, style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(formatClock(s.activeSeconds), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("session_timer"))
                if (s.paused) Text(stringResource(R.string.paused), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = viewModel::togglePause, modifier = Modifier.testTag("pause_resume")) {
                    Text(stringResource(if (s.paused) R.string.resume else R.string.pause))
                }
            }
            if (s.pendingWrites > 0) {
                Text(pluralStringResource(R.plurals.pending_sync, s.pendingWrites, s.pendingWrites), style = MaterialTheme.typography.bodySmall)
            }
            session.syncError?.let { Text(stringResource(R.string.sync_error), color = MaterialTheme.colorScheme.error) }
        }
        s.rest?.let { rest -> RestBar(s.restRemaining, rest.totalSeconds, viewModel::skipRest) { viewModel.addRest(30) } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(session.exercises, key = { it.position }) { e ->
                ExerciseCard(
                    e,
                    enabled = !s.paused,
                    onToggle = viewModel::toggleSet,
                    onEdit = viewModel::editSet,
                    onSkip = { viewModel.setSkipped(e.position, !e.skipped) },
                    onReplace = { onReplace(e.position) },
                    onOpen = { onOpenExercise(e.exerciseId) },
                )
            }
        }
        Button(onClick = viewModel::openFinish, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("finish_workout")) {
            Text(stringResource(R.string.finish_workout))
        }
    }
    if (s.showFinish) FinishDialog(s, viewModel)
}

@Composable
private fun RestBar(remaining: Long, total: Int, onSkip: () -> Unit, onAdd: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("rest_timer")) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.rest_remaining, formatClock(remaining)), modifier = Modifier.weight(1f))
                TextButton(onClick = onAdd) { Text(stringResource(R.string.rest_add_30)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.rest_skip)) }
            }
            LinearProgressIndicator(progress = { if (total > 0) remaining.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ExerciseCard(
    e: SessionExercise,
    enabled: Boolean,
    onToggle: (SessionSet) -> Unit,
    onEdit: (SessionSet, Int?, Double?, Int?) -> Unit,
    onSkip: () -> Unit,
    onReplace: () -> Unit,
    onOpen: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("session_exercise_${e.position}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onOpen) {
                Text(e.name.ifEmpty { stringResource(R.string.exercise_unknown) }, style = MaterialTheme.typography.titleMedium)
            }
            if (e.replaced) Text(stringResource(R.string.replaced), style = MaterialTheme.typography.labelSmall)
            if (e.skipped) {
                Text(stringResource(R.string.skipped), color = MaterialTheme.colorScheme.error)
            } else {
                e.sets.forEach { set -> SetRow(e.trackingType, set, enabled, onToggle, onEdit) }
            }
            Row {
                TextButton(onClick = onSkip, enabled = enabled) { Text(stringResource(if (e.skipped) R.string.unskip else R.string.skip)) }
                TextButton(onClick = onReplace, enabled = enabled && !e.skipped) { Text(stringResource(R.string.replace)) }
            }
        }
    }
}

@Composable
private fun SetRow(tracking: TrackingType, set: SessionSet, enabled: Boolean, onToggle: (SessionSet) -> Unit, onEdit: (SessionSet, Int?, Double?, Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.set_n, set.setIndex + 1), modifier = Modifier.weight(0.6f))
        if (tracking.usesDuration) {
            SessionNumber(R.string.duration_s, set.durationSeconds?.toString(), Modifier.weight(1.6f), enabled) {
                onEdit(set, set.reps, set.weightKg, it.toIntOrNull())
            }
        } else {
            SessionNumber(R.string.reps, set.reps?.toString(), Modifier.weight(0.8f), enabled) { onEdit(set, it.toIntOrNull(), set.weightKg, set.durationSeconds) }
            if (tracking == TrackingType.REPS_WEIGHT) {
                SessionNumber(R.string.weight_kg, set.weightKg?.let(::kg), Modifier.weight(0.8f), enabled, decimal = true) {
                    onEdit(set, set.reps, it.toDoubleOrNull()?.takeIf { w -> w in 0.0..1000.0 }, set.durationSeconds)
                }
            }
        }
        Checkbox(checked = set.completed, onCheckedChange = { onToggle(set) }, enabled = enabled, modifier = Modifier.testTag("set_${set.clientSetId}"))
    }
}

/** Keeps the raw text while typing ("12." stays), reports parsed values up. */
@Composable
private fun SessionNumber(label: Int, value: String?, modifier: Modifier, enabled: Boolean, decimal: Boolean = false, onChange: (String) -> Unit) {
    var text by remember(value == null) { mutableStateOf(value.orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') }.take(7)
            text = clean
            onChange(clean)
        },
        label = { Text(stringResource(label)) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun FinishDialog(s: ActiveSessionState, vm: ActiveSessionViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissFinish,
        title = { Text(stringResource(R.string.finish_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.how_hard))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { d ->
                        FilterChip(selected = s.difficulty == d, onClick = { vm.setDifficulty(d) }, label = { Text("$d") }, modifier = Modifier.testTag("difficulty_$d"))
                    }
                }
                Text(stringResource(R.string.difficulty_scale), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = s.notes, onValueChange = vm::setNotes, label = { Text(stringResource(R.string.notes)) }, modifier = Modifier.fillMaxWidth())
                when (s.finishError) {
                    FinishError.NO_COMPLETED_SETS -> Text(stringResource(R.string.finish_needs_set), color = MaterialTheme.colorScheme.error)
                    FinishError.NO_SESSION, null -> Unit
                }
            }
        },
        confirmButton = { Button(onClick = vm::finish, modifier = Modifier.testTag("confirm_finish")) { Text(stringResource(R.string.finish)) } },
        dismissButton = { TextButton(onClick = vm::dismissFinish) { Text(stringResource(R.string.keep_going)) } },
    )
}

internal fun formatClock(seconds: Long): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val sec = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun kg(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
