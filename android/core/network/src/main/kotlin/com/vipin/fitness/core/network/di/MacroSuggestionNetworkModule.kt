package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.MacroSuggestionApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so the suggestion doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object MacroSuggestionNetworkModule {
    @Provides @Singleton
    fun macroSuggestionApi(retrofit: Retrofit): MacroSuggestionApi = retrofit.create(MacroSuggestionApi::class.java)
}
