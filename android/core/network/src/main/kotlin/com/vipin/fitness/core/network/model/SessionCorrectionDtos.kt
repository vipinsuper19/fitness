package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** PATCH /workout-sessions/{id}/completion. Omitted fields are unchanged. */
@Serializable
data class SessionCompletionUpdateDto(
    val completedAt: String? = null,
    val perceivedDifficulty: Int? = null,
    val notes: String? = null,
)

@Serializable
data class SessionCorrectionResultDto(
    val session: SessionDetailDto,
    val xpDelta: Int,
    val xpBalance: XpBalanceDto,
    /** Optional and additive (badges spec §8). */
    val newBadges: List<BadgeDto>? = null,
)
