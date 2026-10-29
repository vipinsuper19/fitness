package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.progression.DefaultProgressionRepository
import com.vipin.fitness.core.data.progression.ProgressionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ProgressionDataModule {
    @Binds abstract fun progression(impl: DefaultProgressionRepository): ProgressionRepository
    @Binds @IntoSet abstract fun progressionUserState(impl: DefaultProgressionRepository): UserScopedState
}
