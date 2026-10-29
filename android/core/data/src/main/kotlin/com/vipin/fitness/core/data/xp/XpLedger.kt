package com.vipin.fitness.core.data.xp

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.XpLedgerApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.XpTransactionDto
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Contract `XpSourceType`. Anything this build doesn't know reads as [UNKNOWN], never a crash. */
enum class XpSourceType(val wire: String?) {
    WORKOUT_SESSION("workout_session"),
    ADJUSTMENT("adjustment"),
    BADGE("badge"),
    CHALLENGE("challenge"),
    /** Server-side change from correcting or discarding a completed session; can be negative. */
    SESSION_CORRECTION("session_correction"),
    UNKNOWN(null),
    ;

    companion object {
        fun from(wire: String): XpSourceType = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

data class XpEntry(
    val id: String,
    val sourceType: XpSourceType,
    /** The wire value as sent, kept for unknown types. */
    val rawSourceType: String,
    val sourceId: String,
    /** Negative for reversals and downward corrections. */
    val amount: Int,
    val reason: String,
    val createdAt: Instant?,
)

data class XpLedgerPage(val items: List<XpEntry>, val nextCursor: String?)

fun XpTransactionDto.toDomain() = XpEntry(
    id = id,
    sourceType = XpSourceType.from(sourceType),
    rawSourceType = sourceType,
    sourceId = sourceId,
    amount = amount,
    reason = reason,
    createdAt = runCatching { Instant.parse(createdAt) }.getOrNull(),
)

interface XpLedgerRepository {
    suspend fun ledger(cursor: String? = null): ApiResult<XpLedgerPage>
}

@Singleton
class DefaultXpLedgerRepository @Inject constructor(
    private val api: XpLedgerApi,
    private val json: Json,
) : XpLedgerRepository {
    override suspend fun ledger(cursor: String?) = apiCall(json) {
        val page = api.ledger(cursor = cursor, limit = PAGE_SIZE)
        XpLedgerPage(page.items.map { it.toDomain() }, page.nextCursor)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
