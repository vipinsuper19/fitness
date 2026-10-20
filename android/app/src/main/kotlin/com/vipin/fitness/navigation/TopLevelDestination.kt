package com.vipin.fitness.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector
import com.vipin.fitness.R

/** The four bottom-bar tabs, in display order. Home is the start destination. */
enum class TopLevelDestination(@StringRes val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.tab_home, Icons.Filled.Home),
    WORKOUT(R.string.tab_workout, Icons.Filled.PlayArrow),
    NUTRITION(R.string.tab_nutrition, Icons.Filled.Favorite),
    PROGRESS(R.string.tab_progress, Icons.Filled.DateRange),
    ;

    companion object {
        val START = HOME
    }
}
