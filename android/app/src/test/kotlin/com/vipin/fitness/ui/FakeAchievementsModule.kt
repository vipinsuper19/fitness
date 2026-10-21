package com.vipin.fitness.ui

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.achievements.AchievementsRepository
import com.vipin.fitness.core.data.achievements.EarnedBadgeTracker
import com.vipin.fitness.core.data.achievements.SeenBadgeStore
import com.vipin.fitness.core.data.di.AchievementsDataModule
import com.vipin.fitness.core.testing.FakeAchievementsRepository
import com.vipin.fitness.core.testing.InMemorySeenBadgeStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Test-only: badges/challenges come from an in-memory fake; seen badges live in memory, not Room. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AchievementsDataModule::class])
abstract class FakeAchievementsModule {
    @Binds abstract fun achievements(f: FakeAchievementsRepository): AchievementsRepository
    @Binds abstract fun seenBadges(f: InMemorySeenBadgeStore): SeenBadgeStore
    @Binds @IntoSet abstract fun earnedBadgesUserState(impl: EarnedBadgeTracker): UserScopedState

    companion object {
        @Provides @Singleton fun fakeAchievements() = FakeAchievementsRepository()
        @Provides @Singleton fun seenStore() = InMemorySeenBadgeStore()
    }
}
