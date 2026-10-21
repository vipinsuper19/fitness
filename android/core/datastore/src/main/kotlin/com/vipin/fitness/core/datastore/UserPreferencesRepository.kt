package com.vipin.fitness.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vipin.fitness.core.common.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile facts cached on the device so launch doesn't depend on the network: a user known to
 * have finished onboarding goes straight to Main, and units are available offline.
 */
interface ProfileFlagsCache {
    suspend fun onboardingCompleted(): Boolean
    suspend fun save(onboardingCompleted: Boolean, units: UnitSystem)
    /** On sign-out, so the next account on this device starts from the server's answer. */
    suspend fun clear()
}

/** Non-secret local preferences. Metric by default (India first). */
@Singleton
class UserPreferencesRepository @Inject constructor(private val dataStore: DataStore<Preferences>) : ProfileFlagsCache {
    val unitSystem: Flow<UnitSystem> = dataStore.data.map { prefs ->
        prefs[UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC
    }

    suspend fun setUnitSystem(units: UnitSystem) {
        dataStore.edit { it[UNIT_SYSTEM] = units.name }
    }

    override suspend fun onboardingCompleted(): Boolean = dataStore.data.first()[ONBOARDING_COMPLETED] ?: false

    override suspend fun save(onboardingCompleted: Boolean, units: UnitSystem) {
        dataStore.edit {
            it[ONBOARDING_COMPLETED] = onboardingCompleted
            it[UNIT_SYSTEM] = units.name
        }
    }

    override suspend fun clear() {
        dataStore.edit {
            it.remove(ONBOARDING_COMPLETED)
            it.remove(UNIT_SYSTEM)
        }
    }

    private companion object {
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
