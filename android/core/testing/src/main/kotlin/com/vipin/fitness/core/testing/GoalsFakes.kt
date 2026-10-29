package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Goal
import com.vipin.fitness.core.data.GoalChanges
import com.vipin.fitness.core.data.GoalStatus
import com.vipin.fitness.core.data.GoalsRepository
import com.vipin.fitness.core.data.NewGoal
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

class FakeGoalsRepository : GoalsRepository {
    val goals = mutableListOf<Goal>()
    var failNext: ApiResult.Failure? = null
    var listFailure: ApiResult.Failure? = null
    var gate: CompletableDeferred<Unit>? = null
    /** (operation, idempotency key) for each write. */
    val writes = mutableListOf<Pair<String, String>>()
    private var nextId = 1

    override suspend fun list(status: GoalStatus?): ApiResult<List<Goal>> =
        listFailure ?: ApiResult.Success(goals.filter { status == null || it.status == status })

    override suspend fun create(goal: NewGoal, idempotencyKey: String): ApiResult<Goal> {
        writes += "create" to idempotencyKey
        gate?.await()
        failNext?.let { failNext = null; return it }
        val g = Goal("g${nextId++}", goal.type, GoalStatus.ACTIVE, goal.targetValue, goal.startValue, goal.targetDate)
        goals.add(0, g)
        return ApiResult.Success(g)
    }

    override suspend fun update(id: String, changes: GoalChanges, idempotencyKey: String): ApiResult<Goal> {
        writes += "update:$id" to idempotencyKey
        failNext?.let { failNext = null; return it }
        val i = goals.indexOfFirst { it.id == id }
        var g = goals[i]
        changes.status?.let { g = g.copy(status = it) }
        when (val v = changes.targetValue) { is Field.Set -> g = g.copy(targetValue = v.value); Field.Clear -> g = g.copy(targetValue = null); else -> Unit }
        when (val v = changes.targetDate) { is Field.Set -> g = g.copy(targetDate = v.value); Field.Clear -> g = g.copy(targetDate = null); else -> Unit }
        goals[i] = g
        return ApiResult.Success(g)
    }

    override suspend fun delete(id: String, idempotencyKey: String): ApiResult<Unit> {
        writes += "delete:$id" to idempotencyKey
        failNext?.let { failNext = null; return it }
        goals.removeAll { it.id == id }
        return ApiResult.Success(Unit)
    }
}
