package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.WorkoutPlan

/**
 * Start / resume button for today's workout, for hosts outside the Workout tab (Home's plan
 * card). Shows Resume whenever a session is unfinished, Start when today has a workout, else nothing.
 */
@Composable
fun TodayWorkoutAction(
    plan: WorkoutPlan?,
    workout: PlannedWorkout?,
    onOpenSession: () -> Unit,
    viewModel: TodayWorkoutViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(s.openSession) {
        if (s.openSession) {
            viewModel.onSessionOpened()
            onOpenSession()
        }
    }
    TodayWorkoutButton(s, hasWorkout = plan != null && workout != null) { viewModel.startOrResume(plan, workout) }
}

@Composable
internal fun TodayWorkoutButton(s: TodayWorkoutState, hasWorkout: Boolean, onClick: () -> Unit) {
    when {
        s.unfinished != null -> Button(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("home_resume_workout")) {
            Text(stringResource(R.string.resume_workout))
        }
        hasWorkout -> Button(onClick = onClick, enabled = !s.starting, modifier = Modifier.fillMaxWidth().testTag("home_start_workout")) {
            Text(stringResource(R.string.start_workout))
        }
    }
}

/**
 * A Home slot shown only while a session is unfinished on a day without a workout planned
 * (otherwise the plan card's Resume covers it). Renders nothing when there's nothing to resume.
 */
@Composable
fun UnfinishedWorkoutSlot(todayHasWorkout: Boolean, onOpenSession: () -> Unit, viewModel: TodayWorkoutViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val u = s.unfinished ?: return
    if (todayHasWorkout) return
    Card(onClick = onOpenSession, modifier = Modifier.fillMaxWidth().testTag("home_unfinished_workout")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.session_unfinished), style = MaterialTheme.typography.labelLarge)
            Text(u.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.resume_workout), color = MaterialTheme.colorScheme.primary)
        }
    }
}
