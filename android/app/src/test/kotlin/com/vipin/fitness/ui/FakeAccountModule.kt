package com.vipin.fitness.ui

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Test-only binding of the PROPOSED AccountRepository (see ProposedAccountContract.kt). */
@Module
@InstallIn(SingletonComponent::class)
abstract class FakeAccountModule {
    @Binds abstract fun account(f: FakeAccountRepository): AccountRepository

    companion object {
        @Provides @Singleton fun fakeAccount() = FakeAccountRepository()
    }
}
