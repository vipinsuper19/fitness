package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.NotificationPreferencesApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationPreferencesNetworkModule {
    @Provides @Singleton
    fun notificationPreferencesApi(retrofit: Retrofit): NotificationPreferencesApi = retrofit.create(NotificationPreferencesApi::class.java)
}
