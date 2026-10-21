package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.model.ActivePlanDto
import com.vipin.fitness.core.network.model.BodyMeasurementDto
import com.vipin.fitness.core.network.model.ProgressSummaryDto
import com.vipin.fitness.core.network.model.XpBalanceDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/** What a measurement measures, so the UI picks kg/lb, cm/in or %. */
enum class MeasureDimension { MASS, LENGTH, PERCENT }

/** Contract body measurements with their wire key and accepted metric range. */
enum class MeasurementKind(val wire: String, val dimension: MeasureDimension, val min: Double, val max: Double) {
    WEIGHT("weightKg", MeasureDimension.MASS, 20.0, 400.0),
    BODY_FAT("bodyFatPct", MeasureDimension.PERCENT, 2.0, 70.0),
    WAIST("waistCm", MeasureDimension.LENGTH, 30.0, 250.0),
    CHEST("chestCm", MeasureDimension.LENGTH, 30.0, 250.0),
    HIP("hipCm", MeasureDimension.LENGTH, 30.0, 250.0),
    ARM("armCm", MeasureDimension.LENGTH, 10.0, 100.0),
    THIGH("thighCm", MeasureDimension.LENGTH, 20.0, 150.0);

    fun inRange(metric: Double) = metric in min..max
}

/** One body measurement entry. [values] holds only the measurements that were taken (metric). */
data class BodyMeasurement(val id: String, val date: LocalDate, val values: Map<MeasurementKind, Double>)

data class BodyMeasurementPage(val items: List<BodyMeasurement>, val nextCursor: String?)

/** A new entry: a date plus at least one metric measurement. */
data class NewMeasurement(val date: LocalDate, val values: Map<MeasurementKind, Double>)

/** A partial PATCH: null [date] and missing kinds stay unchanged; [Field.Clear] removes a measurement. */
data class MeasurementChanges(
    val date: LocalDate? = null,
    val values: Map<MeasurementKind, Field<Double>> = emptyMap(),
) {
    val isEmpty get() = date == null && values.values.all { it == Field.Unchanged }
}

/** One day of the summary. A null value is a gap (no data that day), never zero. */
data class ProgressPoint(
    val date: LocalDate,
    val weightKg: Double? = null,
    val workoutsCompleted: Int? = null,
    val activeMinutes: Int? = null,
    val energyKcal: Double? = null,
    val xpEarned: Int? = null,
)

/** [points] has exactly one entry per day from [from] to [to]; days the server left out are all gaps. */
data class ProgressSummary(val from: LocalDate, val to: LocalDate, val points: List<ProgressPoint>, val streakDays: Int)

data class XpBalance(val total: Int, val level: Int, val xpIntoLevel: Int, val xpForNextLevel: Int)

/** The active plan as Home needs it: workouts keyed by ISO weekday (1 = Monday). */
data class HomePlan(val id: String, val name: String, val weeks: Int, val workouts: List<PlanWorkout>) {
    /** The workout for [date], or null on a rest day. */
    fun workoutOn(date: LocalDate): PlanWorkout? = workouts.firstOrNull { it.dayOfWeek == date.dayOfWeek.value }
}

data class PlanWorkout(val dayOfWeek: Int, val name: String, val exerciseCount: Int)

/** Two decimals, half-up, on the decimal value (same rule as nutrition). Enough for lb/in converted to metric. */
fun roundMetric(v: Double): Double = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toDouble()

fun BodyMeasurementDto.toDomain() = BodyMeasurement(
    id = id,
    date = LocalDate.parse(date),
    values = buildMap {
        weightKg?.let { put(MeasurementKind.WEIGHT, it) }
        bodyFatPct?.let { put(MeasurementKind.BODY_FAT, it) }
        waistCm?.let { put(MeasurementKind.WAIST, it) }
        chestCm?.let { put(MeasurementKind.CHEST, it) }
        hipCm?.let { put(MeasurementKind.HIP, it) }
        armCm?.let { put(MeasurementKind.ARM, it) }
        thighCm?.let { put(MeasurementKind.THIGH, it) }
    },
)

fun NewMeasurement.toJson(): JsonObject = buildJsonObject {
    put("date", date.toString())
    values.forEach { (k, v) -> put(k.wire, roundMetric(v)) }
}

/** Only changed keys are sent; [Field.Clear] becomes JSON null. */
fun MeasurementChanges.toJson(): JsonObject = buildJsonObject {
    date?.let { put("date", it.toString()) }
    values.forEach { (k, f) ->
        when (f) {
            Field.Unchanged -> Unit
            Field.Clear -> put(k.wire, JsonNull)
            is Field.Set -> put(k.wire, JsonPrimitive(roundMetric(f.value)))
        }
    }
}

fun ProgressSummaryDto.toDomain(): ProgressSummary {
    val start = LocalDate.parse(from)
    val end = LocalDate.parse(to)
    val byDate = points.associateBy { LocalDate.parse(it.date) }
    val days = generateSequence(start) { d -> d.plusDays(1).takeIf { it <= end } }.toList()
    return ProgressSummary(
        from = start,
        to = end,
        points = days.map { d ->
            byDate[d]?.let { ProgressPoint(d, it.weightKg, it.workoutsCompleted, it.activeMinutes, it.energyKcal, it.xpEarned) }
                ?: ProgressPoint(d)
        },
        streakDays = streakDays,
    )
}

fun XpBalanceDto.toDomain() = XpBalance(total, level, xpIntoLevel, xpForNextLevel)

fun ActivePlanDto.toDomain() = HomePlan(id, name, weeks, workouts.map { PlanWorkout(it.dayOfWeek, it.name, it.exercises.size) })
