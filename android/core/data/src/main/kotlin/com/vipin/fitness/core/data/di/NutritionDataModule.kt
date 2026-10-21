package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultNutritionRepository
import com.vipin.fitness.core.data.NutritionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Separate module so nutrition doesn't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class NutritionDataModule {
    @Binds abstract fun nutrition(impl: DefaultNutritionRepository): NutritionRepository
}
