package com.vipin.fitness.feature.workout

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import com.vipin.fitness.core.data.workout.PlannedExercise
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** "Monday" etc. in the device language; used as the default name of a custom day. */
fun DayOfWeek.fullName(locale: Locale = Locale.getDefault()): String = getDisplayName(TextStyle.FULL, locale)
fun DayOfWeek.shortName(locale: Locale = Locale.getDefault()): String = getDisplayName(TextStyle.SHORT, locale)

/** "3 × 8–12", "4 × 10", "3 × 60 s", "3 sets". */
@Composable
fun PlannedExercise.prescription(): String {
    val lo = repsMin
    val hi = repsMax
    return when {
        durationSeconds != null -> "$sets × ${durationSeconds} s"
        lo != null && hi != null && lo != hi -> "$sets × $lo–$hi"
        lo != null || hi != null -> "$sets × ${lo ?: hi}"
        else -> pluralStringResource(R.plurals.n_sets, sets, sets)
    }
}
