package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionSummaryApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.NutritionSummaryDto
import com.vipin.fitness.core.network.model.NutritionTargetsDto
import kotlinx.serialization.json.Json
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

enum class SummaryBucket(val wire: String) { DAY("day"), WEEK("week"), MONTH("month") }

data class NutritionTargets(val kcal: Int?, val proteinG: Int?, val carbsG: Int?, val fatG: Int?, val waterMl: Int?)

data class NutritionAdherence(val kcalPct: Double?, val proteinPct: Double?, val carbsPct: Double?, val fatPct: Double?, val waterPct: Double?)

/** One period. Null values mean nothing was logged, never 0. */
data class NutritionSummaryPoint(
    val period: LocalDate,
    val daysInPeriod: Int,
    val daysLogged: Int,
    val avgPerLoggedDay: Nutrients?,
    val waterMl: Int?,
    val waterDaysLogged: Int,
    val avgWaterMlPerLoggedDay: Int?,
    val adherence: NutritionAdherence?,
    /** Targets in effect on the period's last date (#104); null when none, or from an older server. */
    val targets: NutritionTargets? = null,
    /** True when a target changed inside the period; false when unchanged or not reported. */
    val targetsChanged: Boolean = false,
) {
    val empty get() = daysLogged == 0 && waterDaysLogged == 0
}

data class NutritionSummary(
    val from: LocalDate,
    val to: LocalDate,
    val bucket: SummaryBucket,
    val targets: NutritionTargets?,
    val points: List<NutritionSummaryPoint>,
)

fun NutritionSummaryDto.toDomain() = NutritionSummary(
    from = LocalDate.parse(from),
    to = LocalDate.parse(to),
    bucket = SummaryBucket.entries.firstOrNull { it.wire == bucket } ?: SummaryBucket.DAY,
    targets = targets?.toDomain(),
    points = points.map { p ->
        NutritionSummaryPoint(
            period = LocalDate.parse(p.period),
            daysInPeriod = p.daysInPeriod,
            daysLogged = p.daysLogged,
            avgPerLoggedDay = p.avgPerLoggedDay?.toDomain(),
            waterMl = p.waterMl,
            waterDaysLogged = p.waterDaysLogged,
            avgWaterMlPerLoggedDay = p.avgWaterMlPerLoggedDay,
            adherence = p.adherence?.let { NutritionAdherence(it.kcalPct, it.proteinPct, it.carbsPct, it.fatPct, it.waterPct) },
            targets = p.targets?.toDomain(),
            targetsChanged = p.targetsChanged ?: false,
        )
    },
)

fun NutritionTargetsDto.toDomain() = NutritionTargets(kcal, proteinG, carbsG, fatG, waterMl)

interface NutritionSummaryRepository {
    suspend fun summary(from: LocalDate, to: LocalDate, bucket: SummaryBucket): ApiResult<NutritionSummary>
}

@Singleton
class DefaultNutritionSummaryRepository @Inject constructor(
    private val api: NutritionSummaryApi,
    private val json: Json,
) : NutritionSummaryRepository {
    override suspend fun summary(from: LocalDate, to: LocalDate, bucket: SummaryBucket) =
        apiCall(json) { api.summary(from.toString(), to.toString(), bucket.wire).toDomain() }
}
