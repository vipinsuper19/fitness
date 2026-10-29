package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultNotificationPreferencesRepository
import com.vipin.fitness.core.data.NotificationPreferencesRepository
import com.vipin.fitness.core.data.UserScopedState
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class NotificationPreferencesDataModule {
    @Binds abstract fun notificationPreferences(impl: DefaultNotificationPreferencesRepository): NotificationPreferencesRepository
    @Binds @IntoSet abstract fun notificationPreferencesUserState(impl: DefaultNotificationPreferencesRepository): UserScopedState
}
