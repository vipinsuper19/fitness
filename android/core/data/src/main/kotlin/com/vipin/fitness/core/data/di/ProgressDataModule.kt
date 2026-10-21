package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.CachedUnitPreference
import com.vipin.fitness.core.data.DefaultHomeRepository
import com.vipin.fitness.core.data.DefaultProgressRepository
import com.vipin.fitness.core.data.HomeRepository
import com.vipin.fitness.core.data.ProgressRepository
import com.vipin.fitness.core.data.UnitPreference
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Separate module so progress and Home don't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProgressDataModule {
    @Binds abstract fun progress(impl: DefaultProgressRepository): ProgressRepository
    @Binds abstract fun home(impl: DefaultHomeRepository): HomeRepository
    @Binds abstract fun units(impl: CachedUnitPreference): UnitPreference
}
