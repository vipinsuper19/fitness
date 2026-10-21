package com.vipin.fitness.feature.nutrition

import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.DiaryDay
import com.vipin.fitness.core.data.Food
import com.vipin.fitness.core.data.FoodLog
import com.vipin.fitness.core.data.FoodLogChanges
import com.vipin.fitness.core.data.FoodPage
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.NewFood
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.NutritionRepository
import com.vipin.fitness.core.data.ServingUnit
import com.vipin.fitness.core.data.WaterLog
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import java.time.LocalDate

val NETWORK_FAILURE = ApiResult.Failure(ApiError.Network)

fun food(id: String = "f1", kcal: Double = 100.0, grams: Double? = 150.0) = Food(
    id = id, name = "Poha", servingSize = 1.0, servingUnit = ServingUnit.KATORI, gramsPerServing = grams,
    nutrients = Nutrients(kcal, 2.0, 20.0, 1.0),
)

fun log(id: String = "l1", meal: MealType = MealType.BREAKFAST, servings: Double = 1.0, edited: Boolean = false) = FoodLog(
    id = id, foodId = "f1", foodName = "Poha", date = LocalDate.of(2026, 10, 21), mealType = meal, servings = servings,
    nutrients = Nutrients(100.0 * servings, 2.0, 20.0, 1.0), nutrientsEdited = edited,
)

/** In-memory repository that records every call and the idempotency key it got. */
class FakeNutritionRepository : NutritionRepository {
    val dayCalls = mutableListOf<LocalDate>()
    val writes = mutableListOf<Pair<String, String>>()
    var logs = mutableListOf(log())
    var water = mutableListOf<WaterLog>()
    var failNext: ApiResult.Failure? = null
    var dayFailure: ApiResult.Failure? = null
    var pages: MutableMap<String?, FoodPage> = mutableMapOf(null to FoodPage(listOf(food()), "c2"), "c2" to FoodPage(listOf(food("f2")), null))
    val searches = mutableListOf<Pair<String, String?>>()
    var lastLog: List<Any?>? = null
    var lastChanges: FoodLogChanges? = null
    var lastFood: NewFood? = null

    private fun <T> write(op: String, key: String, value: () -> T): ApiResult<T> {
        writes += op to key
        failNext?.let { failNext = null; return it }
        return ApiResult.Success(value())
    }

    override suspend fun day(date: LocalDate): ApiResult<DiaryDay> {
        dayCalls += date
        dayFailure?.let { return it }
        val totals = logs.fold(Nutrients.ZERO) { a, l -> Nutrients(a.energyKcal + l.nutrients.energyKcal, a.proteinG + l.nutrients.proteinG, a.carbsG + l.nutrients.carbsG, a.fatG + l.nutrients.fatG) }
        val daily = DailyNutrition(date, logs.size, totals, MealType.entries.associateWith { Nutrients.ZERO }, water.sumOf { it.amountMl }, 2000, 2500)
        return ApiResult.Success(DiaryDay(daily, logs.toList(), water.toList()))
    }

    override suspend fun searchFoods(query: String, cursor: String?): ApiResult<FoodPage> {
        searches += query to cursor
        failNext?.let { failNext = null; return it }
        return ApiResult.Success(pages[cursor] ?: FoodPage(emptyList(), null))
    }

    override suspend fun createFood(food: NewFood, idempotencyKey: String) = write("createFood", idempotencyKey) {
        lastFood = food
        Food("new", food.name, null, food.brand, food.servingSize, food.servingUnit, food.gramsPerServing, food.nutrients, food.isVegetarian, custom = true)
    }

    override suspend fun logFood(foodId: String, date: LocalDate, meal: MealType, servings: Double, nutrientsOverride: Nutrients?, idempotencyKey: String) =
        write("logFood", idempotencyKey) {
            lastLog = listOf(foodId, date, meal, servings, nutrientsOverride)
            log("l${logs.size + 1}", meal, servings).also { logs += it }
        }

    override suspend fun updateFoodLog(id: String, changes: FoodLogChanges, idempotencyKey: String) = write("updateFoodLog", idempotencyKey) {
        lastChanges = changes
        logs.first { it.id == id }
    }

    override suspend fun deleteFoodLog(id: String, idempotencyKey: String) = write("deleteFoodLog", idempotencyKey) { logs.removeAll { it.id == id }; Unit }

    override suspend fun addWater(date: LocalDate, amountMl: Int, idempotencyKey: String) = write("addWater", idempotencyKey) {
        WaterLog("w${water.size + 1}", date, amountMl).also { water += it }
    }

    override suspend fun updateWater(id: String, date: LocalDate, amountMl: Int, idempotencyKey: String) = write("updateWater", idempotencyKey) {
        WaterLog(id, date, amountMl).also { w -> water.replaceAll { if (it.id == id) w else it } }
    }

    override suspend fun deleteWater(id: String, idempotencyKey: String) = write("deleteWater", idempotencyKey) { water.removeAll { it.id == id }; Unit }
}
