package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.session.FinishOutcome
import com.vipin.fitness.core.data.session.SessionExercise
import com.vipin.fitness.core.data.session.SessionSet
import com.vipin.fitness.core.data.session.SessionStatus
import com.vipin.fitness.core.data.session.StartOutcome
import com.vipin.fitness.core.data.session.WorkoutSession
import com.vipin.fitness.core.data.session.WorkoutSessionRepository
import com.vipin.fitness.core.data.sync.OfflineSync
import com.vipin.fitness.core.data.sync.SyncScheduler
import com.vipin.fitness.core.data.workout.Exercise
import com.vipin.fitness.core.data.workout.PlannedWorkout
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.data.workout.WorkoutPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** In-memory session store with the repository's rules, minus Room and the outbox. */
class FakeWorkoutSessionRepository(private val now: () -> Instant = { Instant.EPOCH }) : WorkoutSessionRepository {
    private val sessions = MutableStateFlow<Map<String, WorkoutSession>>(emptyMap())
    override val pendingWrites = MutableStateFlow(0)
    val starts = mutableListOf<Pair<PlannedWorkout, Map<String, Exercise>>>()
    val finishes = mutableListOf<Pair<Int?, String?>>()
    private var nextId = 0

    override val unfinished: Flow<WorkoutSession?> =
        sessions.map { m -> m.values.lastOrNull { it.status != SessionStatus.COMPLETED } }

    override fun session(localId: String): Flow<WorkoutSession?> = sessions.map { it[localId] }

    override val flagged: Flow<List<WorkoutSession>> = sessions.map { m -> m.values.filter { it.syncError != null }.reversed() }

    override suspend fun dismissFlag(localId: String) {
        sessions.value[localId]?.let { put(it.copy(syncError = null)) }
    }

    fun current(): WorkoutSession? = sessions.value.values.lastOrNull { it.status != SessionStatus.COMPLETED }
    fun put(s: WorkoutSession) = sessions.update { it + (s.localId to s) }

    private fun MutableStateFlow<Map<String, WorkoutSession>>.update(f: (Map<String, WorkoutSession>) -> Map<String, WorkoutSession>) {
        value = f(value)
    }

    private fun edit(f: (WorkoutSession) -> WorkoutSession) {
        val s = current() ?: return
        put(f(s))
    }

    private fun editSet(id: String, f: (SessionSet) -> SessionSet) = edit { s ->
        s.copy(exercises = s.exercises.map { e -> e.copy(sets = e.sets.map { if (it.clientSetId == id) f(it) else it }) })
    }

    override suspend fun start(plan: WorkoutPlan, workout: PlannedWorkout, exercises: Map<String, Exercise>): StartOutcome {
        current()?.let { return StartOutcome.AlreadyActive(it.localId) }
        starts += workout to exercises
        val id = "s${++nextId}"
        put(testSession(id, workout.name, workout.exercises.mapIndexed { i, p ->
            val ex = exercises[p.exerciseId]
            testSessionExercise(i, p.exerciseId, ex?.name.orEmpty(), p.sets, ex?.trackingType ?: TrackingType.REPS_WEIGHT, p.restSeconds, id)
        }, now()))
        pendingWrites.value++
        return StartOutcome.Started(id)
    }

    override suspend fun pause() = edit {
        if (it.status != SessionStatus.IN_PROGRESS) it else {
            val ran = it.resumedAt?.let { r -> now().toEpochMilli() - r.toEpochMilli() } ?: 0
            it.copy(status = SessionStatus.PAUSED, activeMsBeforePause = it.activeMsBeforePause + ran, resumedAt = null)
        }
    }

    override suspend fun resume() = edit { if (it.status != SessionStatus.PAUSED) it else it.copy(status = SessionStatus.IN_PROGRESS, resumedAt = now()) }

    override suspend fun editSet(clientSetId: String, reps: Int?, weightKg: Double?, durationSeconds: Int?) =
        editSet(clientSetId) { it.copy(reps = reps, weightKg = weightKg, durationSeconds = durationSeconds) }

    override suspend fun setCompleted(clientSetId: String, completed: Boolean) {
        editSet(clientSetId) { it.copy(completed = completed) }
        pendingWrites.value++
    }

    override suspend fun setSkipped(position: Int, skipped: Boolean) = edit { s ->
        s.copy(exercises = s.exercises.map { if (it.position == position) it.copy(skipped = skipped) else it })
    }

    override suspend fun replaceExercise(position: Int, exercise: Exercise) = edit { s ->
        s.copy(
            exercises = s.exercises.map { e ->
                if (e.position != position) e else e.copy(
                    exerciseId = exercise.id, name = exercise.name, trackingType = exercise.trackingType, replaced = true, skipped = false,
                    sets = e.sets.map { if (it.completed) it else it.copy(exerciseId = exercise.id) },
                )
            },
        )
    }

    override suspend fun finish(perceivedDifficulty: Int?, notes: String?): FinishOutcome {
        val s = current() ?: return FinishOutcome.NoSession
        if (s.completedSets == 0) return FinishOutcome.NoCompletedSets
        finishes += perceivedDifficulty to notes
        put(s.copy(status = SessionStatus.COMPLETED, completedAt = now(), perceivedDifficulty = perceivedDifficulty, resumedAt = null))
        pendingWrites.value++
        return FinishOutcome.Finished
    }
}

fun testSessionExercise(
    position: Int,
    exerciseId: String,
    name: String = "Exercise $exerciseId",
    sets: Int = 2,
    tracking: TrackingType = TrackingType.REPS_WEIGHT,
    restSeconds: Int = 90,
    sessionId: String = "s",
) = SessionExercise(
    position, exerciseId, name, tracking, 8, 12, null, null, restSeconds, skipped = false, replaced = false,
    sets = (0 until sets).map { SessionSet("$sessionId-$position-$it", it, exerciseId, 12, null, null, false) },
)

fun testSession(id: String, name: String, exercises: List<SessionExercise>, startedAt: Instant = Instant.EPOCH) = WorkoutSession(
    localId = id, name = name, status = SessionStatus.IN_PROGRESS, startedAt = startedAt, activeMsBeforePause = 0,
    resumedAt = startedAt, completedAt = null, perceivedDifficulty = null, exercises = exercises,
    synced = false, xpAwarded = null, syncError = null,
)

class FakeOfflineSync(var unsynced: Int = 0) : OfflineSync {
    var syncs = 0
    /** Applied on each sync, e.g. to simulate the network coming back. */
    var afterSync: () -> Unit = {}
    override suspend fun syncNow(): Int {
        syncs++
        afterSync()
        return unsynced
    }
}

class FakeSyncScheduler : SyncScheduler {
    var requests = 0
    override fun requestSync() { requests++ }
}
