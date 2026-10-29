package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultGoalsRepository
import com.vipin.fitness.core.data.GoalsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Separate module so goals don't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class GoalsDataModule {
    @Binds abstract fun goals(impl: DefaultGoalsRepository): GoalsRepository
    @Binds @IntoSet abstract fun profileZoneUserState(impl: com.vipin.fitness.core.data.ProfileZone): com.vipin.fitness.core.data.UserScopedState
}
