package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.model.DailyNutritionDto
import com.vipin.fitness.core.network.model.FoodCreateDto
import com.vipin.fitness.core.network.model.FoodDto
import com.vipin.fitness.core.network.model.FoodLogDto
import com.vipin.fitness.core.network.model.NutrientsDto
import com.vipin.fitness.core.network.model.WaterLogDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode

enum class MealType(val wire: String) {
    BREAKFAST("breakfast"), LUNCH("lunch"), DINNER("dinner"), SNACK("snack");

    companion object {
        fun fromWire(s: String): MealType = entries.firstOrNull { it.wire == s } ?: SNACK
    }
}

/** Contract `ServingUnit`: Indian household measures alongside metric. */
enum class ServingUnit(val wire: String) {
    G("g"), ML("ml"), PIECE("piece"), KATORI("katori"), CUP("cup"), TBSP("tbsp"), TSP("tsp"),
    PLATE("plate"), GLASS("glass"), SLICE("slice");

    companion object {
        fun fromWire(s: String): ServingUnit = entries.firstOrNull { it.wire == s } ?: PIECE
    }
}

/** Energy in kcal, macros in g, sodium in mg (metric only). Optional values are null when unknown. */
data class Nutrients(
    val energyKcal: Double = 0.0,
    val proteinG: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
) {
    /** Scales for [servings]; used for previews before the server computes the entry. */
    fun times(servings: Double) = Nutrients(
        energyKcal * servings, proteinG * servings, carbsG * servings, fatG * servings,
        fiberG?.times(servings), sugarG?.times(servings), sodiumMg?.times(servings),
    )

    /** The contract accepts at most one decimal place on input. */
    fun rounded1dp() = Nutrients(
        r1(energyKcal), r1(proteinG), r1(carbsG), r1(fatG), fiberG?.let(::r1), sugarG?.let(::r1), sodiumMg?.let(::r1),
    )

    companion object {
        val ZERO = Nutrients()
    }
}

private fun r1(v: Double) = BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toDouble()

data class Food(
    val id: String,
    val name: String,
    val nameHi: String? = null,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: ServingUnit,
    val gramsPerServing: Double? = null,
    val nutrients: Nutrients,
    val isVegetarian: Boolean? = null,
    val custom: Boolean = false,
)

data class FoodPage(val items: List<Food>, val nextCursor: String?)

data class NewFood(
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: ServingUnit,
    val gramsPerServing: Double? = null,
    val nutrients: Nutrients,
    val isVegetarian: Boolean? = null,
)

data class FoodLog(
    val id: String,
    val foodId: String,
    val foodName: String,
    val date: LocalDate,
    val mealType: MealType,
    val servings: Double,
    val nutrients: Nutrients,
    val nutrientsEdited: Boolean = false,
)

/** PATCH /food-logs/{id}. Null [mealType]/[servings] = unchanged. [nutrients]: Unchanged, Clear (restore computed) or Set. */
data class FoodLogChanges(
    val mealType: MealType? = null,
    val servings: Double? = null,
    val nutrients: Field<Nutrients> = Field.Unchanged,
)

data class WaterLog(val id: String, val date: LocalDate, val amountMl: Int)

data class DailyNutrition(
    val date: LocalDate,
    val entryCount: Int,
    val totals: Nutrients,
    val byMeal: Map<MealType, Nutrients>,
    val waterMl: Int,
    val calorieTargetKcal: Int?,
    val waterTargetMl: Int?,
    /** True when some entries lack fibre/sugar/sodium, so those totals under-count. */
    val partialOptionalTotals: Boolean = false,
)

/** Everything the diary screen shows for one day. */
data class DiaryDay(val daily: DailyNutrition, val foodLogs: List<FoodLog>, val waterLogs: List<WaterLog>)

fun NutrientsDto.toDomain() = Nutrients(energyKcal, proteinG, carbsG, fatG, fiberG, sugarG, sodiumMg)

fun Nutrients.toDto() = NutrientsDto(energyKcal, proteinG, carbsG, fatG, fiberG, sugarG, sodiumMg)

fun FoodDto.toDomain() = Food(
    id, name, nameHi, brand, servingSize, ServingUnit.fromWire(servingUnit), gramsPerServing,
    nutrients.toDomain(), isVegetarian, custom = source == "custom",
)

fun NewFood.toDto() = FoodCreateDto(
    name = name.trim(),
    brand = brand?.trim()?.ifBlank { null },
    servingSize = servingSize,
    servingUnit = servingUnit.wire,
    gramsPerServing = gramsPerServing,
    nutrients = nutrients.rounded1dp().toDto(),
    isVegetarian = isVegetarian,
)

fun FoodLogDto.toDomain() = FoodLog(
    id, foodId, foodName, LocalDate.parse(date), MealType.fromWire(mealType), servings, nutrients.toDomain(), nutrientsEdited,
)

fun WaterLogDto.toDomain() = WaterLog(id, LocalDate.parse(date), amountMl)

fun DailyNutritionDto.toDomain() = DailyNutrition(
    date = LocalDate.parse(date),
    entryCount = entryCount,
    totals = totals.toDomain(),
    byMeal = mapOf(
        MealType.BREAKFAST to byMeal.breakfast.toDomain(),
        MealType.LUNCH to byMeal.lunch.toDomain(),
        MealType.DINNER to byMeal.dinner.toDomain(),
        MealType.SNACK to byMeal.snack.toDomain(),
    ),
    waterMl = waterMl,
    calorieTargetKcal = calorieTargetKcal,
    waterTargetMl = waterTargetMl,
    partialOptionalTotals = entriesMissing.let { m -> listOf(m.fiberG, m.sugarG, m.sodiumMg).any { it in 1 until entryCount } },
)

fun FoodLogChanges.toJson(): JsonObject = buildJsonObject {
    mealType?.let { put("mealType", it.wire) }
    servings?.let { put("servings", it) }
    when (val n = nutrients) {
        Field.Unchanged -> Unit
        Field.Clear -> put("nutrientsOverride", JsonNull)
        is Field.Set -> put(
            "nutrientsOverride",
            n.value.rounded1dp().let { v ->
                buildJsonObject {
                    put("energyKcal", v.energyKcal)
                    put("proteinG", v.proteinG)
                    put("carbsG", v.carbsG)
                    put("fatG", v.fatG)
                    v.fiberG?.let { put("fiberG", JsonPrimitive(it)) }
                    v.sugarG?.let { put("sugarG", JsonPrimitive(it)) }
                    v.sodiumMg?.let { put("sodiumMg", JsonPrimitive(it)) }
                }
            },
        )
    }
}
