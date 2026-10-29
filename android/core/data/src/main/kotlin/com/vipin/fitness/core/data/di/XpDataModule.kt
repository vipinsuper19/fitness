package com.vipin.fitness.core.data.di

import com.vipin.fitness.core.data.xp.DefaultXpLedgerRepository
import com.vipin.fitness.core.data.xp.XpLedgerRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Separate module so the XP ledger doesn't edit the shared DataModule. */
@Module
@InstallIn(SingletonComponent::class)
abstract class XpDataModule {
    @Binds abstract fun xpLedger(impl: DefaultXpLedgerRepository): XpLedgerRepository
}
