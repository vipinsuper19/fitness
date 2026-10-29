package com.vipin.fitness.core.network.model

import kotlinx.serialization.Serializable

/** Contract `NotificationPreferences` (GET and PATCH response). `types` is a map so new server types decode. */
@Serializable
data class NotificationPreferencesDto(
    val enabled: Boolean,
    val types: Map<String, Boolean>,
    val quietHours: QuietHoursDto,
    val timezone: String,
    val updatedAt: String? = null,
)

@Serializable
data class QuietHoursDto(val enabled: Boolean, val start: String, val end: String)

/** Contract `NotificationPreferencesUpdate`: a partial merge; null fields are omitted (encodeDefaults = false). */
@Serializable
data class NotificationPreferencesUpdateDto(
    val enabled: Boolean? = null,
    val types: Map<String, Boolean>? = null,
    val quietHours: QuietHoursUpdateDto? = null,
)

@Serializable
data class QuietHoursUpdateDto(val enabled: Boolean? = null, val start: String? = null, val end: String? = null)
