package com.vipin.fitness.core.data.achievements

import com.vipin.fitness.core.network.model.BadgeDto
import com.vipin.fitness.core.network.model.BadgeListDto
import com.vipin.fitness.core.network.model.ChallengeDto
import com.vipin.fitness.core.network.model.ChallengeTemplateDto
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Fallback when a challenge's timezone isn't a zone this device knows (the profile default). */
internal val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

internal fun BadgeDto.toDomain() = Badge(
    code = code,
    title = title,
    description = description,
    family = BadgeFamily.fromWire(family),
    bonusXp = bonusXp,
    earnedAt = earnedAt?.let(Instant::parse),
    progress = progress?.let { BadgeProgress(it.current, it.target) },
)

internal fun BadgeListDto.toDomain() = BadgeList(items.map { it.toDomain() }, earnedCount)

internal fun ChallengeTemplateDto.toDomain() = ChallengeTemplate(
    code = code,
    type = ChallengeType.fromWire(type),
    title = title,
    description = description,
    metric = metric,
    unit = unit,
    options = targets.mapIndexed { i, t -> TargetOption(t, deadlineDays?.getOrNull(i)) },
    bonusXp = bonusXp,
)

internal fun ChallengeDto.toDomain() = Challenge(
    id = id,
    templateCode = templateCode,
    type = ChallengeType.fromWire(type),
    title = title,
    target = target,
    progress = progress,
    unit = unit,
    windowStart = LocalDate.parse(windowStart),
    windowEnd = windowEnd?.let(LocalDate::parse),
    timezone = runCatching { ZoneId.of(timezone) }.getOrDefault(DEFAULT_ZONE),
    waterGoalMl = waterGoalMl,
    status = ChallengeStatus.fromWire(status),
    joinedAt = Instant.parse(joinedAt),
    completedAt = completedAt?.let(Instant::parse),
    leftAt = leftAt?.let(Instant::parse),
    xpAwarded = xpAwarded,
)
