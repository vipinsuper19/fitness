package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.xp.XpEntry
import com.vipin.fitness.core.data.xp.XpLedgerPage
import com.vipin.fitness.core.data.xp.XpLedgerRepository
import com.vipin.fitness.core.data.xp.XpSourceType
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import java.time.Instant

/** A ledger row from wire values, as GET /xp/ledger would return it (unknown types allowed). */
fun testXpEntry(
    id: String,
    amount: Int = 50,
    type: String = "workout_session",
    reason: String = "Workout completed",
    createdAt: String = "2026-10-20T06:30:00Z",
) = XpEntry(
    id = id, sourceType = XpSourceType.from(type), rawSourceType = type, sourceId = "src-$id",
    amount = amount, reason = reason, createdAt = Instant.parse(createdAt),
)

/**
 * In-memory ledger in the shape of QA's PendingXpLedgerSeed (#92): [pages] maps a cursor
 * (null = first page) to (items, nextCursor); a cursor with no page is a network failure.
 * Every call's cursor is recorded in [calls].
 */
class FakeXpLedgerRepository : XpLedgerRepository {
    val pages = mutableMapOf<String?, Pair<List<XpEntry>, String?>>()
    val calls = mutableListOf<String?>()
    /** Optional: a page waits for its cursor's gate before answering. */
    val gates = mutableMapOf<String?, CompletableDeferred<Unit>>()

    override suspend fun ledger(cursor: String?): ApiResult<XpLedgerPage> {
        calls += cursor
        gates[cursor]?.await()
        val page = pages[cursor] ?: return FAKE_NETWORK_FAILURE
        return ApiResult.Success(XpLedgerPage(page.first, page.second))
    }
}
