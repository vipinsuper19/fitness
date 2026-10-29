package com.vipin.fitness.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConsentRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T06:00:00Z"), ZoneOffset.UTC)

    /** What the server says about who is signed in (null = signed out or offline). */
    private var serverUser: String? = null
    private var fetches = 0

    private fun TestScope.store() = PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.root.resolve("consent.preferences_pb") }
    private fun repo(store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>, version: Int = CONSENT_VERSION) =
        DataStoreConsentRepository(store, clock, { fetches++; serverUser }, version)

    @Test fun signedOutAcceptSavesNothingAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(store())
        assertFalse(r.accept())
        assertFalse(r.hasConsented())
    }

    @Test fun consentIsPerUserAndSurvivesSignOut() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(store())
        r.onSignedIn("u1")
        assertTrue(r.accept())
        assertTrue(r.hasConsented())
        r.clearUserState()
        assertFalse(r.hasConsented())
        r.onSignedIn("u2")
        assertFalse(r.hasConsented()) // another account on this device answers for itself
        r.onSignedIn("u1")
        assertTrue(r.hasConsented()) // same person back: not asked again
    }

    @Test fun upgradeUserSignedInBeforeConsentShippedIsIdentifiedAndTheAnswerSticks() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(store()) // no onSignedIn: the session predates this update
        serverUser = null // offline at first
        assertFalse(r.hasConsented())
        assertFalse(r.accept()) // can't tell who this is: nothing saved, and not reported as saved
        serverUser = "u1"
        r.refreshActiveUser() // app start, back online
        assertFalse(r.hasConsented())
        assertTrue(r.accept())
        assertTrue(r.hasConsented())
        serverUser = null
        val before = fetches
        assertTrue(r.hasConsented()) // next launch, offline: the recorded user is enough
        assertEquals(before, fetches)
    }

    @Test fun raisingTheVersionAsksAgain() = runTest(UnconfinedTestDispatcher()) {
        val store = store()
        val v1 = repo(store, version = 1)
        v1.onSignedIn("u1")
        v1.accept()
        val v2 = repo(store, version = 2)
        assertFalse(v2.hasConsented())
        assertTrue(v2.accept())
        assertTrue(v2.hasConsented())
    }

    @Test fun accountDeletionForgetsThatUsersConsentOnly() = runTest(UnconfinedTestDispatcher()) {
        val r = repo(store())
        r.onSignedIn("u2"); r.accept()
        r.onSignedIn("u1"); r.accept()
        r.forgetActiveUser()
        assertFalse(r.hasConsented())
        r.onSignedIn("u1")
        assertFalse(r.hasConsented()) // deleted user's record is gone
        r.onSignedIn("u2")
        assertTrue(r.hasConsented()) // others untouched
    }
}
