package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionApi
import com.vipin.fitness.core.network.ProgressApi
import com.vipin.fitness.core.network.apiCall
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Body measurements and the progress summary. Every write takes an [idempotencyKey]: one per
 * user action, reused when that same action is retried. Values are metric; the UI converts.
 */
interface ProgressRepository {
    suspend fun measurements(cursor: String? = null): ApiResult<BodyMeasurementPage>
    suspend fun addMeasurement(m: NewMeasurement, idempotencyKey: String = newIdempotencyKey()): ApiResult<BodyMeasurement>
    suspend fun updateMeasurement(id: String, changes: MeasurementChanges, idempotencyKey: String = newIdempotencyKey()): ApiResult<BodyMeasurement>
    suspend fun deleteMeasurement(id: String, idempotencyKey: String = newIdempotencyKey()): ApiResult<Unit>
    suspend fun summary(from: LocalDate, to: LocalDate): ApiResult<ProgressSummary>
}

/** Reads for the Home dashboard. */
interface HomeRepository {
    /** Success(null) when the user has no active plan. */
    suspend fun activePlan(): ApiResult<ActivePlan?>
    suspend fun xpBalance(): ApiResult<XpBalance>
    suspend fun dailyNutrition(date: LocalDate): ApiResult<DailyNutrition>
}

@Singleton
class DefaultProgressRepository @Inject constructor(
    private val api: ProgressApi,
    private val json: Json,
) : ProgressRepository {
    override suspend fun measurements(cursor: String?) = apiCall(json) {
        val page = api.measurements(cursor = cursor, limit = PAGE_SIZE)
        BodyMeasurementPage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    override suspend fun addMeasurement(m: NewMeasurement, idempotencyKey: String): ApiResult<BodyMeasurement> {
        require(m.values.isNotEmpty()) { "a measurement needs at least one value" }
        return apiCall(json) { api.createMeasurement(m.toJson(), idempotencyKey).toDomain() }
    }

    override suspend fun updateMeasurement(id: String, changes: MeasurementChanges, idempotencyKey: String): ApiResult<BodyMeasurement> {
        require(!changes.isEmpty) { "an empty edit must not be sent (the server rejects {})" }
        return apiCall(json) { api.updateMeasurement(id, changes.toJson(), idempotencyKey).toDomain() }
    }

    override suspend fun deleteMeasurement(id: String, idempotencyKey: String) =
        apiCall(json) { api.deleteMeasurement(id, idempotencyKey).orThrow() }

    override suspend fun summary(from: LocalDate, to: LocalDate) =
        apiCall(json) { api.summary(from.toString(), to.toString()).toDomain() }

    companion object {
        const val PAGE_SIZE = 20
    }
}

@Singleton
class DefaultHomeRepository @Inject constructor(
    private val api: ProgressApi,
    private val nutrition: NutritionApi,
    private val json: Json,
) : HomeRepository {
    override suspend fun activePlan(): ApiResult<ActivePlan?> = apiCall(json) {
        val r = api.activePlan()
        if (!r.isSuccessful) throw HttpException(r)
        if (r.code() == 204) null else r.body()?.toDomain()
    }

    override suspend fun xpBalance() = apiCall(json) { api.xpBalance().toDomain() }

    override suspend fun dailyNutrition(date: LocalDate) = apiCall(json) { nutrition.daily(date.toString()).toDomain() }
}

private fun Response<Unit>.orThrow() {
    if (!isSuccessful) throw HttpException(this)
}
