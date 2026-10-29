package com.vipin.fitness.ui

import com.vipin.fitness.core.data.di.SessionCorrectionDataModule
import com.vipin.fitness.core.data.history.SessionChanges
import com.vipin.fitness.core.data.history.SessionCorrectionRepository
import com.vipin.fitness.core.testing.FakeSessionCorrectionRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: corrections in memory, so no UI test reaches the correction endpoints. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SessionCorrectionDataModule::class])
object FakeSessionCorrectionModule {
    @Provides @Singleton fun fakeCorrections(changes: SessionChanges) = FakeSessionCorrectionRepository(changes)
    @Provides fun corrections(f: FakeSessionCorrectionRepository): SessionCorrectionRepository = f
}
