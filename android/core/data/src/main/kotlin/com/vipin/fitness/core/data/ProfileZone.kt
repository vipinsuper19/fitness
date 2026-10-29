package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.network.ApiResult
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** The profile's timezone, or [APP_ZONE] when it is missing or not a zone this device knows. */
fun Profile.zoneOrDefault(): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: APP_ZONE

/** Today's local date in [zone]. */
fun Clock.today(zone: ZoneId): LocalDate = LocalDate.now(withZone(zone))

/**
 * The user's day boundary: the profile timezone, fetched once and cached; [APP_ZONE] until a
 * profile loads (a failed fetch is retried on the next call).
 */
@Singleton
class ProfileZone @Inject constructor(private val profiles: ProfileRepository) {
    @Volatile private var cached: ZoneId? = null

    suspend fun zone(): ZoneId = cached ?: when (val r = profiles.get()) {
        is ApiResult.Success -> r.value.zoneOrDefault().also { cached = it }
        is ApiResult.Failure -> APP_ZONE
    }
}
