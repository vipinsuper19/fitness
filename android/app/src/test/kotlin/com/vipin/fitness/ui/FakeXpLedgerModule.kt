package com.vipin.fitness.ui

import com.vipin.fitness.core.data.di.XpDataModule
import com.vipin.fitness.core.data.xp.XpLedgerRepository
import com.vipin.fitness.core.testing.FakeXpLedgerRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: the XP ledger from memory, so no UI test reaches GET /xp/ledger. */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [XpDataModule::class])
object FakeXpLedgerModule {
    @Provides @Singleton fun fakeXpLedger() = FakeXpLedgerRepository()
    @Provides fun xpLedger(f: FakeXpLedgerRepository): XpLedgerRepository = f
}
