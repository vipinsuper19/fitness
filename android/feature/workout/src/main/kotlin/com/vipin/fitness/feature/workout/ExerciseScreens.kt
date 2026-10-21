package com.vipin.fitness.feature.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.workout.Equipment
import com.vipin.fitness.core.data.workout.MuscleGroup
import java.time.DayOfWeek

/** Browse the catalog. With [pickDay] set, tapping adds to that day of the custom plan and returns. */
@Composable
fun ExerciseLibraryRoute(
    pickDay: DayOfWeek?,
    replacePosition: Int? = null,
    onOpen: (String) -> Unit,
    onPicked: () -> Unit,
    viewModel: ExerciseLibraryViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(pickDay, replacePosition) {
        viewModel.setPickDay(pickDay)
        viewModel.setReplacePosition(replacePosition)
    }
    val picking = pickDay != null || replacePosition != null
    LaunchedEffect(s.picked) { if (s.picked) onPicked() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Text(
                    when {
                        replacePosition != null -> stringResource(R.string.replace_with)
                        pickDay != null -> stringResource(R.string.pick_for, pickDay.fullName())
                        else -> stringResource(R.string.exercise_library)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = s.filter.query,
                    onValueChange = viewModel::onQuery,
                    label = { Text(stringResource(R.string.search_exercises)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipRow(stringResource(R.string.muscle), MuscleGroup.entries, { it == s.filter.muscle }, { it.label() }, viewModel::onMuscle)
                ChipRow(stringResource(R.string.equipment), Equipment.entries, { it == s.filter.equipment }, { it.label() }, viewModel::onEquipment)
            }
        }
        when {
            s.loading -> item { LoadingBox() }
            s.error != null && s.items.isEmpty() -> item { ErrorBox(s.error!!, viewModel::retry) }
            s.items.isEmpty() -> item { Text(stringResource(R.string.no_exercises), modifier = Modifier.padding(vertical = 24.dp)) }
            else -> {
                items(s.items, key = { it.id }) { e ->
                    ListItem(
                        headlineContent = { Text(e.name) },
                        supportingContent = {
                            Text(listOfNotNull(e.primaryMuscle?.label(), e.equipment.map { it.label() }.joinToString().ifEmpty { null }).joinToString(" · "))
                        },
                        modifier = Modifier.clickable { if (picking) viewModel.pick(e) else onOpen(e.id) }.testTag("exercise_${e.id}"),
                    )
                    HorizontalDivider()
                }
                item {
                    when {
                        s.loadingMore -> CircularProgressIndicator(Modifier.padding(16.dp))
                        s.error != null -> ErrorBox(s.error!!, viewModel::loadMore)
                        s.nextCursor != null -> OutlinedButton(onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                            Text(stringResource(R.string.load_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExerciseDetailRoute(
    exerciseId: String,
    viewModel: ExerciseDetailViewModel = hiltViewModel<ExerciseDetailViewModel, ExerciseDetailViewModel.Factory>(
        key = exerciseId,
        creationCallback = { it.create(exerciseId) },
    ),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    when {
        s.loading -> LoadingBox()
        s.error != null -> ErrorBox(s.error!!, viewModel::load)
        else -> {
            val e = s.exercise ?: return
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(e.name, style = MaterialTheme.typography.headlineSmall)
                e.nameHi?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                e.primaryMuscle?.let { Text(stringResource(R.string.primary_muscle, it.label())) }
                if (e.secondaryMuscles.isNotEmpty()) Text(stringResource(R.string.secondary_muscles, e.secondaryMuscles.map { it.label() }.joinToString()))
                if (e.equipment.isNotEmpty()) Text(stringResource(R.string.equipment_list, e.equipment.map { it.label() }.joinToString()))
                e.difficulty?.let { Text(stringResource(R.string.difficulty, it.label())) }
                if (e.instructions.isNotEmpty()) {
                    Text(stringResource(R.string.instructions), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    e.instructions.forEachIndexed { i, step -> Text("${i + 1}. $step") }
                }
                e.videoUrl?.takeIf { it.startsWith("https://") }?.let { url ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.watch_video)) }
                }
            }
        }
    }
}
