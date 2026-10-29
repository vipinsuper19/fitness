package com.vipin.fitness.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.QuietHours
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeNotificationPreferencesRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.NOTIFICATION_PREFS_VALIDATION_FAILED
import com.vipin.fitness.core.testing.testNotificationPreferences
import com.vipin.fitness.core.designsystem.NotificationSettingsTags as T
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Notification settings (#127): Profile → Notifications through the real MainActivity, with core:testing fakes. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class NotificationSettingsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var prefs: FakeNotificationPreferencesRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    private val water = T.type(NotificationType.WATER_REMINDER.wire)
    private val workout = T.type(NotificationType.WORKOUT_REMINDER.wire)

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        prefs.prefs = testNotificationPreferences()
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    private fun node(t: String) = compose.onNodeWithTag(t)
    private fun click(t: String) {
        waitTag(t)
        try { node(t).performScrollTo() } catch (_: AssertionError) {}
        node(t).performClick(); compose.waitForIdle()
    }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("open_profile")
        click(T.OPEN)
        waitTag(T.SCREEN)
    }

    /** Picks [hour]:00 on the 24 h clock face of the open picker. */
    private fun pick(button: String, hour: Int) {
        click(button); waitTag(T.TIME_PICKER)
        val m = hasContentDescription("$hour hours") and hasAnyAncestor(hasTestTag(T.TIME_PICKER))
        compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().let { check(it.isNotEmpty()) { "no $hour on the clock face" } }
        compose.onAllNodes(m, useUnmergedTree = true)[0].performClick(); compose.waitForIdle()
        click(T.TIME_PICKER_OK)
        until { !present(T.TIME_PICKER) }
    }

    @Test fun showsTheServerValues() {
        open()
        node(T.MASTER).assertIsOn()
        node(workout).assertIsOn()
        node(water).assertIsOff()
        assertTrue(shows("Start 22:00")); assertTrue(shows("End 07:00"))
        assertTrue(shows("22:00 – 07:00 next day"))
        assertTrue(shows("Time zone: Asia/Kolkata"))
        node(T.SAVE).assertIsNotEnabled()
        assertEquals(1, prefs.gets)
    }

    @Test fun patchSendsOnlyTheChangedType() {
        open(); click(water)
        node(water).assertIsOn()
        click(T.SAVE); waitTag(T.SAVED)
        val (c, _) = prefs.updates.single()
        assertEquals(mapOf(NotificationType.WATER_REMINDER to true), c.types)
        assertNull(c.enabled); assertNull(c.quietEnabled); assertNull(c.quietWindow)
    }

    @Test fun togglingBackAndForthSendsNothing() {
        open(); click(water); click(water)
        node(T.SAVE).assertIsNotEnabled()
        assertTrue(prefs.updates.isEmpty())
    }

    @Test fun changingOnlyTheStartSendsStartAndEndAsAPair() {
        open(); pick(T.QUIET_START, 23)
        assertTrue(shows("Start 23:00"))
        click(T.SAVE); waitTag(T.SAVED)
        val (c, _) = prefs.updates.single()
        assertEquals("23:00" to "07:00", c.quietWindow)
        assertNull(c.enabled); assertNull(c.quietEnabled); assertTrue(c.types.isEmpty())
        assertEquals(QuietHours(true, "23:00", "07:00"), prefs.prefs.quietHours)
    }

    @Test fun changingOnlyTheEndSendsStartAndEndAsAPair() {
        open(); pick(T.QUIET_END, 6)
        click(T.SAVE); waitTag(T.SAVED)
        assertEquals("22:00" to "06:00", prefs.updates.single().first.quietWindow)
    }

    @Test fun quietSwitchAloneSendsNoTimes() {
        open(); click(T.QUIET_ENABLED)
        node(T.QUIET_START).assertIsNotEnabled()
        click(T.SAVE); waitTag(T.SAVED)
        val (c, _) = prefs.updates.single()
        assertEquals(false, c.quietEnabled); assertNull(c.quietWindow)
    }

    @Test fun masterOffKeepsEachTypeValue() {
        open(); click(T.MASTER)
        node(T.MASTER).assertIsOff()
        assertTrue(shows("Your choices below are kept."))
        node(workout).assertIsOn().assertIsNotEnabled()
        node(water).assertIsOff().assertIsNotEnabled()
        click(T.SAVE); waitTag(T.SAVED)
        val (c, _) = prefs.updates.single()
        assertEquals(false, c.enabled); assertTrue(c.types.isEmpty())
        click(T.MASTER)
        node(workout).assertIsOn(); node(water).assertIsOff()
        assertEquals(prefs.prefs.types, testNotificationPreferences().types)
    }

    @Test fun startEqualToEndIsBlockedAndNeverSent() {
        open(); pick(T.QUIET_END, 22)
        assertTrue(shows("End 22:00"))
        assertFalse(present(T.QUIET_WINDOW))
        click(T.SAVE)
        waitTag(T.SAVE_ERROR)
        assertTrue(shows("Start and end must be different."))
        assertFalse(present(T.SAVE_RETRY))
        assertTrue(prefs.updates.isEmpty())
        pick(T.QUIET_END, 7) // back to a valid window clears the error
        assertFalse(present(T.SAVE_ERROR))
    }

    @Test fun offlineSaveKeepsEditsAndRetryReusesTheKey() {
        prefs.updateResult = { FAKE_NETWORK_FAILURE }
        open(); click(water); click(T.SAVE)
        waitTag(T.SAVE_ERROR)
        assertTrue(shows("You're offline."))
        node(water).assertIsOn() // edit kept
        prefs.updateResult = null
        click(T.SAVE_RETRY); waitTag(T.SAVED)
        assertEquals(2, prefs.updates.size)
        assertEquals(prefs.updates[0].second, prefs.updates[1].second)
        assertEquals(prefs.updates[0].first, prefs.updates[1].first)
    }

    @Test fun aDifferentChangeSetAfterAFailureGetsANewKey() {
        prefs.updateResult = { FAKE_NETWORK_FAILURE }
        open(); click(water); click(T.SAVE); waitTag(T.SAVE_ERROR)
        prefs.updateResult = null
        click(workout); click(T.SAVE); waitTag(T.SAVED)
        assertNotEquals(prefs.updates[0].second, prefs.updates[1].second)
    }

    @Test fun a400DropsTheKeyShowsTheErrorAndRefetches() {
        prefs.updateResult = { NOTIFICATION_PREFS_VALIDATION_FAILED }
        open(); click(water); click(T.SAVE)
        waitTag(T.SAVE_ERROR)
        assertTrue(shows("Those settings weren't accepted."))
        assertFalse(present(T.SAVE_RETRY))
        until { prefs.gets == 2 }
        until { tagIsOff(water) } // back to the server's value
        prefs.updateResult = null
        click(water); click(T.SAVE); waitTag(T.SAVED)
        assertEquals(2, prefs.updates.size)
        assertNotEquals(prefs.updates[0].second, prefs.updates[1].second)
    }

    private fun tagIsOff(t: String) = try { node(t).assertIsOff(); true } catch (_: AssertionError) { false }

    @Test fun loadOfflineThenRetry() {
        prefs.getResult = { FAKE_NETWORK_FAILURE }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("open_profile"); click(T.OPEN)
        waitTag(T.LOAD_ERROR)
        assertTrue(shows("You're offline."))
        assertFalse(present(T.SCREEN))
        prefs.getResult = null
        click(T.RETRY); waitTag(T.SCREEN)
        node(T.MASTER).assertIsOn()
        assertEquals(2, prefs.gets)
    }

    @Test fun switchingUserResetsTheScreenAndDropsEdits() {
        open(); click(water)
        node(water).assertIsOn()
        prefs.switchUser(testNotificationPreferences(enabled = false, quietHours = QuietHours(false, "23:30", "06:15"), timezone = "Europe/London"))
        until { shows("Time zone: Europe/London") }
        node(T.MASTER).assertIsOff()
        node(water).assertIsOff()
        assertTrue(shows("Start 23:30"))
        node(T.SAVE).assertIsNotEnabled()
        assertTrue(prefs.updates.isEmpty())
    }

    @Test fun aSaveInFlightForThePreviousUserIsDropped() {
        val gate = CompletableDeferred<Unit>()
        prefs.updateGate = gate
        open(); click(water); click(T.SAVE)
        until { prefs.updates.size == 1 }
        prefs.updateGate = null
        prefs.switchUser(testNotificationPreferences(timezone = "Europe/London"))
        until { shows("Time zone: Europe/London") }
        gate.complete(Unit)
        compose.waitForIdle(); shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(shows("Time zone: Europe/London"))
        assertFalse(present(T.SAVED))
    }
}
