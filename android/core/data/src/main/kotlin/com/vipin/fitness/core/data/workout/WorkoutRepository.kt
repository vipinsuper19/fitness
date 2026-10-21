package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.WorkoutApi
import com.vipin.fitness.core.network.apiCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

interface WorkoutRepository {
    /** Last known active plan: null until loaded, then [ActivePlan.None] or [ActivePlan.Some]. */
    val activePlan: StateFlow<ActivePlan?>

    suspend fun refreshActivePlan(): ApiResult<ActivePlan>
    suspend fun exercises(filter: ExerciseFilter, cursor: String? = null, limit: Int = PAGE_SIZE): ApiResult<ExercisePage>
    /** Cached in memory: plans reference exercises by id and the catalog is read-only. */
    suspend fun exercise(id: String): ApiResult<Exercise>
    suspend fun generate(request: PlanGenerateRequest): ApiResult<PlanDraft>
    suspend fun create(draft: PlanDraft, idempotencyKey: String): ApiResult<WorkoutPlan>
    /** On success also updates [activePlan]. */
    suspend fun activate(planId: String, idempotencyKey: String): ApiResult<WorkoutPlan>

    companion object {
        const val PAGE_SIZE = 20
    }
}

sealed interface ActivePlan {
    data object None : ActivePlan
    data class Some(val plan: WorkoutPlan) : ActivePlan
}

@Singleton
class DefaultWorkoutRepository @Inject constructor(
    private val api: WorkoutApi,
    private val json: Json,
) : WorkoutRepository {
    private val _active = MutableStateFlow<ActivePlan?>(null)
    override val activePlan: StateFlow<ActivePlan?> = _active.asStateFlow()
    private val exerciseCache = ConcurrentHashMap<String, Exercise>()

    override suspend fun refreshActivePlan(): ApiResult<ActivePlan> = apiCall(json) {
        val r = api.active()
        when {
            r.code() == 204 -> ActivePlan.None
            r.isSuccessful -> r.body()?.let { ActivePlan.Some(it.toDomain()) } ?: ActivePlan.None
            else -> throw HttpException(r)
        }
    }.also { if (it is ApiResult.Success) _active.value = it.value }

    override suspend fun exercises(filter: ExerciseFilter, cursor: String?, limit: Int) = apiCall(json) {
        val page = api.exercises(
            query = filter.query.trim().take(100).ifEmpty { null },
            muscle = filter.muscle?.wire,
            equipment = filter.equipment?.wire,
            cursor = cursor,
            limit = limit,
        )
        ExercisePage(page.items.map { it.toDomain() }.onEach { exerciseCache[it.id] = it }, page.nextCursor)
    }

    override suspend fun exercise(id: String): ApiResult<Exercise> {
        exerciseCache[id]?.let { return ApiResult.Success(it) }
        return apiCall(json) { api.exercise(id).toDomain().also { exerciseCache[id] = it } }
    }

    override suspend fun generate(request: PlanGenerateRequest) = apiCall(json) { api.generate(request.toDto()).toDomain() }

    override suspend fun create(draft: PlanDraft, idempotencyKey: String) =
        apiCall(json) { api.create(draft.toDto(), idempotencyKey).toDomain() }

    override suspend fun activate(planId: String, idempotencyKey: String) =
        apiCall(json) { api.activate(planId, idempotencyKey).toDomain() }
            .also { if (it is ApiResult.Success) _active.value = ActivePlan.Some(it.value) }
}

/** True for a 404 (e.g. an exercise removed from the catalog). */
fun ApiError.isNotFound() = this is ApiError.Http && status == 404
