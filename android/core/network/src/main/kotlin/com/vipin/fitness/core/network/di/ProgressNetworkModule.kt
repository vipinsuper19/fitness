package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.ProgressApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so progress doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object ProgressNetworkModule {
    @Provides @Singleton
    fun progressApi(retrofit: Retrofit): ProgressApi = retrofit.create(ProgressApi::class.java)
}
