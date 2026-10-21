package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.DefaultProfileRepository
import com.vipin.fitness.core.data.DefaultSessionRepository
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds abstract fun session(impl: DefaultSessionRepository): SessionRepository
    @Binds abstract fun profile(impl: DefaultProfileRepository): ProfileRepository
}
