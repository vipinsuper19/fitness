package com.vipin.fitness.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vipin.fitness.core.common.UnitSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Non-secret local preferences. Metric by default (India first). */
@Singleton
class UserPreferencesRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    val unitSystem: Flow<UnitSystem> = dataStore.data.map { prefs ->
        prefs[UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: UnitSystem.METRIC
    }

    suspend fun setUnitSystem(units: UnitSystem) {
        dataStore.edit { it[UNIT_SYSTEM] = units.name }
    }

    private companion object {
        val UNIT_SYSTEM = stringPreferencesKey("unit_system")
    }
}
