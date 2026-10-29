package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.history.DefaultSessionCorrectionRepository
import com.vipin.fitness.core.data.history.SessionCorrectionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionCorrectionDataModule {
    @Binds abstract fun corrections(impl: DefaultSessionCorrectionRepository): SessionCorrectionRepository
}
