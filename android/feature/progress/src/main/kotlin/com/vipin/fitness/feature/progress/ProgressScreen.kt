package com.vipin.fitness.feature.progress

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.designsystem.PlaceholderScreen

@Composable
fun ProgressScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.progress_title),
        body = stringResource(R.string.progress_coming_soon),
        modifier = modifier,
    )
}
