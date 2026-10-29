package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.progression.ApplyOutcome
import com.vipin.fitness.core.data.progression.ProgressionData
import com.vipin.fitness.core.data.progression.ProgressionRepository
import com.vipin.fitness.core.data.progression.buildProgressionUpdate
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.model.WorkoutPlanUpdateDto
import com.vipin.fitness.core.data.progression.Prescription
import com.vipin.fitness.core.data.progression.ProgressionAction
import com.vipin.fitness.core.data.progression.ProgressionInputs
import com.vipin.fitness.core.data.progression.ProgressionReason
import com.vipin.fitness.core.data.progression.ProgressionStep
import com.vipin.fitness.core.data.progression.ProgressionSuggestion
import com.vipin.fitness.core.data.progression.matchSuggestions
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.model.PlannedExerciseDto
import com.vipin.fitness.core.network.model.PlannedWorkoutDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Scripted progression repository. [loads] / [applies] are consumed in order (the last one
 * repeats); [loadGate] / [applyGate] hold a call until completed. Records every apply's key and body.
 */
class FakeProgressionRepository : ProgressionRepository {
    override val userGeneration = MutableStateFlow(0)
    val loads = ArrayDeque<ApiResult<ProgressionData>>()
    val applies = ArrayDeque<ApplyOutcome>()
    var loadGate: CompletableDeferred<Unit>? = null
    var applyGate: CompletableDeferred<Unit>? = null
    val loadCalls = mutableListOf<String>()
    data class ApplyCall(val planId: String, val baseVersion: Int, val acceptedKeys: Set<String>, val key: String, val body: WorkoutPlanUpdateDto?)
    val applyCalls = mutableListOf<ApplyCall>()

    override suspend fun load(planId: String): ApiResult<ProgressionData> {
        loadCalls += planId
        val r = (if (loads.size > 1) loads.removeFirst() else loads.firstOrNull()) ?: ApiResult.Failure(ApiError.Network)
        loadGate?.await()
        return r
    }

    override suspend fun apply(data: ProgressionData, acceptedKeys: Set<String>, idempotencyKey: String): ApplyOutcome {
        applyCalls += ApplyCall(data.planId, data.planVersion, acceptedKeys, idempotencyKey, buildProgressionUpdate(data, acceptedKeys))
        val r = (if (applies.size > 1) applies.removeFirst() else applies.firstOrNull()) ?: ApplyOutcome.Applied(data.planVersion + 1)
        applyGate?.await()
        return r
    }
}

const val PROGRESSION_PLAN_ID = "3c7e9a1b-5d2f-4a6c-8e0b-1f3d5a7c9e24"
const val PROGRESSION_WORKOUT_ID = "0b9d3c2e-1f4a-4c55-9a7e-2d6f8e1a7b01"
const val PROGRESSION_BENCH = "5f1c2a9e-7b3d-4e8a-9c21-0a4b6d8e2f13"
const val PROGRESSION_PLANK = "8a2e4c6f-1b3d-4f5a-8c7e-9d0b2a4c6e81"
const val PROGRESSION_ROW = "e4b6d8f0-2a4c-4e6a-9b1d-3f5a7c9e0b42"

/** The spec's `mixed` example (increase 40→41 kg, COOLDOWN hold, deload 30→27) on a matching plan at [version]. */
fun testProgressionData(version: Int = 4, workoutId: String = PROGRESSION_WORKOUT_ID): ProgressionData {
    val plan = WorkoutPlanDto(
        id = PROGRESSION_PLAN_ID, version = version, name = "Push focus", weeks = 8,
        workouts = listOf(
            PlannedWorkoutDto(
                workoutId, 1, "Push",
                listOf(
                    PlannedExerciseDto(PROGRESSION_BENCH, 0, 3, 8, 10, targetWeightKg = 40.0, restSeconds = 90),
                    PlannedExerciseDto(PROGRESSION_PLANK, 1, 3, durationSeconds = 65, restSeconds = 60),
                    PlannedExerciseDto(PROGRESSION_ROW, 2, 3, 8, 10, targetWeightKg = 30.0, restSeconds = 90),
                ),
            ),
        ),
        isActive = true, source = "rule_based", createdAt = "2026-10-20T09:00:00+05:30",
    )
    val s = listOf(
        ProgressionSuggestion(
            workoutId, PROGRESSION_BENCH, 0, TrackingType.REPS_WEIGHT, ProgressionAction.INCREASE, ProgressionReason.ALL_SETS_COMPLETED,
            Prescription(3, 8, 10, 40.0), Prescription(3, 8, 10, 41.0),
            inputs = ProgressionInputs(3, 3, 3, 3, false, null, ProgressionStep("percent_increase", 2.5, 10.0, false, false)),
        ),
        ProgressionSuggestion(
            workoutId, PROGRESSION_PLANK, 1, TrackingType.DURATION, ProgressionAction.HOLD, ProgressionReason.COOLDOWN,
            Prescription(3, durationSeconds = 65), null, cooldownUntil = Instant.parse("2026-11-01T13:00:00Z"),
            inputs = ProgressionInputs(3, 3, 3, 3, true, Instant.parse("2026-11-01T13:00:00Z"), null),
        ),
        ProgressionSuggestion(
            workoutId, PROGRESSION_ROW, 2, TrackingType.REPS_WEIGHT, ProgressionAction.DELOAD, ProgressionReason.REPEATED_MISSES,
            Prescription(3, 8, 10, 30.0), Prescription(3, 8, 10, 27.0),
            inputs = ProgressionInputs(3, 2, 0, 4, false, null, ProgressionStep("deload", -10.0, null, false, false)),
        ),
    )
    return matchSuggestions(plan, s)
}
