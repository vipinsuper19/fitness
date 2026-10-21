package com.vipin.fitness.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import com.vipin.fitness.core.data.ActivityLevel
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.Sex
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class OnboardingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val clock = Clock.fixed(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC)
    private val repo = FakeProfileRepository()

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = OnboardingViewModel(repo, clock, saved)

    @Test fun freshProfileStartsAtFirstStep() {
        assertEquals(OnboardingStep.ABOUT, vm().state.value.step)
    }

    @Test fun resumesAtFirstUnansweredStep() {
        repo.profile = Profile(displayName = "V", heightCm = 170.0)
        assertEquals(OnboardingStep.ACTIVITY, vm().state.value.step)
    }

    @Test fun savedStepWinsAfterProcessDeath() {
        assertEquals(OnboardingStep.GOALS, vm(SavedStateHandle(mapOf("onboarding_step" to "GOALS"))).state.value.step)
    }

    @Test fun completedProfileFinishesImmediately() {
        repo.profile = Profile(onboardingCompleted = true)
        assertTrue(vm().state.value.finished)
    }

    @Test fun nextSavesOnlyThisStepsChangedFields() {
        val vm = vm()
        vm.onForm { it.copy(displayName = "Vipin", sex = Sex.MALE) }
        vm.next()
        assertEquals(ProfileChanges(displayName = Field.Set("Vipin"), sex = Field.Set(Sex.MALE)), repo.updates.single().first)
        assertEquals(OnboardingStep.BODY, vm.state.value.step)
    }

    @Test fun nextWithNoChangesAdvancesWithoutCallingServer() {
        val vm = vm()
        vm.next()
        assertTrue(repo.updates.isEmpty())
        assertEquals(OnboardingStep.BODY, vm.state.value.step)
    }

    @Test fun skipDiscardsEditsAndSendsNothing() {
        val vm = vm()
        vm.onForm { it.copy(displayName = "typed") }
        vm.skip()
        assertTrue(repo.updates.isEmpty())
        assertEquals("", vm.state.value.form.displayName)
        assertEquals(OnboardingStep.BODY, vm.state.value.step)
    }

    @Test fun invalidInputBlocksNext() {
        repo.profile = Profile(displayName = "V")
        val vm = vm()
        vm.onForm { it.copy(heightCm = "20") }
        vm.next()
        assertEquals(FieldError.HEIGHT, vm.state.value.errors[ProfileField.HEIGHT])
        assertEquals(OnboardingStep.BODY, vm.state.value.step)
        assertTrue(repo.updates.isEmpty())
        vm.onForm { it.copy(heightCm = "180") }
        assertNull(vm.state.value.errors[ProfileField.HEIGHT])
    }

    @Test fun finishMarksOnboardingComplete() {
        val vm = vm(SavedStateHandle(mapOf("onboarding_step" to "GOALS")))
        vm.skip()
        assertEquals(ProfileChanges(onboardingCompleted = true), repo.updates.single().first)
        assertTrue(vm.state.value.finished)
        assertTrue(repo.profile.onboardingCompleted)
    }

    @Test fun failedSaveStaysAndRetryReusesIdempotencyKey() {
        repo.profile = Profile(displayName = "V", heightCm = 170.0)
        var n = 0
        val vm = vm().apply { newKey = { "k${++n}" } }
        repo.failUpdates = ApiResult.Failure(ApiError.Network)
        vm.onForm { it.copy(activity = ActivityLevel.ACTIVE) }
        vm.next()
        assertNotNull(vm.state.value.error)
        assertEquals(OnboardingStep.ACTIVITY, vm.state.value.step)
        repo.failUpdates = null
        vm.next()
        assertEquals(listOf("k1", "k1"), repo.updates.map { it.second })
        assertEquals(OnboardingStep.GOALS, vm.state.value.step)
        assertFalse(vm.state.value.saving)
    }

    @Test fun backGoesToPreviousStep() {
        val vm = vm()
        vm.next()
        vm.back()
        assertEquals(OnboardingStep.ABOUT, vm.state.value.step)
    }

    @Test fun loadFailureShowsRetry() {
        repo.getResult = { ApiResult.Failure(ApiError.Network) }
        val vm = vm()
        assertNotNull(vm.state.value.loadError)
        repo.getResult = null
        vm.load()
        assertNull(vm.state.value.loadError)
    }

    @Test fun profileEditSavesDiffAndSignsOut() {
        repo.profile = Profile(displayName = "V", weightKg = 70.0, onboardingCompleted = true)
        val session = FakeSessionRepository(signedIn = true)
        val vm = ProfileEditViewModel(repo, session, clock)
        vm.save()
        assertTrue(repo.updates.isEmpty()) // nothing changed
        vm.onForm { it.copy(displayName = "", weight = "71") }
        vm.save()
        assertEquals(ProfileChanges(displayName = Field.Clear, weightKg = Field.Set(71.0)), repo.updates.single().first)
        assertTrue(vm.state.value.saved)
        assertNull(repo.profile.displayName)
        vm.signOut()
        assertEquals(1, session.signOuts)
        assertTrue(vm.state.value.signedOut)
    }
}
