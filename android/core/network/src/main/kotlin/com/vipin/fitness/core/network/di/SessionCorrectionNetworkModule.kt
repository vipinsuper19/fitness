package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.SessionCorrectionApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so history doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object SessionCorrectionNetworkModule {
    @Provides @Singleton
    fun sessionCorrectionApi(retrofit: Retrofit): SessionCorrectionApi = retrofit.create(SessionCorrectionApi::class.java)
}
