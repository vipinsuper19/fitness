package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vipin.fitness.core.data.workout.PlannedWorkout

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun LoadingBox() = Centered { CircularProgressIndicator() }

@Composable
internal fun ErrorBox(message: String, onRetry: () -> Unit) = Centered {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.retry)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChipRow(title: String?, options: List<T>, isSelected: (T) -> Boolean, label: @Composable (T) -> String, onClick: (T) -> Unit) {
    Column {
        title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = isSelected(o), onClick = { onClick(o) }, label = { Text(label(o)) }) }
        }
    }
}

/** One day's exercises with names resolved from [names]. */
@Composable
internal fun WorkoutDayList(workout: PlannedWorkout, names: Map<String, String>, onExercise: ((String) -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(workout.name, style = MaterialTheme.typography.titleMedium)
        workout.exercises.forEach { e ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val name = names[e.exerciseId] ?: stringResource(R.string.exercise_unknown)
                if (onExercise != null) {
                    TextButton(onClick = { onExercise(e.exerciseId) }) { Text(name) }
                } else {
                    Text(name, modifier = Modifier.padding(vertical = 12.dp))
                }
                Text(e.prescription(), modifier = Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
