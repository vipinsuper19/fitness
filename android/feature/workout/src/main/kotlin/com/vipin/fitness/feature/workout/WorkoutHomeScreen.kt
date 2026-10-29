package com.vipin.fitness.feature.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.DayOfWeek

/** Where the Workout tab wants to go; the app maps these to Navigation 3 routes. */
sealed interface WorkoutDestination {
    data object NewPlan : WorkoutDestination
    data object Library : WorkoutDestination
    data class ExerciseDetail(val id: String) : WorkoutDestination
    data object ActiveSession : WorkoutDestination
    data object History : WorkoutDestination
    data object Plans : WorkoutDestination
}

@Composable
fun WorkoutHomeRoute(
    onNavigate: (WorkoutDestination) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutHomeViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    var resumedOnce by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        // The first resume is covered by the ViewModel's own initial load.
        if (resumedOnce) viewModel.onResume() else resumedOnce = true
        onPauseOrDispose { }
    }
    LaunchedEffect(s.openSession) {
        if (s.openSession) {
            viewModel.onSessionOpened()
            onNavigate(WorkoutDestination.ActiveSession)
        }
    }
    Column(modifier.fillMaxSize()) {
        when {
            s.loading -> LoadingBox()
            s.error != null -> ErrorBox(s.error!!, viewModel::refresh)
            else -> Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.flagged.forEach { f ->
                    Card(Modifier.fillMaxWidth().testTag("flagged_${f.localId}")) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.flagged_title, f.name), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.flagged_body), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.dismissFlag(f.localId) }) { Text(stringResource(R.string.dismiss)) }
                        }
                    }
                }
                s.unfinishedSession?.let { u ->
                    Card(onClick = viewModel::startWorkout, modifier = Modifier.fillMaxWidth().testTag("resume_session")) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.session_unfinished), style = MaterialTheme.typography.labelLarge)
                            Text(u.name, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.resume_workout), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                val plan = s.plan
                if (plan == null) {
                    Text(stringResource(R.string.no_plan_title), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.no_plan_body))
                } else {
                    Text(stringResource(R.string.my_plan), style = MaterialTheme.typography.labelLarge)
                    Text(plan.name, style = MaterialTheme.typography.headlineSmall)
                    Text(pluralStringResource(R.plurals.plan_weeks, plan.weeks, plan.weeks), style = MaterialTheme.typography.bodyMedium)
                    if (s.stale) Text(stringResource(R.string.stale_plan), color = MaterialTheme.colorScheme.error)
                    WeekStrip(s.week, s.selectedDay, viewModel::select)
                    val sel = s.selected
                    if (sel?.workout != null) {
                        WorkoutDayList(sel.workout, s.exerciseNames) { onNavigate(WorkoutDestination.ExerciseDetail(it)) }
                        if (s.unfinishedSession == null) {
                            Button(
                                onClick = viewModel::startWorkout,
                                enabled = !s.starting,
                                modifier = Modifier.fillMaxWidth().testTag("start_workout"),
                            ) { Text(stringResource(R.string.start_workout)) }
                        }
                    } else {
                        Text(stringResource(R.string.rest_day_body))
                    }
                    ProgressionSuggestionsRoute(plan.id)
                }
                Button(onClick = { onNavigate(WorkoutDestination.NewPlan) }, modifier = Modifier.fillMaxWidth().testTag("start_new_plan")) {
                    Text(stringResource(R.string.start_new_plan))
                }
                OutlinedButton(onClick = { onNavigate(WorkoutDestination.Library) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.exercise_library))
                }
                OutlinedButton(onClick = { onNavigate(WorkoutDestination.History) }, modifier = Modifier.fillMaxWidth().testTag("open_history")) {
                    Text(stringResource(R.string.workout_history))
                }
                OutlinedButton(onClick = { onNavigate(WorkoutDestination.Plans) }, modifier = Modifier.fillMaxWidth().testTag("open_plans")) {
                    Text(stringResource(R.string.manage_plans))
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(week: List<DayCell>, selected: DayOfWeek, onSelect: (DayOfWeek) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEach { c ->
            val isSel = c.day == selected
            Card(
                onClick = { onSelect(c.day) },
                modifier = Modifier.weight(1f).testTag("day_${c.day.name}"),
                colors = if (isSel) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                border = if (c.isToday) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.day.shortName(), style = MaterialTheme.typography.labelSmall)
                    Text(c.date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        c.workout?.name ?: stringResource(R.string.rest_day),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
