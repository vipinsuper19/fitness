package com.vipin.fitness.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.vipin.fitness.core.common.UnitSystem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserPreferencesRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaultsToMetricAndPersistsChoice() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(tmp.root, "prefs.preferences_pb")
        }
        val repo = UserPreferencesRepository(ds)
        assertEquals(UnitSystem.METRIC, repo.unitSystem.first())
        repo.setUnitSystem(UnitSystem.IMPERIAL)
        assertEquals(UnitSystem.IMPERIAL, repo.unitSystem.first())
    }

    @Test fun profileFlagsCacheSavesAndClears() = runTest {
        val ds = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "flags.preferences_pb") }
        val repo = UserPreferencesRepository(ds)
        assertEquals(false, repo.onboardingCompleted())
        repo.save(onboardingCompleted = true, units = UnitSystem.IMPERIAL)
        assertEquals(true, repo.onboardingCompleted())
        assertEquals(UnitSystem.IMPERIAL, repo.unitSystem.first())
        repo.clear()
        assertEquals(false, repo.onboardingCompleted())
        assertEquals(UnitSystem.METRIC, repo.unitSystem.first())
    }
}
