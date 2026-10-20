package com.vipin.fitness.feature.nutrition

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.core.designsystem.PlaceholderScreen

@Composable
fun NutritionScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nutrition_title),
        body = stringResource(R.string.nutrition_coming_soon),
        modifier = modifier,
    )
}
