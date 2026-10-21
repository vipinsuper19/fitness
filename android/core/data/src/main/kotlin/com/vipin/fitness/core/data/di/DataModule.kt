package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultProfileRepository
import com.vipin.fitness.core.data.DefaultSessionRepository
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.session.DefaultWorkoutSessionRepository
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import com.vipin.fitness.core.data.sync.SyncScheduler
import com.vipin.fitness.core.data.sync.WorkManagerSyncScheduler
import com.vipin.fitness.core.data.workout.DefaultWorkoutRepository
import com.vipin.fitness.core.data.workout.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun session(impl: DefaultSessionRepository): SessionRepository
    @Binds abstract fun profile(impl: DefaultProfileRepository): ProfileRepository
    @Binds abstract fun workout(impl: DefaultWorkoutRepository): WorkoutRepository

    /** May be empty; features add their own with @Binds @IntoSet. */
    @Multibinds abstract fun userScopedState(): Set<UserScopedState>
    @Binds @IntoSet abstract fun workoutUserState(impl: DefaultWorkoutRepository): UserScopedState

    @Binds abstract fun workoutSessions(impl: DefaultWorkoutSessionRepository): WorkoutSessionRepository
    @Binds @IntoSet abstract fun workoutSessionsUserState(impl: DefaultWorkoutSessionRepository): UserScopedState
    @Binds abstract fun syncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler
}
