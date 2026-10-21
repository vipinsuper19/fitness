package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.ExerciseFilter
import com.vipin.fitness.core.data.workout.ExercisePage
import com.vipin.fitness.core.data.workout.PlanDraft
import com.vipin.fitness.core.data.workout.PlanGenerateRequest
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

fun testExercise(id: String, name: String = "Exercise $id", tracking: TrackingType = TrackingType.REPS) = Exercise(
    id = id, name = name, nameHi = null, primaryMuscle = null, secondaryMuscles = emptyList(), equipment = emptyList(),
    trackingType = tracking, difficulty = null, instructions = emptyList(), videoUrl = null,
)

fun PlanDraft.asPlan(id: String, active: Boolean = false) =
    WorkoutPlan(id, 1, name, weeks, rationale, workouts, active, source)

val networkFailure = ApiResult.Failure(ApiError.Network)

class FakeWorkoutRepository : WorkoutRepository {
    override val activePlan = MutableStateFlow<ActivePlan?>(null)
    var refreshResult: () -> ApiResult<ActivePlan> = { ApiResult.Success(ActivePlan.None) }
    /** Pages by cursor (null = first page). */
    var pages: (ExerciseFilter, String?) -> ApiResult<ExercisePage> = { _, _ -> ApiResult.Success(ExercisePage(emptyList(), null)) }
    val exerciseCalls = mutableListOf<Pair<ExerciseFilter, String?>>()
    val catalog = mutableMapOf<String, Exercise>()
    var generateResult: (PlanGenerateRequest) -> ApiResult<PlanDraft> = { networkFailure }
    val generateCalls = mutableListOf<PlanGenerateRequest>()
    var createResult: (PlanDraft) -> ApiResult<WorkoutPlan> = { ApiResult.Success(it.asPlan("p1")) }
    val createKeys = mutableListOf<String>()
    var activateResult: (String) -> ApiResult<WorkoutPlan>? = { null }
    val activateCalls = mutableListOf<Pair<String, String>>()
    private val created = mutableMapOf<String, WorkoutPlan>()
    /** When set, generate/create suspend until completed. */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun refreshActivePlan(): ApiResult<ActivePlan> =
        refreshResult().also { if (it is ApiResult.Success) activePlan.value = it.value }

    override suspend fun exercises(filter: ExerciseFilter, cursor: String?, limit: Int): ApiResult<ExercisePage> {
        exerciseCalls += filter to cursor
        return pages(filter, cursor)
    }

    override suspend fun exercise(id: String): ApiResult<Exercise> =
        catalog[id]?.let { ApiResult.Success(it) } ?: ApiResult.Failure(ApiError.Http(404, "NOT_FOUND", "nope", null))

    override suspend fun generate(request: PlanGenerateRequest): ApiResult<PlanDraft> {
        generateCalls += request
        gate?.await()
        return generateResult(request)
    }

    override suspend fun create(draft: PlanDraft, idempotencyKey: String): ApiResult<WorkoutPlan> {
        createKeys += idempotencyKey
        gate?.await()
        return createResult(draft).also { if (it is ApiResult.Success) created[it.value.id] = it.value }
    }

    override suspend fun activate(planId: String, idempotencyKey: String): ApiResult<WorkoutPlan> {
        activateCalls += planId to idempotencyKey
        val r = activateResult(planId)
            ?: ApiResult.Success((created[planId] ?: PlanDraft("x", 1, PlanSource.CUSTOM, null, emptyList()).asPlan(planId)).copy(isActive = true))
        if (r is ApiResult.Success) activePlan.value = ActivePlan.Some(r.value)
        return r
    }
}
