package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.AchievementsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so badges/challenges don't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object AchievementsNetworkModule {
    @Provides @Singleton
    fun achievementsApi(retrofit: Retrofit): AchievementsApi = retrofit.create(AchievementsApi::class.java)
}
