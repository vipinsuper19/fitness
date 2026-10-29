package com.vipin.fitness.feature.onboarding

import com.vipin.fitness.core.data.NotificationPreferencesChanges
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.data.QuietHours
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeNotificationPreferencesRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.NOTIFICATION_PREFS_VALIDATION_FAILED
import com.vipin.fitness.core.testing.testNotificationPreferences
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationSettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeNotificationPreferencesRepository()
    private var keys = 0
    private fun vm() = NotificationSettingsViewModel(repo).also { it.newKey = { "k${++keys}" } }

    @Test fun loadsDefaults() {
        val s = vm().state.value
        assertFalse(s.loading)
        assertEquals(testNotificationPreferences(), s.draft)
        assertFalse(s.dirty)
    }

    @Test fun offlineLoadShowsErrorAndRetryRecovers() {
        repo.getResult = { FAKE_NETWORK_FAILURE }
        val vm = vm()
        assertEquals(NotificationError.OFFLINE, vm.state.value.loadError)
        assertNull(vm.state.value.draft)
        repo.getResult = null
        vm.load()
        assertNull(vm.state.value.loadError)
        assertEquals(testNotificationPreferences(), vm.state.value.draft)
    }

    @Test fun serverErrorLoadIsFailed() {
        repo.getResult = { ApiResult.Failure(ApiError.Http(500, "INTERNAL", "x", null)) }
        assertEquals(NotificationError.FAILED, vm().state.value.loadError)
    }

    @Test fun patchesOnlyTheChangedType() {
        val vm = vm()
        vm.setType(NotificationType.WATER_REMINDER, true)
        assertTrue(vm.state.value.dirty)
        vm.save()
        assertEquals(listOf(NotificationPreferencesChanges(types = mapOf(NotificationType.WATER_REMINDER to true)) to "k1"), repo.updates)
        val s = vm.state.value
        assertTrue(s.savedOk)
        assertFalse(s.dirty)
        assertEquals("2026-10-29T08:00:00.000Z", s.saved!!.updatedAt)
    }

    @Test fun masterOffKeepsPerTypeChoices() {
        val vm = vm()
        vm.setEnabled(false)
        vm.save()
        assertEquals(NotificationPreferencesChanges(enabled = false), repo.updates.single().first)
        val p = vm.state.value.draft!!
        assertFalse(p.enabled)
        assertEquals(testNotificationPreferences().types, p.types)
    }

    @Test fun changingOnlyTheEndStillSendsStartAndEnd() {
        val vm = vm()
        vm.setQuietEnd("06:30")
        vm.save()
        assertEquals("22:00" to "06:30", repo.updates.single().first.quietWindow)
        assertEquals(QuietHours(true, "22:00", "06:30"), vm.state.value.saved!!.quietHours)
    }

    @Test fun quietEnabledAloneDoesNotSendTimes() {
        val vm = vm()
        vm.setQuietEnabled(false)
        vm.save()
        assertEquals(NotificationPreferencesChanges(quietEnabled = false), repo.updates.single().first)
    }

    @Test fun startEqualToEndIsRejectedBeforeSending() {
        val vm = vm()
        vm.setQuietStart("07:00")
        assertTrue(vm.state.value.sameTimes)
        vm.save()
        assertEquals(NotificationError.SAME_TIMES, vm.state.value.saveError)
        assertTrue(repo.updates.isEmpty())
        vm.setQuietStart("21:30")
        assertNull(vm.state.value.saveError)
        vm.save()
        assertEquals("21:30" to "07:00", repo.updates.single().first.quietWindow)
    }

    @Test fun badTimesAreIgnored() {
        val vm = vm()
        vm.setQuietStart("24:00")
        vm.setQuietEnd("7:00")
        assertFalse(vm.state.value.dirty)
    }

    @Test fun unchangedSaveSendsNothing() {
        val vm = vm()
        vm.setType(NotificationType.BADGE_EARNED, false)
        vm.setType(NotificationType.BADGE_EARNED, true)
        vm.save()
        assertTrue(repo.updates.isEmpty())
    }

    @Test fun retryReusesTheKeyAndNewChangesGetANewOne() {
        val vm = vm()
        repo.updateResult = { FAKE_NETWORK_FAILURE }
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        assertEquals(NotificationError.OFFLINE, vm.state.value.saveError)
        assertTrue(vm.state.value.dirty) // edits kept for Retry
        vm.save()
        assertEquals(listOf("k1", "k1"), repo.updates.map { it.second })
        vm.setType(NotificationType.FOOD_LOG_REMINDER, true)
        repo.updateResult = null
        vm.save()
        assertEquals("k2", repo.updates.last().second)
        vm.setType(NotificationType.FOOD_LOG_REMINDER, false)
        vm.save()
        assertEquals("k3", repo.updates.last().second)
    }

    @Test fun badRequestIsTerminalShowsErrorAndRefetches() {
        val vm = vm()
        val gets = repo.gets
        repo.updateResult = { NOTIFICATION_PREFS_VALIDATION_FAILED }
        repo.prefs = repo.prefs.copy(types = repo.prefs.types + (NotificationType.STREAK_REMINDER to false)) // changed elsewhere
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        val s = vm.state.value
        assertEquals(NotificationError.INVALID, s.saveError)
        assertEquals(gets + 1, repo.gets)
        assertEquals(repo.prefs, s.draft)
        assertFalse(s.dirty)
        // The next save is a new request with a new key.
        repo.updateResult = null
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        assertEquals("k2", repo.updates.last().second)
    }

    @Test fun responseReplacesLocalStateEvenWithServerSideMerges() {
        val vm = vm()
        repo.prefs = repo.prefs.copy(enabled = false) // another device turned it off
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        assertFalse(vm.state.value.draft!!.enabled)
        assertFalse(vm.state.value.saved!!.enabled)
    }

    @Test fun staleLoadAfterASaveIsDropped() {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        repo.getGate = gate
        vm.load() // in flight with the old values
        repo.getGate = null
        vm.setQuietEnabled(false)
        vm.save()
        gate.complete(Unit)
        assertFalse(vm.state.value.saved!!.quietHours.enabled)
        assertFalse(vm.state.value.draft!!.quietHours.enabled)
    }

    @Test fun olderLoadIsDroppedForANewerOne() {
        val gate = CompletableDeferred<Unit>()
        repo.getGate = gate
        val vm = vm()
        repo.getGate = null
        repo.prefs = repo.prefs.copy(timezone = "America/New_York")
        vm.load()
        assertEquals("America/New_York", vm.state.value.draft!!.timezone)
        gate.complete(Unit)
        assertEquals("America/New_York", vm.state.value.draft!!.timezone)
    }

    @Test fun anotherUserSignsInDropsTheOldUsersResponsesAndState() {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        repo.updateGate = gate
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        repo.updateGate = null
        repo.switchUser(testNotificationPreferences(timezone = "Europe/London"))
        gate.complete(Unit) // the first user's save answers late
        val s = vm.state.value
        assertEquals("Europe/London", s.draft!!.timezone)
        assertFalse(s.draft!!.types.getValue(NotificationType.WATER_REMINDER))
        assertFalse(s.dirty)
        assertFalse(s.saving)
        assertFalse(s.savedOk)
    }

    @Test fun editsAreLockedWhileSaving() {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        repo.updateGate = gate
        vm.setType(NotificationType.WATER_REMINDER, true)
        vm.save()
        vm.setEnabled(false)
        vm.save()
        assertTrue(vm.state.value.draft!!.enabled)
        gate.complete(Unit)
        assertEquals(1, repo.updates.size)
    }

    @Test fun discardRestoresSaved() {
        val vm = vm()
        vm.setEnabled(false)
        vm.discard()
        assertFalse(vm.state.value.dirty)
    }
}
