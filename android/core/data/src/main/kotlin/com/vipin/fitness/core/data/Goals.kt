package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.time.today
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.GoalDto
import com.vipin.fitness.core.network.GoalsApi
import com.vipin.fitness.core.network.apiCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.floor

/** Contract `GoalType`. [UNKNOWN] keeps a goal of a newer type listable (no progress, no editing). */
enum class GoalType(val wire: String) {
    LOSE_WEIGHT("lose_weight"), GAIN_MUSCLE("gain_muscle"), MAINTAIN("maintain"),
    IMPROVE_ENDURANCE("improve_endurance"), WORKOUTS_PER_WEEK("workouts_per_week"), UNKNOWN("");

    /** Target/start are body weights in kg. */
    val isWeight get() = this == LOSE_WEIGHT || this == GAIN_MUSCLE || this == MAINTAIN

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
        /** Types a user can create. */
        val creatable = entries.filter { it != UNKNOWN }
    }
}

enum class GoalStatus(val wire: String) {
    ACTIVE("active"), ACHIEVED("achieved"), ABANDONED("abandoned"), UNKNOWN("");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN }
}

data class Goal(
    val id: String,
    val type: GoalType,
    val status: GoalStatus,
    val targetValue: Double?,
    val startValue: Double?,
    val targetDate: LocalDate?,
)

data class NewGoal(val type: GoalType, val targetValue: Double?, val startValue: Double?, val targetDate: LocalDate?)

/** PATCH: null = unchanged; [Field.Clear] sends null. */
data class GoalChanges(
    val status: GoalStatus? = null,
    val targetValue: Field<Double> = Field.Unchanged,
    val targetDate: Field<LocalDate> = Field.Unchanged,
)

fun GoalDto.toDomain() = Goal(
    id = id,
    type = GoalType.of(type),
    status = GoalStatus.of(status),
    targetValue = targetValue,
    startValue = startValue,
    targetDate = targetDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
)

fun NewGoal.toJson(): JsonObject = buildJsonObject {
    put("type", type.wire)
    targetValue?.let { put("targetValue", it) }
    startValue?.let { put("startValue", it) }
    targetDate?.let { put("targetDate", it.toString()) }
}

fun GoalChanges.toJson(): JsonObject = buildJsonObject {
    status?.let { put("status", it.wire) }
    when (val v = targetValue) { is Field.Set -> put("targetValue", v.value); Field.Clear -> put("targetValue", JsonNull); Field.Unchanged -> Unit }
    when (val v = targetDate) { is Field.Set -> put("targetDate", v.value.toString()); Field.Clear -> put("targetDate", JsonNull); Field.Unchanged -> Unit }
}

interface GoalsRepository {
    /** Newest first. [status] null = all. */
    suspend fun list(status: GoalStatus? = null): ApiResult<List<Goal>>
    suspend fun create(goal: NewGoal, idempotencyKey: String = newIdempotencyKey()): ApiResult<Goal>
    suspend fun update(id: String, changes: GoalChanges, idempotencyKey: String = newIdempotencyKey()): ApiResult<Goal>
    suspend fun delete(id: String, idempotencyKey: String = newIdempotencyKey()): ApiResult<Unit>
}

@Singleton
class DefaultGoalsRepository @Inject constructor(private val api: GoalsApi, private val json: Json) : GoalsRepository {
    override suspend fun list(status: GoalStatus?) = apiCall(json) { api.list(status?.wire).items.map { it.toDomain() } }
    override suspend fun create(goal: NewGoal, idempotencyKey: String) = apiCall(json) { api.create(goal.toJson(), idempotencyKey).toDomain() }
    override suspend fun update(id: String, changes: GoalChanges, idempotencyKey: String) =
        apiCall(json) { api.update(id, changes.toJson(), idempotencyKey).toDomain() }
    override suspend fun delete(id: String, idempotencyKey: String) = apiCall(json) {
        val r = api.delete(id, idempotencyKey)
        if (!r.isSuccessful) throw HttpException(r)
    }
}

/**
 * A goal plus how far along it is, computed on the device from /progress/summary:
 * weight goals from the latest logged weight, workouts_per_week from this ISO week's completed workouts.
 * [fraction] null = no measurable progress (missing start/target/weight, endurance or unknown type).
 */
data class GoalProgress(
    val goal: Goal,
    val current: Double?,
    val fraction: Double?,
    /** maintain: within [MAINTAIN_TOLERANCE_KG] of the target. */
    val onTrack: Boolean? = null,
) {
    /** Highest milestone reached: 0, 25, 50, 75 or 100 (%). */
    val milestone: Int get() = fraction?.let { (floor(it * 4) * 25).toInt() } ?: 0
}

const val MAINTAIN_TOLERANCE_KG = 1.0

fun goalProgress(goal: Goal, latestWeightKg: Double?, workoutsThisWeek: Int): GoalProgress {
    val target = goal.targetValue
    return when (goal.type) {
        GoalType.LOSE_WEIGHT, GoalType.GAIN_MUSCLE -> {
            val start = goal.startValue
            val f = if (start == null || target == null || latestWeightKg == null || start == target) null
            else ((start - latestWeightKg) / (start - target)).coerceIn(0.0, 1.0)
            GoalProgress(goal, latestWeightKg, f)
        }
        GoalType.MAINTAIN -> GoalProgress(
            goal, latestWeightKg, null,
            onTrack = if (target == null || latestWeightKg == null) null else abs(latestWeightKg - target) <= MAINTAIN_TOLERANCE_KG,
        )
        GoalType.WORKOUTS_PER_WEEK -> GoalProgress(
            goal, workoutsThisWeek.toDouble(),
            target?.takeIf { it > 0 }?.let { (workoutsThisWeek / it).coerceIn(0.0, 1.0) },
        )
        GoalType.IMPROVE_ENDURANCE, GoalType.UNKNOWN -> GoalProgress(goal, null, null)
    }
}

/** Loads goals and their progress for the Progress tab and Home. */
class GoalProgressService @Inject constructor(
    private val goals: GoalsRepository,
    private val progress: ProgressRepository,
    private val clock: Clock,
) {
    /** Goal failures fail the call; a failed summary still returns the goals, without progress. */
    suspend fun load(status: GoalStatus? = GoalStatus.ACTIVE): ApiResult<List<GoalProgress>> {
        val list = when (val r = goals.list(status)) {
            is ApiResult.Failure -> return r
            is ApiResult.Success -> r.value
        }
        if (list.none { it.status == GoalStatus.ACTIVE }) return ApiResult.Success(list.map { GoalProgress(it, null, null) })
        val today = clock.today()
        val points = (progress.summary(today.minusDays(WEIGHT_LOOKBACK_DAYS - 1), today) as? ApiResult.Success)?.value?.points.orEmpty()
        val weight = points.lastOrNull { it.weightKg != null }?.weightKg
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val workouts = points.filter { it.date >= weekStart }.sumOf { it.workoutsCompleted ?: 0 }
        return ApiResult.Success(list.map { g -> if (g.status == GoalStatus.ACTIVE) goalProgress(g, weight, workouts) else GoalProgress(g, null, null) })
    }

    private companion object {
        /** How far back to look for the latest weight. */
        const val WEIGHT_LOOKBACK_DAYS = 90L
    }
}
