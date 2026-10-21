package com.vipin.fitness.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
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
import com.vipin.fitness.R
import com.vipin.fitness.feature.home.HomeScreen
import com.vipin.fitness.feature.nutrition.NutritionScreen
import com.vipin.fitness.feature.progress.ProgressScreen
import com.vipin.fitness.feature.workout.WorkoutDestination
import com.vipin.fitness.feature.workout.WorkoutHomeRoute

/** App shell: Material 3 scaffold with a bottom navigation bar over the four top-level tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FitnessApp(
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onWorkoutNavigate: (WorkoutDestination) -> Unit = {},
    /** Slot so UI tests can render the shell without Hilt. Defaults to the real screen for each tab. */
    tabContent: @Composable (TopLevelDestination, Modifier) -> Unit = { dest, m -> TabContent(dest, m, onWorkoutNavigate) },
) {
    var current by rememberSaveable { mutableStateOf(TopLevelDestination.START) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenProfile, modifier = Modifier.testTag("open_profile")) {
                        Icon(Icons.Filled.AccountCircle, contentDescription = stringResource(R.string.open_profile))
                    }
                },
            )
        },
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
        tabContent(current, Modifier.padding(padding))
    }
}

/** The real screen for each tab. Tests can pass their own [FitnessApp] tabContent to stay free of Hilt. */
@Composable
private fun TabContent(dest: TopLevelDestination, modifier: Modifier, onWorkoutNavigate: (WorkoutDestination) -> Unit) = when (dest) {
    TopLevelDestination.HOME -> HomeScreen(modifier)
    TopLevelDestination.WORKOUT -> WorkoutHomeRoute(onWorkoutNavigate, modifier)
    TopLevelDestination.NUTRITION -> NutritionScreen(modifier)
    TopLevelDestination.PROGRESS -> ProgressScreen(modifier)
}
