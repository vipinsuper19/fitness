package com.vipin.fitness.ui

import com.vipin.fitness.core.data.ConsentRepository
import com.vipin.fitness.core.data.di.ConsentDataModule
import com.vipin.fitness.core.testing.FakeConsentRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: consent already given, so existing flows go straight to the onboarding steps. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ConsentDataModule::class])
object FakeConsentModule {
    @Provides @Singleton fun fakeConsent() = FakeConsentRepository(consented = true)
    @Provides fun consent(f: FakeConsentRepository): ConsentRepository = f
}
