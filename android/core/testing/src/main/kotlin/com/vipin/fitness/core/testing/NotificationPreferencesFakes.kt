package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.NotificationPreferences
import com.vipin.fitness.core.data.NotificationPreferencesChanges
import com.vipin.fitness.core.data.NotificationPreferencesRepository
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.data.QuietHours
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Spec defaults: all on except water and food; quiet hours on, 22:00 to 07:00, Asia/Kolkata. */
fun testNotificationPreferences(
    enabled: Boolean = true,
    types: Map<NotificationType, Boolean> = NotificationType.entries.associateWith {
        it != NotificationType.WATER_REMINDER && it != NotificationType.FOOD_LOG_REMINDER
    },
    quietHours: QuietHours = QuietHours(true, "22:00", "07:00"),
    timezone: String = "Asia/Kolkata",
    updatedAt: String? = null,
) = NotificationPreferences(enabled, types, quietHours, timezone, updatedAt)

/** 400 VALIDATION_FAILED, as for `start == end` or an unknown type. */
val NOTIFICATION_PREFS_VALIDATION_FAILED = ApiResult.Failure(ApiError.Http(400, "VALIDATION_FAILED", "invalid", null))

/**
 * In-memory server: GET returns [prefs]; PATCH merges like the backend and records each call.
 * [getResult]/[updateResult] override the answer; [getGate]/[updateGate] hold a call open to race it.
 */
class FakeNotificationPreferencesRepository(var prefs: NotificationPreferences = testNotificationPreferences()) :
    NotificationPreferencesRepository {
    override val userGeneration = MutableStateFlow(0)
    var getResult: (() -> ApiResult<NotificationPreferences>)? = null
    var updateResult: (() -> ApiResult<NotificationPreferences>)? = null
    var getGate: CompletableDeferred<Unit>? = null
    var updateGate: CompletableDeferred<Unit>? = null
    var gets = 0
    val updates = mutableListOf<Pair<NotificationPreferencesChanges, String>>()

    /** Simulates sign-out then another user signing in. */
    fun switchUser(next: NotificationPreferences) {
        prefs = next
        userGeneration.value++
    }

    override suspend fun get(): ApiResult<NotificationPreferences> {
        gets++
        val override = getResult
        val snapshot = prefs
        getGate?.await()
        return override?.invoke() ?: ApiResult.Success(snapshot)
    }

    override suspend fun update(changes: NotificationPreferencesChanges, idempotencyKey: String): ApiResult<NotificationPreferences> {
        updates += changes to idempotencyKey
        val override = updateResult
        updateGate?.await()
        override?.let { return it() }
        val q = prefs.quietHours
        val start = changes.quietWindow?.first ?: q.start
        val end = changes.quietWindow?.second ?: q.end
        if (start == end) return NOTIFICATION_PREFS_VALIDATION_FAILED
        prefs = prefs.copy(
            enabled = changes.enabled ?: prefs.enabled,
            types = prefs.types + changes.types,
            quietHours = QuietHours(changes.quietEnabled ?: q.enabled, start, end),
            updatedAt = "2026-10-29T08:00:00.000Z",
        )
        return ApiResult.Success(prefs)
    }
}
