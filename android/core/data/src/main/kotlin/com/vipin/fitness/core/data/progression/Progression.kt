package com.vipin.fitness.core.data.progression

import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.data.workout.Wire
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.data.workout.toWireEnum
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.ProgressionApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.ErrorWithDetailsDto
import com.vipin.fitness.core.network.model.PlannedExerciseUpdateDto
import com.vipin.fitness.core.network.model.PlannedWorkoutUpdateDto
import com.vipin.fitness.core.network.model.PrescriptionValuesDto
import com.vipin.fitness.core.network.model.ProgressionSuggestionDto
import com.vipin.fitness.core.network.model.ProgressionSuggestionsDto
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import com.vipin.fitness.core.network.model.WorkoutPlanUpdateDto
import com.vipin.fitness.core.network.parseRetryAfter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

enum class ProgressionAction(override val wire: String) : Wire { INCREASE("increase"), HOLD("hold"), DELOAD("deload") }

/** docs/specs/adaptive-progression.md reason codes. A code we don't know is [UNKNOWN] (generic copy). */
enum class ProgressionReason(override val wire: String) : Wire {
    ALL_SETS_COMPLETED("ALL_SETS_COMPLETED"), NO_TARGET_SET("NO_TARGET_SET"), NOT_ENOUGH_HISTORY("NOT_ENOUGH_HISTORY"),
    MISSED_TARGET("MISSED_TARGET"), HIGH_EFFORT("HIGH_EFFORT"), COOLDOWN("COOLDOWN"), CAP_REACHED("CAP_REACHED"),
    REP_CEILING("REP_CEILING"), REPEATED_MISSES("REPEATED_MISSES"), VERY_HARD("VERY_HARD"), UNKNOWN("");

    companion object {
        fun of(wire: String) = wire.takeIf { it.isNotEmpty() }?.toWireEnum<ProgressionReason>() ?: UNKNOWN
    }
}

/** `PrescriptionValues`: exact PlannedExercise fields. */
data class Prescription(
    val sets: Int,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val targetWeightKg: Double? = null,
    val durationSeconds: Int? = null,
)

data class ProgressionSuggestion(
    val plannedWorkoutId: String,
    val exerciseId: String,
    /** Position in its workout: with [plannedWorkoutId], the entry's identity (an exercise can repeat). */
    val order: Int,
    val trackingType: TrackingType?,
    /** Null for an action this client doesn't know: shown, never applied. */
    val action: ProgressionAction?,
    val reason: ProgressionReason,
    val current: Prescription,
    /** Complete values to write; null for hold. */
    val suggested: Prescription?,
    /** Only with REP_CEILING; may be empty. */
    val harderVariations: List<String> = emptyList(),
    val cooldownUntil: Instant? = null,
    /** Null from servers before #146. */
    val inputs: ProgressionInputs? = null,
) {
    val key: String get() = keyOf(plannedWorkoutId, order)
    /** Something the user can accept: a known change with values to write. */
    val actionable: Boolean get() = suggested != null && (action == ProgressionAction.INCREASE || action == ProgressionAction.DELOAD)

    companion object {
        fun keyOf(plannedWorkoutId: String, order: Int) = "$plannedWorkoutId#$order"
    }
}

data class ProgressionInputs(
    val sessionsRequired: Int?,
    val sessionsConsidered: Int?,
    val sessionsMetTarget: Int?,
    val maxPerceivedDifficulty: Int?,
    val cooldownActive: Boolean,
    val cooldownUntil: Instant?,
    val step: ProgressionStep?,
)

/** How `suggested` was derived. [rule] is the wire value (percent_increase, heaviest_used, rep_step, deload, or newer). */
data class ProgressionStep(val rule: String?, val percent: Double?, val capPercent: Double?, val capApplied: Boolean, val minStepApplied: Boolean)

/** A suggestion placed on its plan row: workout found by id (array position kept), exercise by order. */
data class SuggestionRow(val suggestion: ProgressionSuggestion, val workoutIndex: Int, val workoutName: String, val day: DayOfWeek?)

/**
 * Plan and suggestions at the same version. [plan] is kept as received (array order, ids, every
 * field) because an apply sends the whole plan back.
 */
data class ProgressionData(
    val planId: String,
    val planVersion: Int,
    val plan: WorkoutPlanDto,
    val rows: List<SuggestionRow>,
    /** Suggestions that matched no plan row (not shown, never applied). */
    val unmatched: Int = 0,
)

sealed interface ApplyOutcome {
    data class Applied(val newVersion: Int) : ApplyOutcome
    /** Stale baseVersion; nothing was written. [currentVersion] from `details`, when present. */
    data class VersionConflict(val currentVersion: Int?) : ApplyOutcome
    data object PlanArchived : ApplyOutcome
    data object KeyReused : ApplyOutcome
    data object InProgress : ApplyOutcome
    data class RateLimited(val retryAfterMs: Long?) : ApplyOutcome
    data object Offline : ApplyOutcome
    data object NothingToApply : ApplyOutcome
    data class Failed(val error: ApiError) : ApplyOutcome
}

fun ProgressionSuggestionsDto.toDomain(): List<ProgressionSuggestion> = suggestions.map { it.toDomain() }

fun ProgressionSuggestionDto.toDomain() = ProgressionSuggestion(
    plannedWorkoutId = plannedWorkoutId,
    exerciseId = exerciseId,
    order = order,
    trackingType = trackingType.toWireEnum<TrackingType>(),
    action = action.toWireEnum<ProgressionAction>(),
    reason = ProgressionReason.of(reason),
    current = current.toDomain(),
    suggested = suggested?.toDomain(),
    harderVariations = harderVariations.orEmpty(),
    cooldownUntil = cooldownUntil.toInstantOrNull(),
    inputs = inputs?.let { i ->
        ProgressionInputs(
            sessionsRequired = i.sessionsRequired,
            sessionsConsidered = i.sessionsConsidered,
            sessionsMetTarget = i.sessionsMetTarget,
            maxPerceivedDifficulty = i.maxPerceivedDifficulty,
            cooldownActive = i.cooldown?.active == true,
            cooldownUntil = i.cooldown?.until.toInstantOrNull(),
            step = i.step?.let { ProgressionStep(it.rule, it.percent, it.capPercent, it.capApplied, it.minStepApplied) },
        )
    },
)

private fun String?.toInstantOrNull(): Instant? = this?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

private fun PrescriptionValuesDto.toDomain() = Prescription(sets, repsMin, repsMax, targetWeightKg, durationSeconds)

/** Places each suggestion on the plan: workout by `plannedWorkoutId`, then the exercise with the same `order`. */
fun matchSuggestions(plan: WorkoutPlanDto, suggestions: List<ProgressionSuggestion>): ProgressionData {
    val rows = suggestions.mapNotNull { s ->
        val i = plan.workouts.indexOfFirst { it.id == s.plannedWorkoutId }
        val w = plan.workouts.getOrNull(i) ?: return@mapNotNull null
        if (w.exercises.none { it.order == s.order }) return@mapNotNull null
        SuggestionRow(s, i, w.name, runCatching { DayOfWeek.of(w.dayOfWeek) }.getOrNull())
    }
    return ProgressionData(plan.id, plan.version, plan, rows, suggestions.size - rows.size)
}

/**
 * The PATCH body (spec "Android apply recipe"): the whole plan at [ProgressionData.planVersion],
 * workouts in the same array order, every exercise with its rest and notes; only the accepted
 * entries' sets/reps/weight/duration replaced by `suggested`. Null when nothing accepted applies.
 */
fun buildProgressionUpdate(data: ProgressionData, acceptedKeys: Set<String>): WorkoutPlanUpdateDto? {
    val accepted = data.rows.map { it.suggestion }.filter { it.actionable && it.key in acceptedKeys }.associateBy { it.key }
    if (accepted.isEmpty()) return null
    val plan = data.plan
    return WorkoutPlanUpdateDto(
        baseVersion = data.planVersion,
        weeks = plan.weeks,
        generator = plan.source,
        rationale = plan.rationale,
        workouts = plan.workouts.map { w ->
            PlannedWorkoutUpdateDto(
                dayOfWeek = w.dayOfWeek,
                name = w.name,
                exercises = w.exercises.map { e ->
                    val p = w.id?.let { accepted[ProgressionSuggestion.keyOf(it, e.order)] }?.suggested
                    PlannedExerciseUpdateDto(
                        exerciseId = e.exerciseId,
                        order = e.order,
                        sets = p?.sets ?: e.sets,
                        repsMin = if (p != null) p.repsMin else e.repsMin,
                        repsMax = if (p != null) p.repsMax else e.repsMax,
                        targetWeightKg = if (p != null) p.targetWeightKg else e.targetWeightKg,
                        durationSeconds = if (p != null) p.durationSeconds else e.durationSeconds,
                        restSeconds = e.restSeconds,
                        notes = e.notes,
                    )
                },
            )
        },
    )
}

interface ProgressionRepository {
    /** Bumped on sign-out and sign-in: a response from an older generation belongs to another user. */
    val userGeneration: StateFlow<Int>

    /** Plan and suggestions at the same version (refetched once if they disagree). */
    suspend fun load(planId: String): ApiResult<ProgressionData>

    /** One PATCH with every accepted entry; [idempotencyKey] is reused by the caller on a retry. */
    suspend fun apply(data: ProgressionData, acceptedKeys: Set<String>, idempotencyKey: String): ApplyOutcome
}

@Singleton
class DefaultProgressionRepository @Inject constructor(
    private val api: ProgressionApi,
    private val workouts: WorkoutRepository,
    private val json: Json,
    private val clock: Clock,
) : ProgressionRepository, UserScopedState {
    private val generation = MutableStateFlow(0)
    override val userGeneration: StateFlow<Int> = generation.asStateFlow()

    override suspend fun clearUserState() = generation.update { it + 1 }

    override suspend fun load(planId: String): ApiResult<ProgressionData> = apiCall(json) {
        var attempt = 0
        while (true) {
            val plan = api.plan(planId)
            val s = api.suggestions(planId)
            if (s.planVersion == plan.version) return@apiCall matchSuggestions(plan, s.toDomain())
            // Edited in between: both again, once.
            if (++attempt > 1) throw IllegalStateException("Plan version ${plan.version} != suggestions ${s.planVersion}")
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    override suspend fun apply(data: ProgressionData, acceptedKeys: Set<String>, idempotencyKey: String): ApplyOutcome {
        val body = buildProgressionUpdate(data, acceptedKeys) ?: return ApplyOutcome.NothingToApply
        return try {
            val plan = api.update(data.planId, idempotencyKey, body)
            // The Workout tab shows the new targets; failures there don't undo the apply.
            workouts.refreshActivePlan()
            ApplyOutcome.Applied(plan.version)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: HttpException) {
            e.toApplyOutcome()
        } catch (e: IOException) {
            ApplyOutcome.Offline
        } catch (e: Exception) {
            ApplyOutcome.Failed(ApiError.Unexpected(e))
        }
    }

    private fun HttpException.toApplyOutcome(): ApplyOutcome {
        val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
        val dto = raw?.let { runCatching { json.decodeFromString(ErrorWithDetailsDto.serializer(), it) }.getOrNull() }
        val status = code()
        val code = dto?.code ?: "HTTP_$status"
        return when {
            status == 409 && code == "VERSION_CONFLICT" ->
                ApplyOutcome.VersionConflict(dto?.details?.get("currentVersion")?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() })
            status == 409 && code == "PLAN_ARCHIVED" -> ApplyOutcome.PlanArchived
            status == 409 && code == "IDEMPOTENCY_KEY_REUSED" -> ApplyOutcome.KeyReused
            status == 409 && code == "IDEMPOTENCY_IN_PROGRESS" -> ApplyOutcome.InProgress
            status == 429 -> ApplyOutcome.RateLimited(parseRetryAfter(response()?.headers()?.get("Retry-After"), clock.millis()))
            else -> ApplyOutcome.Failed(
                ApiError.Http(status, code, dto?.message ?: message(), dto?.requestId ?: response()?.headers()?.get("X-Request-Id")),
            )
        }
    }
}
