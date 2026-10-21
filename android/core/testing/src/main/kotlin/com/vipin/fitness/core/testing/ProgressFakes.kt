package com.vipin.fitness.core.testing

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.BodyMeasurement
import com.vipin.fitness.core.data.BodyMeasurementPage
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.HomeRepository
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.MeasurementChanges
import com.vipin.fitness.core.data.NewMeasurement
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.ProgressPoint
import com.vipin.fitness.core.data.ProgressRepository
import com.vipin.fitness.core.data.ProgressSummary
import com.vipin.fitness.core.data.UnitPreference
import com.vipin.fitness.core.data.XpBalance
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

val FAKE_NETWORK_FAILURE = ApiResult.Failure(ApiError.Network)

class FakeUnitPreference(units: UnitSystem = UnitSystem.METRIC) : UnitPreference {
    override val units = MutableStateFlow(units)
}

/** In-memory measurements and summary; records every write with its idempotency key. */
class FakeProgressRepository : ProgressRepository {
    val items = mutableListOf<BodyMeasurement>()
    var pageSize = 20
    /** When set, a "Load more" (cursor != null) suspends until completed. */
    var pageGate: CompletableDeferred<Unit>? = null
    var failNext: ApiResult.Failure? = null
    var summaryFailure: ApiResult.Failure? = null
    val writes = mutableListOf<Pair<String, String>>()
    var lastNew: NewMeasurement? = null
    var lastChanges: MeasurementChanges? = null
    val summaryCalls = mutableListOf<Pair<LocalDate, LocalDate>>()
    var points: Map<LocalDate, ProgressPoint> = emptyMap()
    var streakDays = 0
    private var nextId = 1

    override suspend fun measurements(cursor: String?): ApiResult<BodyMeasurementPage> {
        if (cursor != null) pageGate?.await()
        val sorted = items.sortedByDescending { it.date }
        val start = cursor?.toInt() ?: 0
        val page = sorted.drop(start).take(pageSize)
        val next = (start + pageSize).takeIf { it < sorted.size }?.toString()
        return ApiResult.Success(BodyMeasurementPage(page, next))
    }

    override suspend fun addMeasurement(m: NewMeasurement, idempotencyKey: String) = write("add", idempotencyKey) {
        lastNew = m
        BodyMeasurement("m${nextId++}", m.date, m.values).also { items += it }
    }

    override suspend fun updateMeasurement(id: String, changes: MeasurementChanges, idempotencyKey: String) = write("update", idempotencyKey) {
        check(!changes.isEmpty) { "empty edit sent" }
        lastChanges = changes
        val old = items.first { it.id == id }
        val values = old.values.toMutableMap()
        changes.values.forEach { (k, f) ->
            when (f) {
                Field.Unchanged -> Unit
                Field.Clear -> values.remove(k)
                is Field.Set -> values[k] = f.value
            }
        }
        old.copy(date = changes.date ?: old.date, values = values).also { items[items.indexOf(old)] = it }
    }

    override suspend fun deleteMeasurement(id: String, idempotencyKey: String) = write("delete", idempotencyKey) {
        items.removeAll { it.id == id }
        Unit
    }

    override suspend fun summary(from: LocalDate, to: LocalDate): ApiResult<ProgressSummary> {
        summaryCalls += from to to
        summaryFailure?.let { return it }
        val days = generateSequence(from) { d -> d.plusDays(1).takeIf { it <= to } }.toList()
        return ApiResult.Success(ProgressSummary(from, to, days.map { points[it] ?: ProgressPoint(it) }, streakDays))
    }

    private fun <T> write(name: String, key: String, block: () -> T): ApiResult<T> {
        writes += name to key
        failNext?.let { failNext = null; return it }
        return ApiResult.Success(block())
    }
}

class FakeHomeRepository : HomeRepository {
    var xp = XpBalance(0, 1, 0, 100)
    var nutrition: (LocalDate) -> DailyNutrition = { emptyDay(it) }
    var xpFailure: ApiResult.Failure? = null
    var nutritionFailure: ApiResult.Failure? = null
    val nutritionCalls = mutableListOf<LocalDate>()

    override suspend fun xpBalance(): ApiResult<XpBalance> = xpFailure ?: ApiResult.Success(xp)
    override suspend fun dailyNutrition(date: LocalDate): ApiResult<DailyNutrition> {
        nutritionCalls += date
        return nutritionFailure ?: ApiResult.Success(nutrition(date))
    }

    companion object {
        fun emptyDay(date: LocalDate) = DailyNutrition(
            date, 0, Nutrients.ZERO, MealType.entries.associateWith { Nutrients.ZERO }, 0, null, null,
        )
    }
}
