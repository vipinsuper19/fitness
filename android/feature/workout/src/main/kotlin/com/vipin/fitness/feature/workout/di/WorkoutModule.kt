package com.vipin.fitness.feature.workout.di

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.feature.workout.PlanBuilderStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class WorkoutModule {
    @Binds @IntoSet abstract fun planBuilderUserState(impl: PlanBuilderStore): UserScopedState
}
