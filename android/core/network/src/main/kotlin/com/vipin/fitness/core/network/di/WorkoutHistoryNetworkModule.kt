package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.WorkoutHistoryApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so history doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object WorkoutHistoryNetworkModule {
    @Provides @Singleton
    fun workoutHistoryApi(retrofit: Retrofit): WorkoutHistoryApi = retrofit.create(WorkoutHistoryApi::class.java)
}
