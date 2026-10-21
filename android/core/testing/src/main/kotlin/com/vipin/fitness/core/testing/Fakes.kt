package com.vipin.fitness.core.testing

import com.vipin.fitness.core.data.AuthOutcome
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.ProfileRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeSessionRepository(var signedIn: Boolean = false) : SessionRepository {
    var signInResult: AuthOutcome = AuthOutcome.Success
    var registerResult: AuthOutcome = AuthOutcome.Success
    /** When set, calls suspend until completed: lets tests observe the in-flight state. */
    var gate: CompletableDeferred<Unit>? = null
    val signInCalls = mutableListOf<Pair<String, String>>()
    val registerKeys = mutableListOf<String>()
    var signOuts = 0
    override val sessionExpired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun isSignedIn() = signedIn

    override suspend fun signIn(email: String, password: String): AuthOutcome {
        signInCalls += email to password
        gate?.await()
        if (signInResult == AuthOutcome.Success) signedIn = true
        return signInResult
    }

    override suspend fun register(email: String, password: String, displayName: String?, idempotencyKey: String): AuthOutcome {
        registerKeys += idempotencyKey
        gate?.await()
        if (registerResult == AuthOutcome.Success) signedIn = true
        return registerResult
    }

    override suspend fun signOut() {
        signOuts++
        signedIn = false
    }
}

/** Applies [ProfileChanges] the way the server does: Unchanged keeps, Clear nulls, Set replaces. */
class FakeProfileRepository(var profile: Profile = Profile()) : ProfileRepository {
    var getResult: (() -> ApiResult<Profile>)? = null
    var failUpdates: ApiResult.Failure? = null
    val updates = mutableListOf<Pair<ProfileChanges, String>>()

    override suspend fun get(): ApiResult<Profile> = getResult?.invoke() ?: ApiResult.Success(profile)

    override suspend fun update(changes: ProfileChanges, idempotencyKey: String): ApiResult<Profile> {
        updates += changes to idempotencyKey
        failUpdates?.let { return it }
        profile = profile.apply(changes)
        return ApiResult.Success(profile)
    }
}

fun Profile.apply(c: ProfileChanges): Profile {
    fun <T> Field<T>.or(old: T?): T? = when (this) {
        Field.Unchanged -> old
        Field.Clear -> null
        is Field.Set -> value
    }
    return copy(
        displayName = c.displayName.or(displayName),
        birthYear = c.birthYear.or(birthYear),
        sex = c.sex.or(sex),
        heightCm = c.heightCm.or(heightCm),
        weightKg = c.weightKg.or(weightKg),
        activityLevel = c.activityLevel.or(activityLevel),
        experienceLevel = c.experienceLevel.or(experienceLevel),
        dietPreference = c.dietPreference.or(dietPreference),
        unitSystem = c.unitSystem ?: unitSystem,
        dailyCalorieTargetKcal = c.dailyCalorieTargetKcal.or(dailyCalorieTargetKcal),
        dailyWaterTargetMl = c.dailyWaterTargetMl.or(dailyWaterTargetMl),
        onboardingCompleted = c.onboardingCompleted ?: onboardingCompleted,
    )
}
