package com.vipin.fitness.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.vipin.fitness.feature.home.HomeScreen
import com.vipin.fitness.feature.nutrition.NutritionScreen
import com.vipin.fitness.feature.progress.ProgressScreen
import com.vipin.fitness.feature.workout.WorkoutScreen

/** App shell: Material 3 scaffold with a bottom navigation bar over the four top-level tabs. */
@Composable
fun FitnessApp(modifier: Modifier = Modifier) {
    var current by rememberSaveable { mutableStateOf(TopLevelDestination.START) }
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { dest ->
                    val label = stringResource(dest.labelRes)
                    NavigationBarItem(
                        selected = dest == current,
                        modifier = Modifier.testTag("tab_${dest.name}"),
                        onClick = { current = dest },
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (current) {
            TopLevelDestination.HOME -> HomeScreen(content)
            TopLevelDestination.WORKOUT -> WorkoutScreen(content)
            TopLevelDestination.NUTRITION -> NutritionScreen(content)
            TopLevelDestination.PROGRESS -> ProgressScreen(content)
        }
    }
}
