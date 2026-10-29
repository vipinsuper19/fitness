package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.xp.XpEntry
import com.vipin.fitness.core.data.xp.XpLedgerPage
import com.vipin.fitness.core.data.xp.XpLedgerRepository
import com.vipin.fitness.core.data.xp.XpSourceType
import com.vipin.fitness.core.network.ApiResult
import java.time.Instant

fun testXpEntry(id: String, amount: Int = 50, type: String = "workout_session", reason: String = "Workout completed") = XpEntry(
    id = id, sourceType = XpSourceType.from(type), rawSourceType = type, sourceId = "src-$id",
    amount = amount, reason = reason, createdAt = Instant.parse("2026-10-20T06:30:00Z"),
)

/** Pages come from [pages] keyed by cursor (null = first page). */
class FakeXpLedgerRepository : XpLedgerRepository {
    var pages: (String?) -> ApiResult<XpLedgerPage> = { ApiResult.Success(XpLedgerPage(emptyList(), null)) }
    val calls = mutableListOf<String?>()

    override suspend fun ledger(cursor: String?): ApiResult<XpLedgerPage> {
        calls += cursor
        return pages(cursor)
    }
}
