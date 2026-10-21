package com.vipin.fitness.core.data.achievements

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Badges and challenges (spec docs/specs/badges-challenges.md, contract #43). Titles, rules and
 * units come from the server; the app ships no rules. Every server enum has an UNKNOWN value: a
 * newer server may send values this build doesn't know, and that must never crash.
 */
enum class BadgeFamily(val wire: String) {
    WORKOUT("workout"), STREAK("streak"), NUTRITION("nutrition"), HYDRATION("hydration"),
    MEASUREMENT("measurement"), LEVEL("level"), UNKNOWN("");

    companion object {
        fun fromWire(v: String?): BadgeFamily = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN
    }
}

enum class ChallengeType(val wire: String) {
    WEEKLY("weekly"), PERSONAL("personal"), UNKNOWN("");

    companion object {
        fun fromWire(v: String?): ChallengeType = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN
    }
}

enum class ChallengeStatus(val wire: String) {
    ACTIVE("active"), COMPLETED("completed"), EXPIRED("expired"), LEFT("left"), UNKNOWN("");

    companion object {
        fun fromWire(v: String?): ChallengeStatus = entries.firstOrNull { it != UNKNOWN && it.wire == v } ?: UNKNOWN
    }
}

data class BadgeProgress(val current: Int, val target: Int) {
    /** 0..1 for a progress bar. */
    val fraction: Float get() = if (target <= 0) 0f else (current.toFloat() / target).coerceIn(0f, 1f)
}

data class Badge(
    val code: String,
    val title: String,
    val description: String,
    val family: BadgeFamily,
    val bonusXp: Int,
    /** null = not earned yet. */
    val earnedAt: Instant?,
    val progress: BadgeProgress?,
) {
    val earned: Boolean get() = earnedAt != null
}

data class BadgeList(val items: List<Badge>, val earnedCount: Int)

/** One allowed target of a template; [deadlineDays] only for personal templates. */
data class TargetOption(val target: Int, val deadlineDays: Int?)

data class ChallengeTemplate(
    val code: String,
    val type: ChallengeType,
    val title: String,
    val description: String,
    val metric: String,
    val unit: String,
    val options: List<TargetOption>,
    val bonusXp: Int,
)

data class Challenge(
    val id: String,
    val templateCode: String,
    val type: ChallengeType,
    val title: String,
    val target: Int,
    val progress: Int,
    val unit: String,
    val windowStart: LocalDate,
    /** Inclusive; null = no deadline. */
    val windowEnd: LocalDate?,
    /** Captured at join; the window is in this zone, not the device's. */
    val timezone: ZoneId,
    val waterGoalMl: Int?,
    val status: ChallengeStatus,
    val joinedAt: Instant,
    val completedAt: Instant?,
    val leftAt: Instant?,
    val xpAwarded: Int,
) {
    val fraction: Float get() = if (target <= 0) 0f else (progress.toFloat() / target).coerceIn(0f, 1f)

    /** Days left including today, in the challenge's own timezone; null without a deadline. Never negative. */
    fun daysLeft(now: Instant): Int? = windowEnd?.let { end ->
        (ChronoUnit.DAYS.between(now.atZone(timezone).toLocalDate(), end) + 1).coerceAtLeast(0).toInt()
    }
}

data class ChallengePage(val items: List<Challenge>, val nextCursor: String?)
