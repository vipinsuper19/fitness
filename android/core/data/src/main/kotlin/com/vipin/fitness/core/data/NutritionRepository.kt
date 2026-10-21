package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.FoodLogCreateDto
import com.vipin.fitness.core.network.model.WaterLogCreateDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import retrofit2.Response
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Food diary, food search, custom foods and water. Every write takes an [idempotencyKey]:
 * one per user action, reused when that same action is retried.
 */
interface NutritionRepository {
    suspend fun day(date: LocalDate): ApiResult<DiaryDay>
    suspend fun searchFoods(query: String, cursor: String? = null): ApiResult<FoodPage>
    suspend fun createFood(food: NewFood, idempotencyKey: String = newIdempotencyKey()): ApiResult<Food>
    suspend fun logFood(
        foodId: String,
        date: LocalDate,
        meal: MealType,
        servings: Double,
        nutrientsOverride: Nutrients? = null,
        idempotencyKey: String = newIdempotencyKey(),
    ): ApiResult<FoodLog>
    suspend fun updateFoodLog(id: String, changes: FoodLogChanges, idempotencyKey: String = newIdempotencyKey()): ApiResult<FoodLog>
    suspend fun deleteFoodLog(id: String, idempotencyKey: String = newIdempotencyKey()): ApiResult<Unit>
    suspend fun addWater(date: LocalDate, amountMl: Int, idempotencyKey: String = newIdempotencyKey()): ApiResult<WaterLog>
    suspend fun updateWater(id: String, date: LocalDate, amountMl: Int, idempotencyKey: String = newIdempotencyKey()): ApiResult<WaterLog>
    suspend fun deleteWater(id: String, idempotencyKey: String = newIdempotencyKey()): ApiResult<Unit>
}

@Singleton
class DefaultNutritionRepository @Inject constructor(
    private val api: NutritionApi,
    private val json: Json,
) : NutritionRepository {
    override suspend fun day(date: LocalDate): ApiResult<DiaryDay> = apiCall(json) {
        val d = date.toString()
        coroutineScope {
            val daily = async { api.daily(d) }
            val foods = async { api.foodLogs(d) }
            val water = async { api.waterLogs(d) }
            DiaryDay(
                daily = daily.await().toDomain(),
                foodLogs = foods.await().items.map { it.toDomain() },
                waterLogs = water.await().items.map { it.toDomain() },
            )
        }
    }

    override suspend fun searchFoods(query: String, cursor: String?) = apiCall(json) {
        val page = api.searchFoods(query.trim().take(MAX_QUERY), cursor, PAGE_SIZE)
        FoodPage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    override suspend fun createFood(food: NewFood, idempotencyKey: String) =
        apiCall(json) { api.createFood(food.toDto(), idempotencyKey).toDomain() }

    override suspend fun logFood(
        foodId: String,
        date: LocalDate,
        meal: MealType,
        servings: Double,
        nutrientsOverride: Nutrients?,
        idempotencyKey: String,
    ) = apiCall(json) {
        val body = FoodLogCreateDto(foodId, date.toString(), meal.wire, servings, nutrientsOverride?.rounded1dp()?.toDto())
        api.createFoodLog(body, idempotencyKey).toDomain()
    }

    override suspend fun updateFoodLog(id: String, changes: FoodLogChanges, idempotencyKey: String) =
        apiCall(json) { api.updateFoodLog(id, changes.toJson(), idempotencyKey).toDomain() }

    override suspend fun deleteFoodLog(id: String, idempotencyKey: String) =
        apiCall(json) { api.deleteFoodLog(id, idempotencyKey).orThrow() }

    override suspend fun addWater(date: LocalDate, amountMl: Int, idempotencyKey: String) =
        apiCall(json) { api.createWaterLog(WaterLogCreateDto(date.toString(), amountMl), idempotencyKey).toDomain() }

    override suspend fun updateWater(id: String, date: LocalDate, amountMl: Int, idempotencyKey: String) =
        apiCall(json) { api.updateWaterLog(id, WaterLogCreateDto(date.toString(), amountMl), idempotencyKey).toDomain() }

    override suspend fun deleteWater(id: String, idempotencyKey: String) =
        apiCall(json) { api.deleteWaterLog(id, idempotencyKey).orThrow() }

    private fun Response<Unit>.orThrow() {
        if (!isSuccessful) throw HttpException(this)
    }

    companion object {
        const val PAGE_SIZE = 20
        const val MAX_QUERY = 100
    }
}
