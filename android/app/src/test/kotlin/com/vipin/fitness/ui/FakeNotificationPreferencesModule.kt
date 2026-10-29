package com.vipin.fitness.ui

import com.vipin.fitness.core.data.NotificationPreferencesRepository
import com.vipin.fitness.core.data.di.NotificationPreferencesDataModule
import com.vipin.fitness.core.testing.FakeNotificationPreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: notification preferences never reach a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NotificationPreferencesDataModule::class])
abstract class FakeNotificationPreferencesModule {
    @Binds abstract fun prefs(f: FakeNotificationPreferencesRepository): NotificationPreferencesRepository
    companion object {
        @Provides @Singleton fun fakePrefs() = FakeNotificationPreferencesRepository()
    }
}
