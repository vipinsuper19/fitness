package com.vipin.fitness.core.data.session

import androidx.room.withTransaction
import com.vipin.fitness.core.data.UserScopedState
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.sync.OutboxProcessor
import com.vipin.fitness.core.data.sync.SyncScheduler
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.data.workout.WorkoutPlan
import com.vipin.fitness.core.database.FitnessDatabase
import com.vipin.fitness.core.database.outbox.PendingWriteEntity
import com.vipin.fitness.core.database.session.CurrentAccountEntity
import com.vipin.fitness.core.database.session.SessionExerciseEntity
import com.vipin.fitness.core.database.session.SessionSetEntity
import com.vipin.fitness.core.database.session.WorkoutSessionEntity
import com.vipin.fitness.core.network.model.SessionCompleteDto
import com.vipin.fitness.core.network.model.SessionStartDto
import com.vipin.fitness.core.network.model.SessionUpdateDto
import com.vipin.fitness.core.network.model.SetLogUpsertDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface StartOutcome {
    val localId: String
    data class Started(override val localId: String) : StartOutcome
    /** One session at a time: the unfinished one is returned instead. */
    data class AlreadyActive(override val localId: String) : StartOutcome
}

/**
 * Workout tracking, offline first. Every action is written to Room and, when the server needs
 * to know, queued in pending_writes in the same transaction with a fresh Idempotency-Key, then
 * a sync is requested. The UI reads Room only, so it works the same with or without network.
 */
interface WorkoutSessionRepository {
    /** The session in progress or paused, if any. */
    val unfinished: Flow<WorkoutSession?>
    fun session(localId: String): Flow<WorkoutSession?>
    /** Writes not yet accepted by the server. */
    val pendingWrites: Flow<Int>
    /** Sessions with a write the server rejected for good, newest first. */
    val flagged: Flow<List<WorkoutSession>>
    /** The user has seen the problem. */
    suspend fun dismissFlag(localId: String)

    suspend fun start(plan: WorkoutPlan, workout: PlannedWorkout, exercises: Map<String, Exercise>): StartOutcome
    suspend fun pause()
    suspend fun resume()
    /** Edits values. Local only; a completed set is re-sent on pause, finish or its next toggle. */
    suspend fun editSet(clientSetId: String, reps: Int?, weightKg: Double?, durationSeconds: Int?)
    suspend fun setCompleted(clientSetId: String, completed: Boolean)
    suspend fun setSkipped(position: Int, skipped: Boolean)
    /** Swaps the exercise for the sets not yet done; done sets keep the exercise they were done with. */
    suspend fun replaceExercise(position: Int, exercise: Exercise)
    suspend fun finish(perceivedDifficulty: Int?, notes: String?): FinishOutcome
}

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class DefaultWorkoutSessionRepository @Inject constructor(
    private val db: FitnessDatabase,
    private val json: Json,
    private val clock: Clock,
    private val sync: SyncScheduler,
) : WorkoutSessionRepository, UserScopedState {
    private val dao = db.workoutSessionDao()
    private val outbox = db.pendingWriteDao()

    override val unfinished: Flow<WorkoutSession?> =
        dao.observeUnfinished().flatMapLatest { s -> if (s == null) flowOf(null) else details(s.localId) }

    override fun session(localId: String): Flow<WorkoutSession?> = details(localId)

    override val pendingWrites: Flow<Int> = outbox.count()

    override val flagged: Flow<List<WorkoutSession>> =
        dao.observeFlagged().map { list -> list.map { it.toDomain(emptyList(), emptyList()) } }

    override suspend fun dismissFlag(localId: String) = dao.clearSyncError(localId)

    private fun details(localId: String): Flow<WorkoutSession?> =
        combine(dao.observeSession(localId), dao.observeExercises(localId), dao.observeSets(localId)) { s, ex, sets ->
            s?.toDomain(ex, sets)
        }

    override suspend fun start(plan: WorkoutPlan, workout: PlannedWorkout, exercises: Map<String, Exercise>): StartOutcome {
        var outcome: StartOutcome? = null
        db.withTransaction {
            dao.unfinished()?.let {
                outcome = StartOutcome.AlreadyActive(it.localId)
                return@withTransaction
            }
            val now = clock.millis()
            val localId = UUID.randomUUID().toString()
            val startKey = newIdempotencyKey()
            dao.insertSession(
                WorkoutSessionEntity(
                    localId = localId, startKey = startKey, planId = plan.id, planVersion = plan.version,
                    plannedWorkoutId = workout.id, name = workout.name, status = SessionStatus.IN_PROGRESS.wire,
                    startedAtMs = now, resumedAtMs = now, ownerUserId = dao.currentUserId(),
                ),
            )
            val slots = workout.exercises.mapIndexed { i, p ->
                val ex = exercises[p.exerciseId]
                val tracking = ex?.trackingType ?: if (p.durationSeconds != null) TrackingType.DURATION else TrackingType.REPS_WEIGHT
                SessionExerciseEntity(
                    sessionLocalId = localId, position = i, exerciseId = p.exerciseId, exerciseName = ex?.name.orEmpty(),
                    trackingType = tracking.wire, targetSets = p.sets, repsMin = p.repsMin, repsMax = p.repsMax,
                    durationSeconds = p.durationSeconds, targetWeightKg = p.targetWeightKg, restSeconds = p.restSeconds,
                )
            }
            dao.insertExercises(slots)
            dao.insertSets(
                slots.flatMap { e ->
                    val duration = TrackingType.entries.first { it.wire == e.trackingType }.usesDuration
                    (0 until e.targetSets).map { i ->
                        SessionSetEntity(
                            clientSetId = UUID.randomUUID().toString(), sessionLocalId = localId, position = e.position, setIndex = i,
                            exerciseId = e.exerciseId,
                            reps = if (duration) null else e.repsMax ?: e.repsMin,
                            weightKg = if (duration) null else e.targetWeightKg,
                            durationSeconds = if (duration) e.durationSeconds else null,
                        )
                    }
                },
            )
            queue(
                startKey, "POST", OutboxProcessor.START_PATH,
                json.encodeToString(SessionStartDto(workout.id, plan.version.takeIf { workout.id != null }, Instant.ofEpochMilli(now).toString())),
                now,
            )
            outcome = StartOutcome.Started(localId)
        }
        sync.requestSync()
        return outcome!!
    }

    override suspend fun pause() = changeStatus(SessionStatus.PAUSED)
    override suspend fun resume() = changeStatus(SessionStatus.IN_PROGRESS)

    private suspend fun changeStatus(to: SessionStatus) {
        val changed = db.withTransaction {
            val s = dao.unfinished() ?: return@withTransaction false
            if (s.status == to.wire) return@withTransaction false
            val now = clock.millis()
            val updated = if (to == SessionStatus.PAUSED) {
                s.copy(status = to.wire, activeMsBeforePause = s.activeMsBeforePause + (now - (s.resumedAtMs ?: now)).coerceAtLeast(0), resumedAtMs = null)
            } else {
                s.copy(status = to.wire, resumedAtMs = now)
            }
            dao.updateSession(updated)
            if (to == SessionStatus.PAUSED) flushEdited(s.localId, now)
            queue(newIdempotencyKey(), "PATCH", sessionPath(s.localId), json.encodeToString(SessionUpdateDto(to.wire)), now)
            true
        }
        if (changed) sync.requestSync()
    }

    override suspend fun editSet(clientSetId: String, reps: Int?, weightKg: Double?, durationSeconds: Int?) {
        db.withTransaction {
            val set = dao.set(clientSetId) ?: return@withTransaction
            if (!isOpen(set.sessionLocalId)) return@withTransaction
            dao.updateSet(set.copy(reps = reps, weightKg = weightKg, durationSeconds = durationSeconds, needsSync = set.completed || set.needsSync))
        }
    }

    override suspend fun setCompleted(clientSetId: String, completed: Boolean) {
        val queued = db.withTransaction {
            val set = dao.set(clientSetId) ?: return@withTransaction false
            if (set.completed == completed || !isOpen(set.sessionLocalId)) return@withTransaction false
            val now = clock.millis()
            val updated = set.copy(completed = completed, loggedAtMs = now, needsSync = false)
            dao.updateSet(updated)
            queueSet(updated, now)
            true
        }
        if (queued) sync.requestSync()
    }

    override suspend fun setSkipped(position: Int, skipped: Boolean) {
        db.withTransaction {
            val s = dao.unfinished() ?: return@withTransaction
            val e = dao.exercise(s.localId, position) ?: return@withTransaction
            dao.updateExercise(e.copy(skipped = skipped))
        }
    }

    override suspend fun replaceExercise(position: Int, exercise: Exercise) {
        db.withTransaction {
            val s = dao.unfinished() ?: return@withTransaction
            val e = dao.exercise(s.localId, position) ?: return@withTransaction
            if (e.exerciseId == exercise.id) return@withTransaction
            val duration = exercise.trackingType.usesDuration
            dao.updateExercise(
                e.copy(
                    exerciseId = exercise.id, exerciseName = exercise.name, trackingType = exercise.trackingType.wire,
                    skipped = false, replacedExerciseId = e.replacedExerciseId ?: e.exerciseId,
                    durationSeconds = if (duration) e.durationSeconds ?: DEFAULT_DURATION_S else null,
                ),
            )
            dao.sets(s.localId).filter { it.position == position && !it.completed }.forEach {
                dao.updateSet(
                    it.copy(
                        exerciseId = exercise.id,
                        reps = if (duration) null else it.reps ?: e.repsMax ?: e.repsMin,
                        weightKg = if (duration) null else it.weightKg,
                        durationSeconds = if (duration) it.durationSeconds ?: e.durationSeconds ?: DEFAULT_DURATION_S else null,
                    ),
                )
            }
        }
    }

    override suspend fun finish(perceivedDifficulty: Int?, notes: String?): FinishOutcome {
        val outcome = db.withTransaction {
            val s = dao.unfinished() ?: return@withTransaction FinishOutcome.NoSession
            if (dao.sets(s.localId).none { it.completed }) return@withTransaction FinishOutcome.NoCompletedSets
            val now = clock.millis()
            flushEdited(s.localId, now)
            val key = newIdempotencyKey()
            val difficulty = perceivedDifficulty?.coerceIn(1, 5)
            val cleanNotes = notes?.trim()?.take(1000)?.ifEmpty { null }
            dao.updateSession(
                s.copy(
                    status = SessionStatus.COMPLETED.wire, completeKey = key, completedAtMs = now,
                    activeMsBeforePause = s.activeMsBeforePause + (s.resumedAtMs?.let { now - it } ?: 0).coerceAtLeast(0),
                    resumedAtMs = null, perceivedDifficulty = difficulty, notes = cleanNotes,
                ),
            )
            queue(
                key, "POST", "${sessionPath(s.localId)}/complete",
                json.encodeToString(SessionCompleteDto(Instant.ofEpochMilli(now).toString(), difficulty, cleanNotes)), now,
            )
            FinishOutcome.Finished
        }
        if (outcome == FinishOutcome.Finished) sync.requestSync()
        return outcome
    }

    /** Sign-out, confirmed by the user: sessions and unsent writes go with the account. */
    override suspend fun clearUserState() {
        db.withTransaction {
            outbox.clear()
            dao.clear()
            dao.clearCurrentAccount()
        }
    }

    /**
     * The same user back: keep their unsynced workouts and send them. Anyone else: delete the
     * previous owner's (and unknown-owner) sessions and writes without sending them.
     */
    override suspend fun onSignedIn(userId: String) {
        db.withTransaction {
            // Includes ownerless writes queued before owner_user_id existed. They are deliberately
            // not backfilled: there are no real users yet, only test installs, so deleting them on
            // the next sign-in is intentional.
            outbox.deleteNotOwnedBy(userId)
            dao.deleteNotOwnedBy(userId)
            dao.setCurrentAccount(CurrentAccountEntity(userId = userId))
        }
        sync.requestSync()
    }

    private suspend fun isOpen(localId: String) = dao.session(localId)?.status?.let { it != SessionStatus.COMPLETED.wire } == true

    private suspend fun flushEdited(localId: String, now: Long) {
        dao.sets(localId).filter { it.needsSync }.forEach {
            val updated = it.copy(needsSync = false)
            dao.updateSet(updated)
            queueSet(updated, now)
        }
    }

    private suspend fun queueSet(set: SessionSetEntity, now: Long) {
        val body = SetLogUpsertDto(
            clientSetId = set.clientSetId, exerciseId = set.exerciseId, setIndex = set.setIndex, completed = set.completed,
            reps = set.reps, weightKg = set.weightKg, durationSeconds = set.durationSeconds,
            loggedAt = set.loggedAtMs?.let { Instant.ofEpochMilli(it).toString() },
        )
        queue(newIdempotencyKey(), "POST", "${sessionPath(set.sessionLocalId)}/sets", json.encodeToString(body), now)
    }

    /**
     * The queue is sent in created_at order, so the time is kept strictly increasing: writes in
     * the same millisecond (or after the clock moves back) still go in the order they were made.
     */
    private suspend fun queue(key: String, method: String, path: String, body: String, now: Long) {
        val at = maxOf(now, (outbox.latestCreatedAt() ?: Long.MIN_VALUE) + 1)
        outbox.enqueue(PendingWriteEntity(UUID.randomUUID().toString(), key, method, path, body, at, ownerUserId = dao.currentUserId()))
    }

    private fun sessionPath(localId: String) = "workout-sessions/${OutboxProcessor.sessionRef(localId)}"

    private companion object {
        const val DEFAULT_DURATION_S = 60
    }
}

private fun WorkoutSessionEntity.toDomain(ex: List<SessionExerciseEntity>, sets: List<SessionSetEntity>): WorkoutSession {
    val byPosition = sets.groupBy { it.position }
    return WorkoutSession(
        localId = localId, name = name, status = SessionStatus.of(status),
        startedAt = Instant.ofEpochMilli(startedAtMs), activeMsBeforePause = activeMsBeforePause,
        resumedAt = resumedAtMs?.let(Instant::ofEpochMilli), completedAt = completedAtMs?.let(Instant::ofEpochMilli),
        perceivedDifficulty = perceivedDifficulty,
        exercises = ex.map { e ->
            SessionExercise(
                position = e.position, exerciseId = e.exerciseId, name = e.exerciseName,
                trackingType = TrackingType.entries.firstOrNull { it.wire == e.trackingType } ?: TrackingType.REPS_WEIGHT,
                repsMin = e.repsMin, repsMax = e.repsMax, durationSeconds = e.durationSeconds, targetWeightKg = e.targetWeightKg,
                restSeconds = e.restSeconds, skipped = e.skipped, replaced = e.replacedExerciseId != null,
                sets = byPosition[e.position].orEmpty().sortedBy { it.setIndex }.map {
                    SessionSet(it.clientSetId, it.setIndex, it.exerciseId, it.reps, it.weightKg, it.durationSeconds, it.completed)
                },
            )
        },
        synced = serverId != null, xpAwarded = xpAwarded, syncError = syncError,
    )
}
