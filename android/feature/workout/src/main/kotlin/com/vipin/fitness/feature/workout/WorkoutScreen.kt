package com.vipin.fitness.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.designsystem.PlaceholderScreen

@Composable
fun WorkoutScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.workout_title),
        body = stringResource(R.string.workout_coming_soon),
        modifier = modifier,
    )
}
