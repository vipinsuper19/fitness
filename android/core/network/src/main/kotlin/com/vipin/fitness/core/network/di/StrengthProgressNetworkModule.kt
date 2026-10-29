package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.StrengthProgressApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so strength progress doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object StrengthProgressNetworkModule {
    @Provides @Singleton
    fun strengthProgressApi(retrofit: Retrofit): StrengthProgressApi = retrofit.create(StrengthProgressApi::class.java)
}
