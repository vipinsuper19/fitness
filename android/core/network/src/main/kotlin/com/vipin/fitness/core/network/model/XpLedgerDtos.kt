package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `XpTransaction`. [sourceType] stays a string: clients must accept unknown values. */
@Serializable
data class XpTransactionDto(
    val id: String,
    val sourceType: String,
    val sourceId: String,
    val amount: Int,
    val reason: String = "",
    val createdAt: String,
)

@Serializable
data class XpLedgerPageDto(val items: List<XpTransactionDto> = emptyList(), val nextCursor: String? = null)
