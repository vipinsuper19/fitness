package com.vipin.fitness.ui

import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import com.vipin.fitness.core.data.sync.SyncScheduler
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.data.di.DataModule
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.datastore.di.DataStoreModule
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.TokenStore
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeSyncScheduler
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.FakeWorkoutSessionRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/** Test-only: repositories are in-memory fakes, so no UI test can reach a real server. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataModule::class])
abstract class FakeDataModule {
    @Binds abstract fun session(f: FakeSessionRepository): SessionRepository
    @Binds abstract fun profile(f: FakeProfileRepository): ProfileRepository
    @Binds abstract fun workout(f: FakeWorkoutRepository): WorkoutRepository
    @Binds abstract fun workoutSessions(f: FakeWorkoutSessionRepository): WorkoutSessionRepository
    @Binds abstract fun sync(f: FakeSyncScheduler): SyncScheduler
    @Multibinds abstract fun userScopedState(): Set<UserScopedState>

    companion object {
        @Provides @Singleton fun fakeSession() = FakeSessionRepository()
        @Provides @Singleton fun fakeProfile() = FakeProfileRepository()
        @Provides @Singleton fun fakeWorkout() = FakeWorkoutRepository()
        @Provides @Singleton fun fakeWorkoutSessions() = FakeWorkoutSessionRepository()
        @Provides @Singleton fun fakeSync() = FakeSyncScheduler()
    }
}

/** Test-only: no Keystore / disk DataStore; tokens never exist. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [DataStoreModule::class])
abstract class FakeDataStoreModule {
    @Binds abstract fun flags(f: FakeProfileFlagsCache): ProfileFlagsCache

    companion object {
        @Provides @Singleton fun fakeFlags() = FakeProfileFlagsCache()
        @Provides @Singleton fun tokens(): TokenStore = object : TokenStore {
            override fun get(): AuthTokens? = null
            override fun save(tokens: AuthTokens) = Unit
            override fun clear() = Unit
        }
    }
}
