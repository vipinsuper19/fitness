package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NotificationPreferencesApi
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.NotificationPreferencesDto
import com.vipin.fitness.core.network.model.NotificationPreferencesUpdateDto
import com.vipin.fitness.core.network.model.QuietHoursUpdateDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The types this client knows (docs/specs/notification-preferences.md), in display order. Others are hidden. */
enum class NotificationType(val wire: String) {
    WORKOUT_REMINDER("workout_reminder"),
    STREAK_REMINDER("streak_reminder"),
    WATER_REMINDER("water_reminder"),
    FOOD_LOG_REMINDER("food_log_reminder"),
    CHALLENGE_UPDATES("challenge_updates"),
    BADGE_EARNED("badge_earned"),
    PROGRESSION_SUGGESTION("progression_suggestion"),
    ;

    companion object {
        fun fromWire(w: String) = entries.firstOrNull { it.wire == w }
    }
}

/** Local wall-clock times "HH:MM" (24 h) in the profile timezone. */
data class QuietHours(val enabled: Boolean, val start: String, val end: String)

data class NotificationPreferences(
    /** Master switch: off silences everything but keeps [types]. */
    val enabled: Boolean,
    /** Known types the server returned, in [NotificationType] order. Unknown keys are dropped. */
    val types: Map<NotificationType, Boolean>,
    val quietHours: QuietHours,
    /** Profile timezone; read-only here (changed via the profile). */
    val timezone: String,
    val updatedAt: String?,
)

/** A PATCH: only what changed. Quiet-hours start and end always travel together. */
data class NotificationPreferencesChanges(
    val enabled: Boolean? = null,
    val types: Map<NotificationType, Boolean> = emptyMap(),
    val quietEnabled: Boolean? = null,
    /** start to end, both or neither. */
    val quietWindow: Pair<String, String>? = null,
) {
    val isEmpty get() = enabled == null && types.isEmpty() && quietEnabled == null && quietWindow == null

    companion object {
        /** The fields of [edited] that differ from [saved]. */
        fun between(saved: NotificationPreferences, edited: NotificationPreferences) = NotificationPreferencesChanges(
            enabled = edited.enabled.takeIf { it != saved.enabled },
            types = edited.types.filter { (t, on) -> t in saved.types && saved.types[t] != on },
            quietEnabled = edited.quietHours.enabled.takeIf { it != saved.quietHours.enabled },
            quietWindow = (edited.quietHours.start to edited.quietHours.end)
                .takeIf { it != (saved.quietHours.start to saved.quietHours.end) },
        )
    }
}

fun NotificationPreferencesDto.toDomain() = NotificationPreferences(
    enabled = enabled,
    types = NotificationType.entries.mapNotNull { t -> types[t.wire]?.let { t to it } }.toMap(),
    quietHours = QuietHours(quietHours.enabled, quietHours.start, quietHours.end),
    timezone = timezone,
    updatedAt = updatedAt,
)

fun NotificationPreferencesChanges.toDto() = NotificationPreferencesUpdateDto(
    enabled = enabled,
    types = types.takeIf { it.isNotEmpty() }?.mapKeys { it.key.wire },
    quietHours = if (quietEnabled == null && quietWindow == null) null
    else QuietHoursUpdateDto(enabled = quietEnabled, start = quietWindow?.first, end = quietWindow?.second),
)

interface NotificationPreferencesRepository {
    /** Bumped on sign-out and sign-in: a response from an older generation belongs to another user. */
    val userGeneration: StateFlow<Int>
    suspend fun get(): ApiResult<NotificationPreferences>
    suspend fun update(changes: NotificationPreferencesChanges, idempotencyKey: String): ApiResult<NotificationPreferences>
}

/** No cache: the screen asks each time, so nothing outlives a user beyond [userGeneration]. */
@Singleton
class DefaultNotificationPreferencesRepository @Inject constructor(
    private val api: NotificationPreferencesApi,
    private val json: Json,
) : NotificationPreferencesRepository, UserScopedState {
    private val generation = MutableStateFlow(0)
    override val userGeneration: StateFlow<Int> = generation.asStateFlow()

    override suspend fun get() = apiCall(json) { api.get().toDomain() }

    override suspend fun update(changes: NotificationPreferencesChanges, idempotencyKey: String) =
        apiCall(json) { api.update(changes.toDto(), idempotencyKey).toDomain() }

    override suspend fun clearUserState() = generation.update { it + 1 }
}
