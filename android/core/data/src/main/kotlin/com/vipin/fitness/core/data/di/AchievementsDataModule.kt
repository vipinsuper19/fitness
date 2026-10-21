package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.achievements.AchievementsRepository
import com.vipin.fitness.core.data.achievements.DefaultAchievementsRepository
import com.vipin.fitness.core.data.achievements.EarnedBadgeTracker
import com.vipin.fitness.core.data.achievements.RoomSeenBadgeStore
import com.vipin.fitness.core.data.achievements.SeenBadgeStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Separate module so badges/challenges don't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AchievementsDataModule {
    @Binds abstract fun achievements(impl: DefaultAchievementsRepository): AchievementsRepository
    @Binds abstract fun seenBadges(impl: RoomSeenBadgeStore): SeenBadgeStore
    @Binds @IntoSet abstract fun earnedBadgesUserState(impl: EarnedBadgeTracker): UserScopedState
}
