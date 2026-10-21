package com.vipin.fitness.navigation

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vipin.fitness.feature.home.DefaultPlanDayContent
import com.vipin.fitness.feature.home.HomeState
import com.vipin.fitness.feature.home.PlanDay
import com.vipin.fitness.feature.home.Slot
import com.vipin.fitness.feature.workout.TodayWorkoutAction
import com.vipin.fitness.feature.workout.TodayWorkoutViewModel
import com.vipin.fitness.feature.workout.UnfinishedWorkoutSlot

/**
 * T13 on Home, through Home's own hooks only: the plan card keeps its default content and gains
 * Start / Resume ([planDayContent]); an extra slot offers Resume on days without a planned workout.
 */
@Composable
fun WorkoutPlanDayContent(d: PlanDay, onOpenSession: () -> Unit, viewModel: TodayWorkoutViewModel = hiltViewModel()) {
    DefaultPlanDayContent(d)
    TodayWorkoutAction(d.plan, d.workout, onOpenSession, viewModel)
}

fun LazyListScope.workoutHomeSlots(s: HomeState, onOpenSession: () -> Unit, viewModel: @Composable () -> TodayWorkoutViewModel = { hiltViewModel() }) {
    val todayHasWorkout = (s.plan as? Slot.Ready)?.value?.let { it.plan != null && it.workout != null } == true
    item(key = "workout_unfinished") { UnfinishedWorkoutSlot(todayHasWorkout, onOpenSession, viewModel()) }
}
