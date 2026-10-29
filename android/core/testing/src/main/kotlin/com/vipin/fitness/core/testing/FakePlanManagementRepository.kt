package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.workout.PlanManagementRepository
import com.vipin.fitness.core.data.workout.PlanRowUpdate
import com.vipin.fitness.core.data.workout.PlanSource
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.data.workout.PlanSummary
import com.vipin.fitness.core.data.workout.PlanSummaryPage
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

fun testPlanSummary(id: String, status: PlanStatus = PlanStatus.INACTIVE, name: String = "Plan $id") = PlanSummary(
    id = id, name = name, source = PlanSource.RULE_BASED, version = 1, status = status,
    currentWeek = if (status == PlanStatus.ACTIVE || status == PlanStatus.PAUSED) 2 else null,
    totalWeeks = 8, completedSessions = 3, createdAt = "2026-10-01T00:00:00Z",
)

private fun PlanSummary.asPlan() = WorkoutPlan(id, version, name, totalWeeks, null, emptyList(), status == PlanStatus.ACTIVE, source)

/** In-memory plans; [failure] fails the next writes, [pages] overrides list(). */
class FakePlanManagementRepository : PlanManagementRepository {
    companion object {
        const val PAUSED_AT = "2026-10-29T06:00:00Z"
    }

    val plans = mutableListOf<PlanSummary>()
    var pages: ((PlanStatus?, String?) -> ApiResult<PlanSummaryPage>)? = null
    var failure: ApiResult.Failure? = null
    var listGate: CompletableDeferred<Unit>? = null
    val listCalls = mutableListOf<Pair<PlanStatus?, String?>>()
    /** "action:id:key" per write. */
    val calls = mutableListOf<String>()

    override suspend fun list(status: PlanStatus?, cursor: String?, limit: Int): ApiResult<PlanSummaryPage> {
        listCalls += status to cursor
        listGate?.await()
        pages?.let { return it(status, cursor) }
        val items = plans.filter { if (status == null) it.status != PlanStatus.ARCHIVED else it.status == status }
        return ApiResult.Success(PlanSummaryPage(items, null))
    }

    private fun set(id: String, status: PlanStatus): PlanSummary {
        val i = plans.indexOfFirst { it.id == id }
        if (status == PlanStatus.ACTIVE) {
            plans.replaceAll { if (it.status == PlanStatus.ACTIVE) it.copy(status = PlanStatus.PAUSED, pausedAt = PAUSED_AT) else it }
        }
        val p = plans[i]
        val pausedAt = when (status) {
            PlanStatus.PAUSED -> p.pausedAt ?: PAUSED_AT
            // Archiving the active plan pauses it first; archive keeps pausedAt.
            PlanStatus.ARCHIVED -> if (p.status == PlanStatus.ACTIVE) PAUSED_AT else p.pausedAt
            else -> null
        }
        return p.copy(status = status, pausedAt = pausedAt).also { plans[i] = it }
    }

    private inline fun <T> write(tag: String, id: String, key: String, block: () -> T): ApiResult<T> {
        calls += "$tag:$id:$key"
        failure?.let { return it }
        return ApiResult.Success(block())
    }

    override suspend fun pause(planId: String, idempotencyKey: String) = write("pause", planId, idempotencyKey) { set(planId, PlanStatus.PAUSED); Unit }
    override suspend fun resume(planId: String, idempotencyKey: String) = write("resume", planId, idempotencyKey) { set(planId, PlanStatus.ACTIVE).asPlan() }
    /** Mirrors the contract: activate on the active plan is a no-op unless it was paused first. */
    override suspend fun restart(planId: String, idempotencyKey: String, wasActive: Boolean) = write("restart", planId, idempotencyKey) {
        val i = plans.indexOfFirst { it.id == planId }
        if (plans[i].status == PlanStatus.ACTIVE && !wasActive) return@write plans[i].asPlan()
        set(planId, PlanStatus.ACTIVE)
        plans[i] = plans[i].copy(currentWeek = 1, pausedAt = null)
        plans[i].asPlan()
    }
    override suspend fun archive(planId: String, idempotencyKey: String) = write("archive", planId, idempotencyKey) { set(planId, PlanStatus.ARCHIVED); Unit }
    override suspend fun unarchive(planId: String, idempotencyKey: String) = write("unarchive", planId, idempotencyKey) {
        val p = set(planId, if (plans.first { it.id == planId }.pausedAt != null) PlanStatus.PAUSED else PlanStatus.INACTIVE)
        PlanRowUpdate(p.id, p.name, p.status, p.pausedAt, p.currentWeek)
    }
    override suspend fun rename(planId: String, name: String, idempotencyKey: String) = write("rename", planId, idempotencyKey) {
        val i = plans.indexOfFirst { it.id == planId }
        plans[i] = plans[i].copy(name = name)
        plans[i].asPlan()
    }
}
