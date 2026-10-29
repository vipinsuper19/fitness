package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.ExerciseProgress
import com.vipin.fitness.core.data.ExerciseProgressPage
import com.vipin.fitness.core.data.ExerciseProgressSummary
import com.vipin.fitness.core.data.PersonalRecords
import com.vipin.fitness.core.data.StrengthProgressRepository
import com.vipin.fitness.core.data.SummaryBucket
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiResult
import java.time.Instant
import java.time.LocalDate

/** Pages by cursor (missing = offline); [detail] answers the detail call. Calls are recorded. */
class FakeStrengthProgressRepository : StrengthProgressRepository {
    val pages = mutableMapOf<String?, ExerciseProgressPage>()
    var detail: (String, LocalDate, LocalDate, SummaryBucket) -> ApiResult<ExerciseProgress> = { _, _, _, _ -> FAKE_NETWORK_FAILURE }
    val listCalls = mutableListOf<Triple<LocalDate, LocalDate, String?>>()
    val detailCalls = mutableListOf<String>()

    override suspend fun exercises(from: LocalDate, to: LocalDate, cursor: String?): ApiResult<ExerciseProgressPage> {
        listCalls += Triple(from, to, cursor)
        return pages[cursor]?.let { ApiResult.Success(it) } ?: FAKE_NETWORK_FAILURE
    }

    override suspend fun exercise(id: String, from: LocalDate, to: LocalDate, bucket: SummaryBucket): ApiResult<ExerciseProgress> {
        detailCalls += "$id:$from:$to:${bucket.wire}"
        return detail(id, from, to, bucket)
    }
}

fun testExerciseSummary(id: String, name: String = "Exercise $id", pr: PersonalRecords = PersonalRecords(maxWeightKg = 100.0)) =
    ExerciseProgressSummary(id, name, TrackingType.REPS_WEIGHT, 1, Instant.parse("2026-10-20T10:00:00Z"), pr)
