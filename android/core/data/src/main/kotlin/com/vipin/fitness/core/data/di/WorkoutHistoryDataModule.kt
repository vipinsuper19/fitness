package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.history.DefaultWorkoutHistoryRepository
import com.vipin.fitness.core.data.history.WorkoutHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkoutHistoryDataModule {
    @Binds abstract fun history(impl: DefaultWorkoutHistoryRepository): WorkoutHistoryRepository
}
