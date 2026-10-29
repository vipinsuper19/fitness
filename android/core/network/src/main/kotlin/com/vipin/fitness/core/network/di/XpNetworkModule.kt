package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.XpLedgerApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so the XP ledger doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object XpNetworkModule {
    @Provides @Singleton
    fun xpLedgerApi(retrofit: Retrofit): XpLedgerApi = retrofit.create(XpLedgerApi::class.java)
}
