package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `Nutrients`. Per serving on a food; totals on a log entry or a day. */
@Serializable
data class NutrientsDto(
    val energyKcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
)

/** Contract `Food`. */
@Serializable
data class FoodDto(
    val id: String,
    val name: String,
    val nameHi: String? = null,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val gramsPerServing: Double? = null,
    val nutrients: NutrientsDto,
    val isVegetarian: Boolean? = null,
    val source: String,
)

@Serializable
data class FoodPageDto(val items: List<FoodDto>, val nextCursor: String? = null)

/** Contract `FoodCreate`. */
@Serializable
data class FoodCreateDto(
    val name: String,
    val brand: String? = null,
    val servingSize: Double,
    val servingUnit: String,
    val gramsPerServing: Double? = null,
    val nutrients: NutrientsDto,
    val isVegetarian: Boolean? = null,
)

/** Contract `FoodLog`. */
@Serializable
data class FoodLogDto(
    val id: String,
    val foodId: String,
    val foodName: String,
    val date: String,
    val mealType: String,
    val servings: Double,
    val nutrients: NutrientsDto,
    val nutrientsEdited: Boolean = false,
    val createdAt: String,
)

/** Contract `FoodLogCreate`. */
@Serializable
data class FoodLogCreateDto(
    val foodId: String,
    val date: String,
    val mealType: String,
    val servings: Double,
    val nutrientsOverride: NutrientsDto? = null,
)

@Serializable
data class FoodLogListDto(val items: List<FoodLogDto>)

/** Contract `WaterLog`. */
@Serializable
data class WaterLogDto(val id: String, val date: String, val amountMl: Int, val createdAt: String)

/** Contract `WaterLogCreate` (also the PATCH body). */
@Serializable
data class WaterLogCreateDto(val date: String, val amountMl: Int)

@Serializable
data class WaterLogListDto(val items: List<WaterLogDto>)

@Serializable
data class EntriesMissingDto(val fiberG: Int, val sugarG: Int, val sodiumMg: Int)

@Serializable
data class ByMealDto(val breakfast: NutrientsDto, val lunch: NutrientsDto, val dinner: NutrientsDto, val snack: NutrientsDto)

/** Contract `DailyNutrition`. */
@Serializable
data class DailyNutritionDto(
    val date: String,
    val entryCount: Int,
    val entriesMissing: EntriesMissingDto,
    val totals: NutrientsDto,
    val byMeal: ByMealDto,
    val waterMl: Int,
    val calorieTargetKcal: Int? = null,
    val waterTargetMl: Int? = null,
)
