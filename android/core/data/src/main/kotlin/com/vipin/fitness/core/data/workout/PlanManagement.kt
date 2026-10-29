package com.vipin.fitness.core.data.workout

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.PlanManagementApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.PlanActivateDto
import com.vipin.fitness.core.network.model.PlanRenameDto
import com.vipin.fitness.core.network.model.WorkoutPlanSummaryDto
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

enum class PlanStatus(override val wire: String) : Wire {
    ACTIVE("active"), PAUSED("paused"), INACTIVE("inactive"), ARCHIVED("archived");

    companion object {
        /** Unknown values read as inactive. */
        fun of(wire: String): PlanStatus = wire.toWireEnum<PlanStatus>() ?: INACTIVE
    }
}

data class PlanSummary(
    val id: String,
    val name: String,
    val source: PlanSource,
    val version: Int,
    val status: PlanStatus,
    val currentWeek: Int?,
    val totalWeeks: Int,
    val completedSessions: Int,
    val createdAt: String,
)

data class PlanSummaryPage(val items: List<PlanSummary>, val nextCursor: String?)

fun WorkoutPlanSummaryDto.toDomain() = PlanSummary(
    id = id,
    name = name,
    source = source.toWireEnum<PlanSource>() ?: PlanSource.CUSTOM,
    version = version,
    status = PlanStatus.of(status),
    currentWeek = currentWeek,
    totalWeeks = totalWeeks,
    completedSessions = completedSessions,
    createdAt = createdAt,
)

/** Contract limits for PUT /workout-plans/{id}/name, after trimming. */
object PlanName {
    const val MAX = 80
    fun normalize(raw: String): String? = raw.trim().takeIf { it.length in 1..MAX }
}

fun ApiError.isPlanArchived() = this is ApiError.Http && status == 409 && code == "PLAN_ARCHIVED"

/**
 * Plan list and lifecycle. Every write takes an Idempotency-Key and, on success, refreshes
 * [WorkoutRepository.activePlan] so the Workout tab follows pause/resume/switch.
 */
interface PlanManagementRepository {
    /** [status] null lists everything except archived. */
    suspend fun list(status: PlanStatus?, cursor: String? = null, limit: Int = WorkoutRepository.PAGE_SIZE): ApiResult<PlanSummaryPage>
    suspend fun pause(planId: String, idempotencyKey: String): ApiResult<Unit>
    suspend fun resume(planId: String, idempotencyKey: String): ApiResult<WorkoutPlan>
    /** Restart at week 1; also how an inactive plan becomes the active one. */
    suspend fun restart(planId: String, idempotencyKey: String): ApiResult<WorkoutPlan>
    suspend fun archive(planId: String, idempotencyKey: String): ApiResult<Unit>
    suspend fun unarchive(planId: String, idempotencyKey: String): ApiResult<WorkoutPlan>
    /** [name] must already be [PlanName.normalize]d. */
    suspend fun rename(planId: String, name: String, idempotencyKey: String): ApiResult<WorkoutPlan>
}

@Singleton
class DefaultPlanManagementRepository @Inject constructor(
    private val api: PlanManagementApi,
    private val workouts: WorkoutRepository,
    private val json: Json,
) : PlanManagementRepository {
    override suspend fun list(status: PlanStatus?, cursor: String?, limit: Int) = apiCall(json) {
        val page = api.list(status?.wire, cursor, limit)
        PlanSummaryPage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    override suspend fun pause(planId: String, idempotencyKey: String) =
        apiCall(json) { api.deactivate(planId, idempotencyKey).orThrow() }.synced()

    override suspend fun resume(planId: String, idempotencyKey: String) =
        apiCall(json) { api.activate(planId, idempotencyKey, PlanActivateDto(resume = true)).toDomain() }.synced()

    override suspend fun restart(planId: String, idempotencyKey: String) =
        apiCall(json) { api.restart(planId, idempotencyKey).toDomain() }.synced()

    override suspend fun archive(planId: String, idempotencyKey: String) =
        apiCall(json) { api.archive(planId, idempotencyKey).orThrow() }.synced()

    override suspend fun unarchive(planId: String, idempotencyKey: String) =
        apiCall(json) { api.unarchive(planId, idempotencyKey).toDomain() }

    override suspend fun rename(planId: String, name: String, idempotencyKey: String) =
        apiCall(json) { api.rename(planId, idempotencyKey, PlanRenameDto(name)).toDomain() }.synced()

    private fun Response<Unit>.orThrow() {
        if (!isSuccessful) throw HttpException(this)
    }

    private suspend fun <T> ApiResult<T>.synced(): ApiResult<T> = also {
        if (it is ApiResult.Success) workouts.refreshActivePlan()
    }
}
