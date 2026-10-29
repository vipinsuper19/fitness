package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultStrengthProgressRepository
import com.vipin.fitness.core.data.StrengthProgressRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class StrengthProgressDataModule {
    @Binds abstract fun strength(impl: DefaultStrengthProgressRepository): StrengthProgressRepository
}
