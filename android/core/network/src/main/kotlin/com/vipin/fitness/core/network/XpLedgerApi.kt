package com.vipin.fitness.core.network

import com.vipin.fitness.core.network.model.XpLedgerPageDto
import retrofit2.http.GET
import retrofit2.http.Query

/** GET /xp/ledger (contract tag `xp`), read-only, newest first. */
interface XpLedgerApi {
    @GET("xp/ledger")
    suspend fun ledger(@Query("cursor") cursor: String? = null, @Query("limit") limit: Int? = null): XpLedgerPageDto
}
