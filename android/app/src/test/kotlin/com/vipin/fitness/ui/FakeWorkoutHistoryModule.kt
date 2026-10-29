package com.vipin.fitness.ui

import com.vipin.fitness.core.data.di.WorkoutHistoryDataModule
import com.vipin.fitness.core.data.history.WorkoutHistoryRepository
import com.vipin.fitness.core.testing.FakeWorkoutHistoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: workout history from memory, so no UI test reaches GET /workout-sessions/history. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [WorkoutHistoryDataModule::class])
object FakeWorkoutHistoryModule {
    @Provides @Singleton fun fakeHistory() = FakeWorkoutHistoryRepository()
    @Provides fun history(f: FakeWorkoutHistoryRepository): WorkoutHistoryRepository = f
}
