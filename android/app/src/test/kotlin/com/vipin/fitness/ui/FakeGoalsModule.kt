package com.vipin.fitness.ui

import com.vipin.fitness.core.data.GoalsRepository
import com.vipin.fitness.core.data.di.GoalsDataModule
import com.vipin.fitness.core.testing.FakeGoalsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: goals are in memory (none), so no UI test reaches a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [GoalsDataModule::class])
object FakeGoalsModule {
    @Provides @Singleton fun fakeGoals() = FakeGoalsRepository()
    @Provides fun goals(f: FakeGoalsRepository): GoalsRepository = f
}
