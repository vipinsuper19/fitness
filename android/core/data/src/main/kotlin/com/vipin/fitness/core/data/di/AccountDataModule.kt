package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.AccountRepository
import com.vipin.fitness.core.data.DefaultAccountRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Separate module so account deletion doesn't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AccountDataModule {
    @Binds abstract fun account(impl: DefaultAccountRepository): AccountRepository
}
