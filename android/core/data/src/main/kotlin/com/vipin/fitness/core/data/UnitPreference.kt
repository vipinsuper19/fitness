package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.datastore.UserPreferencesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** The user's display units from the local profile cache (refreshed whenever a profile loads). */
interface UnitPreference {
    val units: Flow<UnitSystem>
}

@Singleton
class CachedUnitPreference @Inject constructor(prefs: UserPreferencesRepository) : UnitPreference {
    override val units: Flow<UnitSystem> = prefs.unitSystem
}
