package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.PlanManagementApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so plan management doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object PlanManagementNetworkModule {
    @Provides @Singleton
    fun planManagementApi(retrofit: Retrofit): PlanManagementApi = retrofit.create(PlanManagementApi::class.java)
}
