package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

// Contract #43 (badges & challenges). Enum-typed fields (family, type, status) stay plain strings
// here: the contract says clients must accept unknown values, so they are mapped in core:data.

@Serializable
data class BadgeProgressDto(val current: Int, val target: Int)

/** Contract `Badge`. */
@Serializable
data class BadgeDto(
    val code: String,
    val title: String,
    val description: String = "",
    val family: String = "",
    val bonusXp: Int = 0,
    val earnedAt: String? = null,
    val progress: BadgeProgressDto? = null,
)

/** Contract `BadgeList`. */
@Serializable
data class BadgeListDto(val items: List<BadgeDto>, val earnedCount: Int = 0)

/** Contract `ChallengeTemplate`. */
@Serializable
data class ChallengeTemplateDto(
    val code: String,
    val type: String,
    val title: String,
    val description: String = "",
    val metric: String = "",
    val unit: String = "",
    val targets: List<Int>,
    val deadlineDays: List<Int>? = null,
    val bonusXp: Int = 0,
)

@Serializable
data class ChallengeTemplateListDto(val items: List<ChallengeTemplateDto>)

/** Contract `Challenge`. */
@Serializable
data class ChallengeDto(
    val id: String,
    val templateCode: String,
    val type: String,
    val title: String,
    val target: Int,
    val progress: Int,
    val unit: String = "",
    val windowStart: String,
    val windowEnd: String? = null,
    val timezone: String = "Asia/Kolkata",
    val waterGoalMl: Int? = null,
    val status: String,
    val joinedAt: String,
    val completedAt: String? = null,
    val leftAt: String? = null,
    val xpAwarded: Int = 0,
    /** Local join date in [timezone] (added to the contract by #49; required there, lenient here). */
    val joinDate: String? = null,
)

@Serializable
data class ChallengePageDto(val items: List<ChallengeDto>, val nextCursor: String? = null)

/** Contract `ChallengeJoinRequest`. */
@Serializable
data class ChallengeJoinRequestDto(val templateCode: String, val target: Int)
