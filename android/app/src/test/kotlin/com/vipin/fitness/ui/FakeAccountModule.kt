package com.vipin.fitness.ui

import com.vipin.fitness.core.data.AccountRepository
import com.vipin.fitness.core.data.di.AccountDataModule
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeAccountRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton

/** Test-only: the account API is an in-memory fake (no server). */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [AccountDataModule::class])
abstract class FakeAccountModule {
    @Binds abstract fun account(f: FakeAccountRepository): AccountRepository

    companion object {
        @Provides @Singleton fun fakeAccount() = FakeAccountRepository()
    }
}

/** Contract error responses used by DeleteExportUiTest. */
object AccountResults {
    val WRONG_PASSWORD = ApiResult.Failure(ApiError.Http(403, "INVALID_PASSWORD", "Wrong password", "req-1"))
    val SERVER_ERROR = ApiResult.Failure(ApiError.Http(500, "INTERNAL", "Internal error", "req-2"))
    val RATE_LIMITED = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "Too many requests", "req-3"))
    /** Prod behaviour: a same-key retry after a successful (but lost) 204 → 401, tokens already revoked. */
    val UNAUTHENTICATED = ApiResult.Failure(ApiError.Http(401, "UNAUTHENTICATED", "Unauthenticated", "req-4"))
    val OFFLINE = ApiResult.Failure(ApiError.Network)
}
