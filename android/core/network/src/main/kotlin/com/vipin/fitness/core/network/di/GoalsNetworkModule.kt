package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.GoalsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so goals don't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object GoalsNetworkModule {
    @Provides @Singleton
    fun goalsApi(retrofit: Retrofit): GoalsApi = retrofit.create(GoalsApi::class.java)
}
