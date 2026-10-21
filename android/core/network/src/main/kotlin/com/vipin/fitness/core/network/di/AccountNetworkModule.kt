package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.AccountApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so account deletion doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object AccountNetworkModule {
    @Provides @Singleton
    fun accountApi(retrofit: Retrofit): AccountApi = retrofit.create(AccountApi::class.java)
}
