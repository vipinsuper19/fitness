package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `PersonalRecords`: all-time; every value and its `…AchievedAt` is nullable. */
@Serializable
data class PersonalRecordsDto(
    val maxWeightKg: Double? = null,
    val maxWeightKgAchievedAt: String? = null,
    val maxReps: Int? = null,
    val maxRepsAchievedAt: String? = null,
    val best1RmKg: Double? = null,
    val best1RmKgAchievedAt: String? = null,
    val maxDurationSec: Int? = null,
    val maxDurationSecAchievedAt: String? = null,
    val maxDistanceM: Double? = null,
    val maxDistanceMAchievedAt: String? = null,
)

@Serializable
data class ExerciseProgressSummaryDto(
    val exerciseId: String,
    val name: String,
    val trackingType: String,
    val sessions: Int,
    val lastPerformedAt: String,
    val pr: PersonalRecordsDto = PersonalRecordsDto(),
)

@Serializable
data class ExerciseProgressPageDto(
    val from: String,
    val to: String,
    val timezone: String? = null,
    val items: List<ExerciseProgressSummaryDto>,
    val nextCursor: String? = null,
)

/** Contract `ExerciseProgressPoint`: null = no data, never 0. */
@Serializable
data class ExerciseProgressPointDto(
    val period: String,
    val sessions: Int = 0,
    val sets: Int = 0,
    val volumeKg: Double? = null,
    val totalReps: Int? = null,
    val topSetWeightKg: Double? = null,
    val best1RmKg: Double? = null,
    val totalDurationSec: Int? = null,
    val totalDistanceM: Double? = null,
    val maxSetDurationSec: Int? = null,
    val maxSetDistanceM: Double? = null,
)

@Serializable
data class ExerciseProgressDto(
    val exerciseId: String,
    val name: String,
    val trackingType: String,
    val from: String,
    val to: String,
    val bucket: String,
    val timezone: String? = null,
    val pr: PersonalRecordsDto = PersonalRecordsDto(),
    val points: List<ExerciseProgressPointDto> = emptyList(),
)
