package com.vipin.fitness.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.designsystem.PlaceholderScreen

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.home_title),
        body = stringResource(R.string.home_coming_soon),
        modifier = modifier,
    )
}
