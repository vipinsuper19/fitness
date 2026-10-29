package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.network.ApiResult
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** The profile's timezone, or [APP_ZONE] when it is missing or not a zone this device knows. */
fun Profile.zoneOrDefault(): ZoneId =
    timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: APP_ZONE

/** Today's local date in [zone]. */
fun Clock.today(zone: ZoneId): LocalDate = LocalDate.now(withZone(zone))

/**
 * The signed-in user's day boundary: the profile timezone, cached after the first fetch and
 * [APP_ZONE] until a profile loads (a failed fetch is retried on the next call).
 *
 * Every profile [ProfileRepository] returns (get or update) refreshes the cache via [remember], so a
 * timezone change applies at once. It is [UserScopedState]: sign-out, sign-in and account deletion
 * clear it, so a second account never inherits the first one's zone. [profiles] is a Provider
 * because the repository also depends on this cache.
 */
@Singleton
class ProfileZone @Inject constructor(private val profiles: Provider<ProfileRepository>) : UserScopedState {
    @Volatile private var cached: ZoneId? = null
    /** Bumped on every clear, so a fetch that started for the previous user isn't cached. */
    @Volatile private var generation = 0

    suspend fun zone(): ZoneId {
        cached?.let { return it }
        val gen = generation
        return when (val r = profiles.get().get()) {
            is ApiResult.Success -> r.value.zoneOrDefault().also { if (gen == generation) cached = it }
            is ApiResult.Failure -> APP_ZONE
        }
    }

    /** A fresh profile from the server (load or update). */
    fun remember(profile: Profile) {
        cached = profile.zoneOrDefault()
    }

    override suspend fun clearUserState() {
        generation++
        cached = null
    }
}
