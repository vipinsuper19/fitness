package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PlanPreviewRoute(
    onActivated: () -> Unit,
    onOpenExercise: (String) -> Unit,
    viewModel: PlanPreviewViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.done) { if (s.done) onActivated() }
    val draft = s.draft
    if (draft == null) {
        Centered { Text(stringResource(R.string.no_preview)) }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.labelLarge)
        Text(draft.name, style = MaterialTheme.typography.headlineSmall)
        Text(pluralStringResource(R.plurals.plan_weeks, draft.weeks, draft.weeks))
        draft.rationale?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        draft.workouts.forEach { w ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(w.day.fullName(), style = MaterialTheme.typography.labelLarge)
                    WorkoutDayList(w, s.exerciseNames, onOpenExercise)
                }
            }
        }
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = viewModel::saveAndActivate, enabled = !s.saving, modifier = Modifier.fillMaxWidth().testTag("save_activate")) {
            Text(stringResource(R.string.save_activate))
        }
    }
}
