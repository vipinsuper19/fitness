package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.ProgressionApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProgressionNetworkModule {
    @Provides @Singleton
    fun progressionApi(retrofit: Retrofit): ProgressionApi = retrofit.create(ProgressionApi::class.java)
}
