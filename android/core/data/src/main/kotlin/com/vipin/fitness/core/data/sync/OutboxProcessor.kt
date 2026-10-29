package com.vipin.fitness.core.data.sync

import com.vipin.fitness.core.database.outbox.OutboxRateLimitEntity
import com.vipin.fitness.core.database.outbox.PendingWriteDao
import com.vipin.fitness.core.database.outbox.PendingWriteEntity
import com.vipin.fitness.core.database.session.WorkoutSessionDao
import com.vipin.fitness.core.network.OutboxApi
import com.vipin.fitness.core.network.model.ErrorDto
import com.vipin.fitness.core.network.model.SessionCompleteResultDto
import com.vipin.fitness.core.network.model.WorkoutSessionDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import android.util.Log
import com.vipin.fitness.core.network.parseRetryAfter
import java.io.IOException
import java.time.Clock
import java.util.UUID
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

enum class DrainResult { Done, Retry }

/**
 * Sends queued writes oldest first, one at a time, so a session's start always goes before its
 * sets and completion. A write that can't go yet (offline, 5xx, 429) stops the drain and keeps
 * its place: later writes may depend on it. A write the server rejects for good (other 4xx) is
 * dropped and the error kept on its session, so one bad write never blocks the queue.
 *
 * Paths may name a session that only exists on the device as `{session:<localId>}`; it is
 * replaced by the server id once the session's start has synced.
 */
@Singleton
class OutboxProcessor @Inject constructor(
    private val pending: PendingWriteDao,
    private val sessions: WorkoutSessionDao,
    private val api: OutboxApi,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    /**
     * Epoch ms before which nothing is sent, set by a 429's Retry-After (or a default backoff).
     * Mirrors the current user's outbox_rate_limit row, which is the source of truth: it is
     * reloaded on every drain so the window survives a process restart.
     */
    @Volatile private var notBeforeMs = 0L

    /** After [DrainResult.Retry]: ms to wait if the server rate-limited us, else null (normal backoff). */
    fun rateLimitDelayMs(): Long? = (notBeforeMs - clock.millis()).takeIf { it > 0 }

    suspend fun drain(): DrainResult = mutex.withLock {
        val owner = owner()
        notBeforeMs = loadNotBefore(owner)
        // Rate-limited: don't send anything until the server's Retry-After has passed.
        if (rateLimitDelayMs() != null) return@withLock DrainResult.Retry
        drainLocked().also {
            if (it == DrainResult.Done) {
                notBeforeMs = 0L
                pending.clearNotBefore(owner)
            }
        }
    }

    /** Whose window applies: the signed-in account, or "" when none is known. */
    private suspend fun owner(): String = sessions.currentUserId() ?: ""

    /**
     * The stored window, or 0. A value further ahead than the longest window we ever set can only
     * come from clock skew (the clock was ahead when it was saved, then corrected): ignore and drop
     * it rather than park the queue.
     */
    private suspend fun loadNotBefore(owner: String): Long {
        val stored = pending.notBefore(owner) ?: return 0L
        if (stored - clock.millis() > MAX_RATE_LIMIT_WAIT_MS) {
            Log.w(TAG, "Ignoring outbox Retry-After window too far ahead (clock skew?)")
            pending.clearNotBefore(owner)
            return 0L
        }
        return stored
    }

    private suspend fun drainLocked(): DrainResult {
        var batch = pending.oldest(BATCH)
        while (batch.isNotEmpty()) {
            for (w in batch) {
                if (!send(w)) return DrainResult.Retry
            }
            batch = pending.oldest(BATCH)
        }
        return DrainResult.Done
    }

    /** True when the write is finished with (sent or dropped); false to stop and retry later. */
    private suspend fun send(w: PendingWriteEntity): Boolean {
        // Never send one user's write with another user's login.
        val current = sessions.currentUserId()
        if (w.ownerUserId != null && current != null && w.ownerUserId != current) {
            pending.delete(w.id)
            return true
        }
        val localId = SESSION_REF.find(w.path)?.groupValues?.get(1)
        val path = if (localId == null) {
            w.path
        } else {
            val serverId = sessions.serverId(localId)
            if (serverId == null) {
                // Its start isn't synced. The start is older, so it was dropped (or the session
                // was deleted): this write can never succeed.
                if (sessions.session(localId) == null || !hasPendingStart(localId)) return drop(w, localId, "SESSION_NOT_SYNCED")
                return false
            }
            w.path.replace(SESSION_REF, serverId)
        }
        val body = (w.body ?: "{}").toRequestBody(JSON)
        val response = try {
            when (w.method) {
                "POST" -> api.post(path, w.idempotencyKey, body)
                "PATCH" -> api.patch(path, w.idempotencyKey, body)
                "PUT" -> api.put(path, w.idempotencyKey, body)
                "DELETE" -> api.delete(path, w.idempotencyKey)
                else -> return drop(w, localId, "UNSUPPORTED_METHOD")
            }
        } catch (e: IOException) {
            pending.markFailed(w.id, "NETWORK")
            return false
        } catch (e: Exception) {
            pending.markFailed(w.id, "UNEXPECTED")
            return false
        }
        return handle(w, localId, response)
    }

    private suspend fun handle(w: PendingWriteEntity, localId: String?, r: Response<ResponseBody>): Boolean {
        val code = r.code()
        if (r.isSuccessful) {
            val text = r.body()?.string().orEmpty()
            if (w.path == START_PATH) {
                val id = runCatching { json.decodeFromString<WorkoutSessionDto>(text).id }
                    .onFailure { Log.w(TAG, "Unparseable start response for ${w.idempotencyKey}", it) }
                    .getOrNull()
                    // Accepted but we can't learn the server id, so nothing after it can sync: flag it.
                    ?: return drop(w, localId, BAD_RESPONSE)
                sessions.setServerId(w.idempotencyKey, id)
            } else if (w.path.endsWith("/complete")) {
                runCatching { sessions.setXpAwarded(w.idempotencyKey, json.decodeFromString<SessionCompleteResultDto>(text).xpAwarded) }
                    .onFailure { Log.w(TAG, "Unparseable complete response for ${w.idempotencyKey}", it) }
            }
            pending.delete(w.id)
            return true
        }
        val error = r.errorBody()?.string()?.let { runCatching { json.decodeFromString<ErrorDto>(it).code }.getOrNull() }
        // 404 (session gone, e.g. discarded on another device) and other 4xx: dropped below.
        return when {
            // 429: keep the write, its key and its place; wait out Retry-After before sending again.
            code == 429 -> {
                val wait = parseRetryAfter(r.headers()["Retry-After"], clock.millis()) ?: DEFAULT_RATE_LIMIT_WAIT_MS
                notBeforeMs = clock.millis() + wait.coerceIn(MIN_RATE_LIMIT_WAIT_MS, MAX_RATE_LIMIT_WAIT_MS)
                pending.setNotBefore(OutboxRateLimitEntity(owner(), notBeforeMs))
                pending.markFailed(w.id, error ?: "RATE_LIMITED")
                false
            }
            code == 408 || code >= 500 || error == "IDEMPOTENCY_IN_PROGRESS" -> {
                pending.markFailed(w.id, error ?: "HTTP_$code")
                false
            }
            // Other 409s (e.g. IDEMPOTENCY_KEY_REUSED, a conflicting state) are deliberately not
            // retried: the same request would get the same answer, so they fall through to drop.
            // Signed out or the refresh failed: keep the write; the session-expired flow takes over.
            code == 401 -> {
                pending.markFailed(w.id, "UNAUTHORIZED")
                false
            }
            // A set logged offline after the session completed (e.g. on another device): send it
            // as a correction instead, which edits or adds that set by its clientSetId.
            error == "SESSION_COMPLETED" && w.method == "POST" && w.path.endsWith(SETS_SUFFIX) -> {
                val clientSetId = clientSetId(w.body) ?: return drop(w, localId, error)
                val key = UUID.nameUUIDFromBytes("correction:${w.idempotencyKey}".toByteArray()).toString()
                val corrected = w.copy(
                    method = "PUT",
                    path = w.path.removeSuffix(SETS_SUFFIX) + "/corrections/sets/" + clientSetId,
                    idempotencyKey = key, attempts = 0, lastError = null,
                )
                pending.rewrite(w.id, corrected.method, corrected.path, key)
                send(corrected)
            }
            // A correction delete of a set that is already gone: nothing left to do.
            error == CORRECTION_SET_NOT_FOUND -> {
                pending.delete(w.id)
                true
            }
            // Pause/resume or a set after the session completed elsewhere: nothing left to do.
            error == "SESSION_COMPLETED" -> {
                pending.delete(w.id)
                true
            }
            else -> drop(w, localId, error ?: "HTTP_$code")
        }
    }

    private fun clientSetId(body: String?): String? = body?.let {
        runCatching { json.parseToJsonElement(it).jsonObject["clientSetId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    private suspend fun drop(w: PendingWriteEntity, localId: String?, reason: String): Boolean {
        pending.delete(w.id)
        val sessionId = localId ?: if (w.path == START_PATH) sessions.byStartKey(w.idempotencyKey)?.localId else null
        sessionId?.let { sessions.setSyncError(it, reason) }
        return true
    }

    /** Distinct workouts with at least one write not yet accepted by the server. */
    suspend fun unsyncedWorkouts(): Int {
        val ids = mutableSetOf<String>()
        var other = 0
        pending.oldest(Int.MAX_VALUE).forEach { w ->
            val id = SESSION_REF.find(w.path)?.groupValues?.get(1)
                ?: if (w.path == START_PATH) sessions.byStartKey(w.idempotencyKey)?.localId else null
            if (id != null) ids += id else other++
        }
        return ids.size + other
    }

    private suspend fun hasPendingStart(localId: String): Boolean {
        val s = sessions.session(localId) ?: return false
        return pending.byKey(s.startKey) != null
    }

    companion object {
        const val DEFAULT_RATE_LIMIT_WAIT_MS = 30_000L
        const val MIN_RATE_LIMIT_WAIT_MS = 1_000L
        /** Cap so a bad header can't park the queue for days. */
        const val MAX_RATE_LIMIT_WAIT_MS = 60 * 60_000L
        const val BAD_RESPONSE = "BAD_RESPONSE"
        const val CORRECTION_SET_NOT_FOUND = "SET_NOT_FOUND"
        private const val SETS_SUFFIX = "/sets"
        private const val TAG = "OutboxProcessor"
        const val START_PATH = "workout-sessions"
        private const val BATCH = 50
        private val JSON = "application/json".toMediaType()
        private val SESSION_REF = Regex("""\{session:([^}]+)}""")
        fun sessionRef(localId: String) = "{session:$localId}"
    }
}
