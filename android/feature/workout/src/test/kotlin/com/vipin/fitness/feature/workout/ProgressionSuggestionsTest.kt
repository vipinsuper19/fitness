package com.vipin.fitness.feature.workout

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.progression.ApplyOutcome
import com.vipin.fitness.core.data.progression.ProgressionInputs
import com.vipin.fitness.core.data.progression.ProgressionReason
import com.vipin.fitness.core.data.progression.ProgressionStep
import com.vipin.fitness.core.data.progression.ProgressionSuggestion
import com.vipin.fitness.core.data.progression.Prescription
import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.designsystem.ProgressionTags
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FakeProgressionRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.PROGRESSION_PLAN_ID
import com.vipin.fitness.core.testing.PROGRESSION_WORKOUT_ID
import com.vipin.fitness.core.testing.testProgressionData
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private class ProgressionTestClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
    override fun instant(): Instant = now
}

private val K0 = ProgressionSuggestion.keyOf(PROGRESSION_WORKOUT_ID, 0)
private val K1 = ProgressionSuggestion.keyOf(PROGRESSION_WORKOUT_ID, 1)
private val K2 = ProgressionSuggestion.keyOf(PROGRESSION_WORKOUT_ID, 2)

class ProgressionSuggestionsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakeProgressionRepository().apply { loads += ApiResult.Success(testProgressionData()) }
    private val units = FakeUnitPreference()
    private val clock = ProgressionTestClock(Instant.parse("2026-10-29T10:00:00Z"))
    private var n = 0
    private fun vm() = ProgressionSuggestionsViewModel(repo, FakeWorkoutRepository(), units, clock).apply {
        newKey = { "k${++n}" }
        load(PROGRESSION_PLAN_ID)
    }

    @Test fun nothingAppliesAutomatically() {
        val vm = vm()
        assertEquals(3, vm.state.value.rows.size)
        assertTrue(repo.applyCalls.isEmpty())
        assertFalse(vm.state.value.canApply)
        vm.apply()
        assertTrue(repo.applyCalls.isEmpty())
    }

    @Test fun holdCannotBeAcceptedAndSkipExcludes() {
        val vm = vm()
        vm.accept(K1)
        assertNull(vm.state.value.decisions[K1])
        vm.accept(K0); vm.accept(K2); vm.skip(K2)
        assertEquals(setOf(K0), vm.state.value.acceptedKeys)
    }

    @Test fun applySendsOnePatchWithEveryAcceptedChangeThenRefetches() {
        val vm = vm()
        vm.accept(K0); vm.accept(K2)
        repo.loads.clear(); repo.loads += ApiResult.Success(testProgressionData(version = 5, workoutId = "new-w"))
        vm.apply()
        val call = repo.applyCalls.single()
        assertEquals(setOf(K0, K2), call.acceptedKeys)
        assertEquals(4, call.body!!.baseVersion)
        assertEquals(listOf(41.0, null, 27.0), call.body!!.workouts.single().exercises.map { it.targetWeightKg })
        assertEquals(ApplyStatus.Applied(5), vm.state.value.status)
        assertEquals(2, repo.loadCalls.size)
        assertEquals(5, vm.state.value.data!!.planVersion)
        assertTrue(vm.state.value.decisions.isEmpty())
    }

    @Test fun retryReusesTheKeyUntilTheChangeSetChanges() {
        val vm = vm()
        repo.applies += ApplyOutcome.Offline
        vm.accept(K0)
        vm.apply(); vm.apply()
        assertEquals(ApplyStatus.Offline, vm.state.value.status)
        vm.accept(K2)
        vm.apply()
        assertEquals(listOf("k1", "k1", "k2"), repo.applyCalls.map { it.key })
    }

    @Test fun inProgressKeepsTheKey() {
        val vm = vm()
        repo.applies += ApplyOutcome.InProgress
        vm.accept(K0); vm.apply(); vm.apply()
        assertEquals(listOf("k1", "k1"), repo.applyCalls.map { it.key })
    }

    @Test fun versionConflictShowsCurrentVersionRefetchesAndNeedsReconfirm() {
        val vm = vm()
        repo.applies += ApplyOutcome.VersionConflict(5)
        repo.loads.clear(); repo.loads += ApiResult.Success(testProgressionData(version = 5))
        vm.accept(K0); vm.apply()
        assertEquals(ApplyStatus.Conflict(5), vm.state.value.status)
        assertEquals(5, vm.state.value.data!!.planVersion)
        assertTrue(vm.state.value.decisions.isEmpty())
        assertFalse(vm.state.value.canApply)
        repo.applies.clear(); repo.applies += ApplyOutcome.Applied(6)
        vm.accept(K0); vm.apply()
        assertEquals(listOf("k1", "k2"), repo.applyCalls.map { it.key })
        assertEquals(5, repo.applyCalls[1].baseVersion)
    }

    @Test fun keyReusedDropsTheKeyAndRefetches() {
        val vm = vm()
        repo.applies += ApplyOutcome.KeyReused
        vm.accept(K0); vm.apply()
        assertEquals(ApplyStatus.KeyReused, vm.state.value.status)
        assertEquals(2, repo.loadCalls.size)
        vm.apply()
        assertEquals(listOf("k1", "k2"), repo.applyCalls.map { it.key })
    }

    @Test fun planArchivedDisablesApply() {
        val vm = vm()
        repo.applies += ApplyOutcome.PlanArchived
        vm.accept(K0); vm.apply()
        assertEquals(ApplyStatus.Archived, vm.state.value.status)
        assertFalse(vm.state.value.canApply)
    }

    @Test fun applyRateLimitHoldsUntilRetryAfterWithTheSameKey() {
        val vm = vm()
        repo.applies += ApplyOutcome.RateLimited(30_000)
        repo.applies += ApplyOutcome.Applied(5)
        vm.accept(K0); vm.apply()
        assertEquals(ApplyStatus.RateLimited(30_000), vm.state.value.status)
        clock.now = clock.now.plusSeconds(10)
        vm.apply()
        assertEquals(1, repo.applyCalls.size)
        assertEquals(ApplyStatus.RateLimited(20_000), vm.state.value.status)
        clock.now = clock.now.plusSeconds(21)
        vm.apply()
        assertEquals(listOf("k1", "k1"), repo.applyCalls.map { it.key })
    }

    @Test fun suggestionsGet429IsRateLimitedWithRetry() {
        repo.loads.clear()
        repo.loads += ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "slow", null, retryAfterMs = 12_000))
        repo.loads += ApiResult.Success(testProgressionData())
        val vm = vm()
        assertEquals(ProgressionLoadError.RATE_LIMITED, vm.state.value.loadError)
        assertEquals(12_000L, vm.state.value.loadRetryAfterMs)
        vm.retry()
        assertNull(vm.state.value.loadError)
        assertEquals(3, vm.state.value.rows.size)
    }

    @Test fun loadErrors() {
        repo.loads.clear(); repo.loads += ApiResult.Failure(ApiError.Network)
        assertEquals(ProgressionLoadError.OFFLINE, vm().state.value.loadError)
        repo.loads.clear(); repo.loads += ApiResult.Failure(ApiError.Http(409, "PLAN_ARCHIVED", "a", null))
        assertEquals(ProgressionLoadError.ARCHIVED, vm().state.value.loadError)
    }

    @Test fun staleLoadIsIgnored() {
        val vm = vm()
        val gate = CompletableDeferred<Unit>()
        repo.loadGate = gate
        repo.loads.clear(); repo.loads += ApiResult.Success(testProgressionData(version = 9))
        vm.retry() // suspended, then superseded
        repo.loadGate = null
        repo.loads.clear(); repo.loads += ApiResult.Success(testProgressionData(version = 7))
        vm.retry()
        assertEquals(7, vm.state.value.data!!.planVersion)
        gate.complete(Unit)
        assertEquals(7, vm.state.value.data!!.planVersion)
    }

    @Test fun anotherUsersResultsAreDropped() {
        val vm = vm()
        vm.accept(K0)
        val gate = CompletableDeferred<Unit>()
        repo.applyGate = gate
        vm.apply()
        repo.userGeneration.value = 1 // signed out / another user
        assertNull(vm.state.value.data)
        gate.complete(Unit)
        assertNull(vm.state.value.status)
        assertNull(vm.state.value.data)
    }

    @Test fun unitsFollowThePreference() {
        val vm = vm()
        units.units.value = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, vm.state.value.units)
    }
}

class ProgressionFormatTest {
    @Test fun weightUsesEnInAndTheUnitSystem() {
        assertEquals("41 kg", ProgressionFormat.weight(41.0, UnitSystem.METRIC))
        assertEquals("27.5 kg", ProgressionFormat.weight(27.5, UnitSystem.METRIC))
        assertEquals("90.4 lb", ProgressionFormat.weight(41.0, UnitSystem.IMPERIAL))
        assertEquals("1,00,000 kg", ProgressionFormat.weight(100000.0, UnitSystem.METRIC))
    }

    @Test fun prescriptions() {
        assertEquals("3 \u00d7 8\u201310 \u00b7 41 kg", ProgressionFormat.prescription(Prescription(3, 8, 10, 41.0), TrackingType.REPS_WEIGHT, UnitSystem.METRIC))
        assertEquals("3 \u00d7 1 min 5 s", ProgressionFormat.prescription(Prescription(3, durationSeconds = 65), TrackingType.DURATION, UnitSystem.METRIC))
        assertEquals("3 \u00d7 45 s", ProgressionFormat.prescription(Prescription(3, durationSeconds = 45), TrackingType.DURATION, UnitSystem.METRIC))
        assertEquals("3 \u00d7 12", ProgressionFormat.prescription(Prescription(3, 12, 12), TrackingType.REPS, UnitSystem.METRIC))
        assertEquals("2 min", ProgressionFormat.duration(120))
    }

    @Test fun cooldownIsShownInIst() {
        assertEquals("1 nov, 6:30 pm", ProgressionFormat.dateTime(Instant.parse("2026-11-01T13:00:00Z")).lowercase())
    }

    @Test fun reasonsHaveResourcesAndAFallback() {
        ProgressionReason.entries.forEach { ProgressionFormat.reasonRes(it, false) }
        assertEquals(R.string.progression_reason_unknown, ProgressionFormat.reasonRes(ProgressionReason.UNKNOWN, false))
        assertEquals(R.string.progression_reason_rep_ceiling_variations, ProgressionFormat.reasonRes(ProgressionReason.REP_CEILING, true))
        assertEquals(R.string.progression_reason_rep_ceiling, ProgressionFormat.reasonRes(ProgressionReason.REP_CEILING, false))
        assertEquals(ProgressionReason.UNKNOWN, ProgressionReason.of("SOMETHING_NEW"))
    }

    @Test fun whyLineComesFromInputs() {
        assertTrue(ProgressionFormat.whyParts(null).isEmpty())
        val up = ProgressionFormat.whyParts(ProgressionInputs(3, 3, 3, 3, false, null, ProgressionStep("percent_increase", 2.5, 10.0, true, true)))
        assertEquals(
            listOf(
                ProgressionFormat.Part(R.string.progression_why_sessions, listOf(3, 3, 3)),
                ProgressionFormat.Part(R.string.progression_why_effort, listOf(3)),
                ProgressionFormat.Part(R.string.progression_why_step_up, listOf("2.5")),
                ProgressionFormat.Part(R.string.progression_why_capped, listOf("10")),
                ProgressionFormat.Part(R.string.progression_why_min_step),
            ),
            up,
        )
        val down = ProgressionFormat.whyParts(ProgressionInputs(3, 2, 0, null, true, Instant.parse("2026-11-01T13:00:00Z"), ProgressionStep("deload", -10.0, null, false, false)))
        assertEquals(ProgressionFormat.Part(R.string.progression_why_step_down, listOf("10")), down[1])
        assertEquals(R.string.progression_why_cooldown, down[2].res)
        // Unknown rule: nothing invented.
        assertEquals(1, ProgressionFormat.whyParts(ProgressionInputs(3, 1, 1, null, false, null, ProgressionStep("new", 3.0, null, false, false))).size)
    }
}

@RunWith(AndroidJUnit4::class)
class ProgressionSuggestionsSectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rowsShowValuesReasonsWhyAndApplyNeedsAnAccept() {
        var state = ProgressionUiState(planId = PROGRESSION_PLAN_ID, data = testProgressionData())
        var accepted: String? = null
        compose.setContent { ProgressionSuggestionsSection(state, { accepted = it }, {}, {}, {}) }
        compose.onNodeWithTag(ProgressionTags.value(K0)).assertTextContains("40 kg", substring = true).assertTextContains("41 kg", substring = true)
        compose.onNodeWithTag(ProgressionTags.reason(K1)).assertTextContains("1 Nov", substring = true)
        compose.onNodeWithTag(ProgressionTags.reason(K2)).assertTextContains("Lighten the load", substring = true)
        compose.onNodeWithTag(ProgressionTags.why(K0)).assertTextContains("Met target in 3 of 3 sessions (needs 3) · hardest effort 3/5 · +2.5% step")
        compose.onNodeWithTag(ProgressionTags.APPLY).assertIsNotEnabled()
        compose.onNodeWithTag(ProgressionTags.accept(K1)).assertDoesNotExist()
        compose.onNodeWithTag(ProgressionTags.accept(K0)).performClick()
        assertEquals(K0, accepted)
    }

    @Test fun acceptedEnablesApply() {
        val state = ProgressionUiState(planId = PROGRESSION_PLAN_ID, data = testProgressionData(), decisions = mapOf(K0 to SuggestionDecision.ACCEPT))
        compose.setContent { ProgressionSuggestionsSection(state, {}, {}, {}, {}) }
        compose.onNodeWithTag(ProgressionTags.APPLY).assertIsEnabled().assertTextContains("Apply accepted (1)")
    }

    @Test fun rateLimitedLoadOffersRetry() {
        var retried = false
        val state = ProgressionUiState(planId = PROGRESSION_PLAN_ID, loadError = ProgressionLoadError.RATE_LIMITED, loadRetryAfterMs = 12_000)
        compose.setContent { ProgressionSuggestionsSection(state, {}, {}, {}, { retried = true }) }
        compose.onNodeWithTag(ProgressionTags.ERROR).assertTextContains("Try again in 12 s", substring = true)
        compose.onNodeWithTag(ProgressionTags.RETRY).performClick()
        assertTrue(retried)
    }

    @Test fun unknownReasonUsesTheGenericFallback() {
        val d = testProgressionData()
        val odd = d.copy(rows = listOf(d.rows[0].copy(suggestion = d.rows[0].suggestion.copy(reason = ProgressionReason.UNKNOWN, inputs = null))))
        compose.setContent { ProgressionSuggestionsSection(ProgressionUiState(data = odd), {}, {}, {}, {}) }
        compose.onNodeWithTag(ProgressionTags.reason(K0)).assertTextContains("Based on your recent sessions.")
        compose.onNodeWithTag(ProgressionTags.why(K0)).assertDoesNotExist()
    }
}
