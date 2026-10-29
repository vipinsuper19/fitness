package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.NutritionSummaryApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so the summary doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object NutritionSummaryNetworkModule {
    @Provides @Singleton
    fun nutritionSummaryApi(retrofit: Retrofit): NutritionSummaryApi = retrofit.create(NutritionSummaryApi::class.java)
}
