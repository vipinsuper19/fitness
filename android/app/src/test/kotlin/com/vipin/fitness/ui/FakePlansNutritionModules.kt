package com.vipin.fitness.ui

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
import com.vipin.fitness.core.data.NutritionSummary
import com.vipin.fitness.core.data.NutritionSummaryRepository
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.data.WaterLog
import com.vipin.fitness.core.data.di.NutritionDataModule
import com.vipin.fitness.core.data.di.NutritionSummaryDataModule
import com.vipin.fitness.core.data.di.PlanManagementDataModule
import com.vipin.fitness.core.data.workout.PlanManagementRepository
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakePlanManagementRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import javax.inject.Singleton

/** Test-only: plan management (#102), the diary and the nutrition summary (#106) never reach a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [PlanManagementDataModule::class])
abstract class FakePlanManagementModule {
    @Binds abstract fun plans(f: FakePlanManagementRepository): PlanManagementRepository
    companion object {
        @Provides @Singleton fun fakePlans() = FakePlanManagementRepository()
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NutritionDataModule::class])
abstract class FakeNutritionModule {
    @Binds abstract fun nutrition(f: FakeDiaryRepository): NutritionRepository
    companion object {
        @Provides @Singleton fun fakeDiary() = FakeDiaryRepository()
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NutritionSummaryDataModule::class])
abstract class FakeNutritionSummaryModule {
    @Binds abstract fun summary(f: FakeAppNutritionSummaryRepository): NutritionSummaryRepository
    companion object {
        @Provides @Singleton fun fakeSummary() = FakeAppNutritionSummaryRepository()
    }
}

/** An empty diary for any day; writes fail as offline (these tests don't log food). */
class FakeDiaryRepository : NutritionRepository {
    override suspend fun day(date: LocalDate) = ApiResult.Success(
        DiaryDay(DailyNutrition(date, 0, Nutrients(), emptyMap(), 0, null, null), emptyList(), emptyList()),
    )
    override suspend fun searchFoods(query: String, cursor: String?): ApiResult<FoodPage> = FAKE_NETWORK_FAILURE
    override suspend fun createFood(food: NewFood, idempotencyKey: String): ApiResult<Food> = FAKE_NETWORK_FAILURE
    override suspend fun logFood(foodId: String, date: LocalDate, meal: MealType, servings: Double, nutrientsOverride: Nutrients?, idempotencyKey: String): ApiResult<FoodLog> = FAKE_NETWORK_FAILURE
    override suspend fun updateFoodLog(id: String, changes: FoodLogChanges, idempotencyKey: String): ApiResult<FoodLog> = FAKE_NETWORK_FAILURE
    override suspend fun deleteFoodLog(id: String, idempotencyKey: String): ApiResult<Unit> = FAKE_NETWORK_FAILURE
    override suspend fun addWater(date: LocalDate, amountMl: Int, idempotencyKey: String): ApiResult<WaterLog> = FAKE_NETWORK_FAILURE
    override suspend fun updateWater(id: String, date: LocalDate, amountMl: Int, idempotencyKey: String): ApiResult<WaterLog> = FAKE_NETWORK_FAILURE
    override suspend fun deleteWater(id: String, idempotencyKey: String): ApiResult<Unit> = FAKE_NETWORK_FAILURE
}

/** Records each call; [gates] holds a bucket's response until completed. */
class FakeAppNutritionSummaryRepository : NutritionSummaryRepository {
    var result: (LocalDate, LocalDate, SummaryBucket) -> ApiResult<NutritionSummary> = { _, _, _ -> FAKE_NETWORK_FAILURE }
    val gates = mutableMapOf<SummaryBucket, CompletableDeferred<Unit>>()
    val calls = mutableListOf<Triple<LocalDate, LocalDate, SummaryBucket>>()
    override suspend fun summary(from: LocalDate, to: LocalDate, bucket: SummaryBucket): ApiResult<NutritionSummary> {
        calls += Triple(from, to, bucket)
        gates[bucket]?.await()
        return result(from, to, bucket)
    }
}
