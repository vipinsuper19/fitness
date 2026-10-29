package com.vipin.fitness.ui

import com.vipin.fitness.core.data.StrengthProgressRepository
import com.vipin.fitness.core.data.di.StrengthProgressDataModule
import com.vipin.fitness.core.testing.FakeStrengthProgressRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: strength progress (#114) never reaches a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StrengthProgressDataModule::class])
abstract class FakeStrengthProgressModule {
    @Binds abstract fun strength(f: FakeStrengthProgressRepository): StrengthProgressRepository
    companion object {
        @Provides @Singleton fun fakeStrength() = FakeStrengthProgressRepository()
    }
}
