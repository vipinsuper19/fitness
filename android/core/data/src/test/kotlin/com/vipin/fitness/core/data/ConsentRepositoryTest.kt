package com.vipin.fitness.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConsentRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(scope: TestScope, file: File = File(tmp.root, "consent.preferences_pb")) = DataStoreConsentRepository(
        PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { file },
        Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC),
    )

    @Test fun nobodySignedInMeansNoConsentAndAcceptIsANoOp() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(this)
        r.accept()
        assertFalse(r.hasConsented())
        r.onSignedIn("u1")
        assertFalse(r.hasConsented())
    }

    @Test fun consentIsPerUserAndSurvivesSignOutAndRestart() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(this)
        r.onSignedIn("u1")
        r.accept()
        assertTrue(r.hasConsented())
        r.clearUserState()
        assertFalse(r.hasConsented())
        r.onSignedIn("u2")
        assertFalse(r.hasConsented()) // another account on this device answers for itself
        r.onSignedIn("u1")
        assertTrue(r.hasConsented()) // same person back: not asked again
    }
}
