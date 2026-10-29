package com.vipin.fitness.ui

import com.vipin.fitness.core.data.di.ProgressionDataModule
import com.vipin.fitness.core.data.progression.ProgressionRepository
import com.vipin.fitness.core.testing.FakeProgressionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: progression suggestions are scripted in memory, so no UI test reaches a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ProgressionDataModule::class])
object FakeProgressionModule {
    @Provides @Singleton fun fakeProgression() = FakeProgressionRepository()
    @Provides fun progression(f: FakeProgressionRepository): ProgressionRepository = f
}
