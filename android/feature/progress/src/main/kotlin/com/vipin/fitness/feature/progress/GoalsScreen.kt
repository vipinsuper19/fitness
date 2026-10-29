package com.vipin.fitness.feature.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.GoalProgress
import com.vipin.fitness.core.data.GoalStatus
import com.vipin.fitness.core.data.GoalType
import com.vipin.fitness.core.data.MeasureDimension

@Composable
internal fun GoalsScreen(vm: GoalsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.load() }
    GoalsContent(s, vm::load, { vm.openNew() }, vm::setStatus, vm::delete)
    s.form?.let { GoalFormDialog(it, s.units, s.saving, vm::onForm, vm::save, vm::closeForm) }
}

@Composable
internal fun GoalsContent(
    s: GoalsState,
    onRetry: () -> Unit,
    onAdd: () -> Unit,
    onStatus: (String, GoalStatus) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onAdd, enabled = !s.saving, modifier = Modifier.fillMaxWidth().testTag(GoalsTags.ADD)) { Text(stringResource(R.string.goal_add)) }
        s.writeError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(GoalsTags.WRITE_ERROR)) }
        when {
            s.loading && s.items.isEmpty() -> CircularProgressIndicator()
            s.error != null -> {
                Text(s.error, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text(stringResource(R.string.progress_retry)) }
            }
            s.items.isEmpty() -> Text(stringResource(R.string.goal_empty), modifier = Modifier.testTag(GoalsTags.EMPTY))
            else -> {
                s.active.forEach { GoalCard(it, s.units, s.saving, onStatus, onDelete) }
                if (s.past.isNotEmpty()) {
                    Text(stringResource(R.string.goal_past), style = MaterialTheme.typography.titleSmall)
                    s.past.forEach { GoalCard(it, s.units, s.saving, onStatus, onDelete) }
                }
            }
        }
    }
}

@Composable
fun goalTitle(type: GoalType): String = stringResource(
    when (type) {
        GoalType.LOSE_WEIGHT -> R.string.goal_type_lose
        GoalType.GAIN_MUSCLE -> R.string.goal_type_gain
        GoalType.MAINTAIN -> R.string.goal_type_maintain
        GoalType.IMPROVE_ENDURANCE -> R.string.goal_type_endurance
        GoalType.WORKOUTS_PER_WEEK -> R.string.goal_type_workouts
        GoalType.UNKNOWN -> R.string.goal_type_other
    },
)

private fun weight(kg: Double, units: UnitSystem) =
    "${ProgressFormat.number(UnitConversion.displayWeight(kg, units)!!)} ${ProgressFormat.unitLabel(MeasureDimension.MASS, units)}"

/** One line describing progress, or null when there is nothing measurable. */
@Composable
fun goalProgressText(p: GoalProgress, units: UnitSystem): String? {
    val g = p.goal
    val target = g.targetValue
    val current = p.current
    return when {
        g.type == GoalType.WORKOUTS_PER_WEEK && target != null ->
            pluralStringResource(R.plurals.goal_workouts_progress, target.toInt(), p.current?.toInt() ?: 0, target.toInt())
        g.type == GoalType.MAINTAIN && target != null && current != null ->
            stringResource(if (p.onTrack == true) R.string.goal_maintain_on_track else R.string.goal_maintain_off, weight(current, units), weight(target, units))
        g.type.isWeight && target != null && current != null -> stringResource(R.string.goal_weight_progress, weight(current, units), weight(target, units))
        g.type.isWeight && target != null -> stringResource(R.string.goal_weight_no_data, weight(target, units))
        else -> null
    }
}

@Composable
private fun GoalCard(p: GoalProgress, units: UnitSystem, saving: Boolean, onStatus: (String, GoalStatus) -> Unit, onDelete: (String) -> Unit) {
    val g = p.goal
    Card(Modifier.fillMaxWidth().testTag(GoalsTags.card(g.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(goalTitle(g.type), style = MaterialTheme.typography.titleMedium)
            when (g.status) {
                GoalStatus.ACHIEVED -> Text(stringResource(R.string.goal_status_achieved))
                GoalStatus.ABANDONED -> Text(stringResource(R.string.goal_status_abandoned))
                else -> Unit
            }
            goalProgressText(p, units)?.let { Text(it) }
            p.fraction?.let { f ->
                LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth().testTag(GoalsTags.bar(g.id)))
                if (p.milestone > 0) Text(stringResource(R.string.goal_milestone, p.milestone), color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag(GoalsTags.milestone(g.id)))
            }
            g.targetDate?.let { Text(stringResource(R.string.goal_by_date, ProgressFormat.date(it)), style = MaterialTheme.typography.bodySmall) }
            FlowRowActions(g.id, g.status, saving, onStatus, onDelete)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowActions(id: String, status: GoalStatus, saving: Boolean, onStatus: (String, GoalStatus) -> Unit, onDelete: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (status == GoalStatus.ACTIVE) {
            TextButton(onClick = { onStatus(id, GoalStatus.ACHIEVED) }, enabled = !saving, modifier = Modifier.testTag(GoalsTags.achieved(id))) { Text(stringResource(R.string.goal_mark_achieved)) }
            TextButton(onClick = { onStatus(id, GoalStatus.ABANDONED) }, enabled = !saving, modifier = Modifier.testTag(GoalsTags.abandon(id))) { Text(stringResource(R.string.goal_abandon)) }
        } else {
            TextButton(onClick = { onStatus(id, GoalStatus.ACTIVE) }, enabled = !saving, modifier = Modifier.testTag(GoalsTags.reactivate(id))) { Text(stringResource(R.string.goal_reactivate)) }
        }
        TextButton(onClick = { onDelete(id) }, enabled = !saving, modifier = Modifier.testTag(GoalsTags.delete(id))) {
            Text(stringResource(R.string.goal_delete), color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalFormDialog(f: GoalForm, units: UnitSystem, saving: Boolean, onForm: ((GoalForm) -> GoalForm) -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    val unit = ProgressFormat.unitLabel(MeasureDimension.MASS, units)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.goal_new)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GoalType.creatable.forEach { t ->
                        FilterChip(selected = f.type == t, onClick = { onForm { it.copy(type = t) } }, label = { Text(goalTitle(t)) }, modifier = Modifier.testTag(GoalsTags.type(t.wire)))
                    }
                }
                if (f.type.isWeight || f.type == GoalType.WORKOUTS_PER_WEEK) {
                    OutlinedTextField(
                        f.target, { v -> onForm { it.copy(target = v) } },
                        label = { Text(if (f.type == GoalType.WORKOUTS_PER_WEEK) stringResource(R.string.goal_target_workouts) else stringResource(R.string.goal_target_weight, unit)) },
                        isError = GoalFormError.TARGET in f.errors,
                        supportingText = { if (GoalFormError.TARGET in f.errors) Text(stringResource(if (f.type == GoalType.WORKOUTS_PER_WEEK) R.string.goal_err_workouts else R.string.goal_err_weight)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.testTag(GoalsTags.TARGET),
                    )
                }
                if (f.type == GoalType.LOSE_WEIGHT || f.type == GoalType.GAIN_MUSCLE) {
                    OutlinedTextField(
                        f.start, { v -> onForm { it.copy(start = v) } },
                        label = { Text(stringResource(R.string.goal_start_weight, unit)) },
                        isError = GoalFormError.START in f.errors,
                        supportingText = { if (GoalFormError.START in f.errors) Text(stringResource(R.string.goal_err_weight)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.testTag(GoalsTags.START),
                    )
                }
                OutlinedTextField(
                    f.date, { v -> onForm { it.copy(date = v) } },
                    label = { Text(stringResource(R.string.goal_target_date)) },
                    isError = GoalFormError.DATE in f.errors,
                    supportingText = { Text(stringResource(if (GoalFormError.DATE in f.errors) R.string.goal_err_date else R.string.goal_date_hint)) },
                    modifier = Modifier.testTag(GoalsTags.DATE),
                )
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = !saving, modifier = Modifier.testTag(GoalsTags.SAVE)) { Text(stringResource(R.string.progress_save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.goal_cancel)) } },
    )
}
