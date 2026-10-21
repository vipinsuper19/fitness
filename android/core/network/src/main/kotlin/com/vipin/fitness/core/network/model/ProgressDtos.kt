package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `BodyMeasurement`. Metric; a null measurement was not taken. */
@Serializable
data class BodyMeasurementDto(
    val id: String,
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPct: Double? = null,
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val hipCm: Double? = null,
    val armCm: Double? = null,
    val thighCm: Double? = null,
    val createdAt: String,
)

@Serializable
data class BodyMeasurementPageDto(val items: List<BodyMeasurementDto>, val nextCursor: String? = null)

/** Contract `ProgressPoint`: `weightKg` / `energyKcal` null means no data that day, never 0. */
@Serializable
data class ProgressPointDto(
    val date: String,
    val weightKg: Double? = null,
    val workoutsCompleted: Int = 0,
    val activeMinutes: Int = 0,
    val energyKcal: Double? = null,
    val xpEarned: Int = 0,
)

@Serializable
data class ProgressSummaryDto(val from: String, val to: String, val points: List<ProgressPointDto>, val streakDays: Int)

/** Contract `XpBalance`. */
@Serializable
data class XpBalanceDto(val total: Int, val level: Int, val xpIntoLevel: Int, val xpForNextLevel: Int)

