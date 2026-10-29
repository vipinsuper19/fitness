package com.vipin.fitness.core.data

import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.StrengthProgressApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.ExerciseProgressDto
import com.vipin.fitness.core.network.model.ExerciseProgressPageDto
import com.vipin.fitness.core.network.model.PersonalRecordsDto
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** All-time records (stored units: kg, s, m). Each value is null when no set qualifies, and so is its time. */
data class PersonalRecords(
    val maxWeightKg: Double? = null,
    val maxWeightKgAchievedAt: Instant? = null,
    val maxReps: Int? = null,
    val maxRepsAchievedAt: Instant? = null,
    val best1RmKg: Double? = null,
    val best1RmKgAchievedAt: Instant? = null,
    val maxDurationSec: Int? = null,
    val maxDurationSecAchievedAt: Instant? = null,
    val maxDistanceM: Double? = null,
    val maxDistanceMAchievedAt: Instant? = null,
) {
    val isEmpty get() = maxWeightKg == null && maxReps == null && best1RmKg == null && maxDurationSec == null && maxDistanceM == null
}

data class ExerciseProgressSummary(
    val exerciseId: String,
    val name: String,
    val trackingType: TrackingType,
    val sessions: Int,
    val lastPerformedAt: Instant?,
    val pr: PersonalRecords,
)

data class ExerciseProgressPage(val items: List<ExerciseProgressSummary>, val nextCursor: String?)

data class ExerciseProgressPoint(
    val period: LocalDate,
    val sessions: Int,
    val sets: Int,
    val volumeKg: Double? = null,
    val totalReps: Int? = null,
    val topSetWeightKg: Double? = null,
    val best1RmKg: Double? = null,
    val totalDurationSec: Int? = null,
    val totalDistanceM: Double? = null,
    val maxSetDurationSec: Int? = null,
    val maxSetDistanceM: Double? = null,
) {
    val empty get() = sets == 0
}

data class ExerciseProgress(
    val exerciseId: String,
    val name: String,
    val trackingType: TrackingType,
    val from: LocalDate,
    val to: LocalDate,
    val bucket: SummaryBucket,
    val pr: PersonalRecords,
    val points: List<ExerciseProgressPoint>,
)

private fun instant(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }
private fun tracking(s: String) = TrackingType.entries.firstOrNull { it.wire == s } ?: TrackingType.REPS_WEIGHT

fun PersonalRecordsDto.toDomain() = PersonalRecords(
    maxWeightKg, instant(maxWeightKgAchievedAt), maxReps, instant(maxRepsAchievedAt),
    best1RmKg, instant(best1RmKgAchievedAt), maxDurationSec, instant(maxDurationSecAchievedAt),
    maxDistanceM, instant(maxDistanceMAchievedAt),
)

fun ExerciseProgressPageDto.toDomain() = ExerciseProgressPage(
    items = items.map { ExerciseProgressSummary(it.exerciseId, it.name, tracking(it.trackingType), it.sessions, instant(it.lastPerformedAt), it.pr.toDomain()) },
    nextCursor = nextCursor,
)

fun ExerciseProgressDto.toDomain() = ExerciseProgress(
    exerciseId = exerciseId,
    name = name,
    trackingType = tracking(trackingType),
    from = LocalDate.parse(from),
    to = LocalDate.parse(to),
    bucket = SummaryBucket.entries.firstOrNull { it.wire == bucket } ?: SummaryBucket.DAY,
    pr = pr.toDomain(),
    points = points.map {
        ExerciseProgressPoint(
            LocalDate.parse(it.period), it.sessions, it.sets, it.volumeKg, it.totalReps, it.topSetWeightKg, it.best1RmKg,
            it.totalDurationSec, it.totalDistanceM, it.maxSetDurationSec, it.maxSetDistanceM,
        )
    },
)

interface StrengthProgressRepository {
    suspend fun exercises(from: LocalDate, to: LocalDate, cursor: String?): ApiResult<ExerciseProgressPage>
    suspend fun exercise(id: String, from: LocalDate, to: LocalDate, bucket: SummaryBucket): ApiResult<ExerciseProgress>
}

@Singleton
class DefaultStrengthProgressRepository @Inject constructor(
    private val api: StrengthProgressApi,
    private val json: Json,
) : StrengthProgressRepository {
    override suspend fun exercises(from: LocalDate, to: LocalDate, cursor: String?) =
        apiCall(json) { api.exercises(from.toString(), to.toString(), cursor).toDomain() }

    override suspend fun exercise(id: String, from: LocalDate, to: LocalDate, bucket: SummaryBucket) =
        apiCall(json) { api.exercise(id, from.toString(), to.toString(), bucket.wire).toDomain() }
}
