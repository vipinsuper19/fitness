package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultNutritionSummaryRepository
import com.vipin.fitness.core.data.NutritionSummaryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class NutritionSummaryDataModule {
    @Binds abstract fun summary(impl: DefaultNutritionSummaryRepository): NutritionSummaryRepository
}
