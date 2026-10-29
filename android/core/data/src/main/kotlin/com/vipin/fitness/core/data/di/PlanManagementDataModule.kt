package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.workout.DefaultPlanManagementRepository
import com.vipin.fitness.core.data.workout.PlanManagementRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class PlanManagementDataModule {
    @Binds abstract fun plans(impl: DefaultPlanManagementRepository): PlanManagementRepository
}
