package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.GoalType
import java.time.DayOfWeek
import kotlin.math.roundToInt

@Composable
fun NewPlanRoute(
    onPreview: () -> Unit,
    onAddExercise: (DayOfWeek) -> Unit,
    viewModel: NewPlanViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val custom by viewModel.custom.collectAsStateWithLifecycle()
    LaunchedEffect(s.openPreview) {
        if (s.openPreview) {
            viewModel.onNavigatedToPreview()
            onPreview()
        }
    }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.new_plan_title), style = MaterialTheme.typography.headlineSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            NewPlanMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = s.mode == m,
                    onClick = { viewModel.setMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, NewPlanMode.entries.size),
                ) { Text(stringResource(if (m == NewPlanMode.GENERATE) R.string.mode_generate else R.string.mode_custom)) }
            }
        }
        if (s.mode == NewPlanMode.GENERATE) GenerateForm(s, viewModel) else CustomForm(custom, s.customErrors, viewModel, onAddExercise)
    }
}

@Composable
private fun GenerateForm(s: NewPlanState, vm: NewPlanViewModel) {
    val f = s.form
    ChipRow(stringResource(R.string.goal), GoalType.entries, { it == f.goal }, { it.label() }) { g -> vm.onForm { it.copy(goal = g) } }
    ChipRow(stringResource(R.string.experience), ExperienceLevel.entries, { it == f.experience }, { it.label() }) { x -> vm.onForm { it.copy(experience = x) } }
    Text(stringResource(R.string.days_per_week, f.daysPerWeek))
    Slider(value = f.daysPerWeek.toFloat(), onValueChange = { v -> vm.onForm { it.copy(daysPerWeek = v.roundToInt()) } }, valueRange = 1f..7f, steps = 5)
    Text(stringResource(R.string.session_minutes, f.sessionMinutes))
    Slider(
        value = f.sessionMinutes.toFloat(),
        onValueChange = { v -> vm.onForm { it.copy(sessionMinutes = (v / 15).roundToInt() * 15) } },
        valueRange = 15f..180f,
        steps = 10,
    )
    Text(pluralStringResource(R.plurals.weeks, f.weeks, f.weeks))
    Slider(value = f.weeks.toFloat(), onValueChange = { v -> vm.onForm { it.copy(weeks = v.roundToInt()) } }, valueRange = 1f..16f, steps = 14)
    ChipRow(
        stringResource(R.string.equipment_available),
        Equipment.entries.filter { it != Equipment.NONE },
        { it in f.equipment },
        { it.label() },
        vm::toggleEquipment,
    )
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = vm::generate, enabled = !s.generating, modifier = Modifier.fillMaxWidth().testTag("generate")) {
        Text(stringResource(R.string.generate))
    }
}

@Composable
internal fun CustomForm(plan: CustomPlan, errors: Set<CustomPlanError>, vm: NewPlanViewModel, onAddExercise: (DayOfWeek) -> Unit) {
    OutlinedTextField(
        value = plan.name,
        onValueChange = vm::setCustomName,
        label = { Text(stringResource(R.string.plan_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(pluralStringResource(R.plurals.weeks, plan.weeks, plan.weeks))
    Slider(value = plan.weeks.toFloat(), onValueChange = { vm.setCustomWeeks(it.roundToInt()) }, valueRange = 1f..16f, steps = 14)
    ChipRow(stringResource(R.string.training_days), DayOfWeek.entries, { it in plan.days }, { it.shortName() }, vm::toggleDay)
    plan.days.forEach { (day, d) ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { vm.renameDay(day, it) },
                    label = { Text(stringResource(R.string.day_name, day.fullName())) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                d.exercises.forEachIndexed { i, e -> CustomExerciseRow(day, i, e, d.exercises.size, vm) }
                OutlinedButton(onClick = { onAddExercise(day) }, enabled = d.exercises.size < PlanBuilderStore.MAX_EXERCISES) {
                    Text(stringResource(R.string.add_exercise))
                }
            }
        }
    }
    errors.forEach { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
    // Out-of-range numbers are shown inline per field; the server would reject them, so no Preview/Save.
    Button(onClick = vm::previewCustom, enabled = !plan.hasFieldErrors(), modifier = Modifier.fillMaxWidth().testTag(PlanBuilderTags.PREVIEW)) {
        Text(stringResource(R.string.preview))
    }
}

@Composable
private fun CustomExerciseRow(day: DayOfWeek, index: Int, e: CustomExercise, count: Int, vm: NewPlanViewModel) {
    Column {
        Text(e.exercise.name, style = MaterialTheme.typography.titleSmall)
        val errs = e.fieldErrors()
        fun tag(f: ExerciseField) = PlanBuilderTags.field(day, index, f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 0 sets is shown as empty (cleared field), with the range error.
            NumberField(R.string.sets, e.sets.takeIf { it > 0 }, errs[ExerciseField.SETS], tag(ExerciseField.SETS), Modifier.weight(1f)) { v ->
                vm.updateExercise(day, index) { it.copy(sets = v ?: 0) }
            }
            if (e.exercise.trackingType.usesDuration) {
                NumberField(R.string.duration_s, e.durationSeconds, errs[ExerciseField.DURATION], tag(ExerciseField.DURATION), Modifier.weight(2f)) { v ->
                    vm.updateExercise(day, index) { it.copy(durationSeconds = v) }
                }
            } else {
                NumberField(R.string.reps_min, e.repsMin, errs[ExerciseField.REPS_MIN], tag(ExerciseField.REPS_MIN), Modifier.weight(1f)) { v ->
                    vm.updateExercise(day, index) { it.copy(repsMin = v) }
                }
                NumberField(R.string.reps_max, e.repsMax, errs[ExerciseField.REPS_MAX], tag(ExerciseField.REPS_MAX), Modifier.weight(1f)) { v ->
                    vm.updateExercise(day, index) { it.copy(repsMax = v) }
                }
            }
        }
        Row {
            TextButton(onClick = { vm.moveExercise(day, index, -1) }, enabled = index > 0) { Text(stringResource(R.string.move_up)) }
            TextButton(onClick = { vm.moveExercise(day, index, 1) }, enabled = index < count - 1) { Text(stringResource(R.string.move_down)) }
            TextButton(onClick = { vm.removeExercise(day, index) }) { Text(stringResource(R.string.remove)) }
        }
    }
}

@Composable
private fun NumberField(label: Int, value: Int?, error: ExerciseFieldError?, tag: String, modifier: Modifier = Modifier, onChange: (Int?) -> Unit) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { t -> onChange(t.filter(Char::isDigit).take(4).toIntOrNull()) },
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.message()), Modifier.testTag(PlanBuilderTags.error(tag))) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(tag),
    )
}

object PlanBuilderTags {
    const val PREVIEW = "preview_custom"
    fun field(day: DayOfWeek, index: Int, f: ExerciseField) = "custom_${day.name.lowercase()}_${index}_${f.name.lowercase()}"
    fun error(fieldTag: String) = "${fieldTag}_error"
}
