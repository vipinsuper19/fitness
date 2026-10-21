package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.NutritionApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Separate module so nutrition doesn't edit the shared NetworkModule. */
@Module
@InstallIn(SingletonComponent::class)
object NutritionNetworkModule {
    @Provides @Singleton
    fun nutritionApi(retrofit: Retrofit): NutritionApi = retrofit.create(NutritionApi::class.java)
}
