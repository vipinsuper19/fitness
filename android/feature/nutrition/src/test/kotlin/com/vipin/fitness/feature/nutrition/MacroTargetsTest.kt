package com.vipin.fitness.feature.nutrition

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.MacroInput
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.SuggestedMacros
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeMacroSuggestionRepository
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.MainDispatcherRule
import com.vipin.fitness.core.testing.testMacroSuggestion
import com.vipin.fitness.core.testing.testMissingMacroInputs
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class MacroTargetsViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val profiles = FakeProfileRepository(Profile(dailyCalorieTargetKcal = 2000, weightKg = 70.0, dailyProteinTargetG = 90))
    private val suggestions = FakeMacroSuggestionRepository()
    private val units = FakeUnitPreference()
    private var keys = 0
    private fun vm() = MacroTargetsViewModel(profiles, suggestions, units).also { it.newKey = { "k${++keys}" } }

    @Test fun loadsTargetsAndRetriesAfterOffline() {
        profiles.getResult = { FAKE_NETWORK_FAILURE }
        val vm = vm()
        assertEquals(MacroError.OFFLINE, vm.state.value.loadError)
        profiles.getResult = null
        vm.load()
        assertNull(vm.state.value.loadError)
        assertEquals(MacroTargets(90, null, null, 2000), vm.state.value.targets)
    }

    @Test fun suggestThenApplySavesTheGramsOnlyAndUpdatesTargets() {
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
        val vm = vm()
        vm.suggest()
        assertEquals(SuggestedMacros(112, 262, 56, 2000), vm.state.value.suggestion!!.suggestion)
        vm.apply()
        val (changes, key) = profiles.updates.single()
        assertEquals(Field.Set(112), changes.dailyProteinTargetG)
        assertEquals(Field.Set(262), changes.dailyCarbsTargetG)
        assertEquals(Field.Set(56), changes.dailyFatTargetG)
        assertEquals(Field.Unchanged, changes.dailyCalorieTargetKcal) // kcal is derived; the target stays
        assertEquals("k1", key)
        val s = vm.state.value
        assertTrue(s.applied)
        assertNull(s.suggestion)
        assertEquals(MacroTargets(112, 262, 56, 2000), s.targets)
    }

    @Test fun applyRetryReusesTheKeyAndZeroGramsClear() {
        suggestions.result = { ApiResult.Success(testMacroSuggestion(proteinG = 50, carbsG = 0, fatG = 20)) }
        val vm = vm()
        vm.suggest()
        profiles.failUpdates = ApiResult.Failure(ApiError.Network)
        vm.apply()
        assertEquals(MacroError.OFFLINE, vm.state.value.applyError)
        profiles.failUpdates = null
        vm.apply()
        assertEquals(listOf("k1", "k1"), profiles.updates.map { it.second })
        assertEquals(Field.Clear, profiles.updates.last().first.dailyCarbsTargetG)
        assertNull(vm.state.value.targets!!.carbsG)
    }

    @Test fun missingInputsShowNoSuggestionAndApplyDoesNothing() {
        suggestions.result = { ApiResult.Success(testMissingMacroInputs(MacroInput.WEIGHT)) }
        val vm = vm()
        vm.suggest()
        assertEquals(listOf(MacroInput.WEIGHT), vm.state.value.suggestion!!.missingInputs)
        vm.apply()
        assertTrue(profiles.updates.isEmpty())
    }

    @Test fun suggestOfflineThenRetry() {
        val vm = vm()
        vm.suggest()
        assertEquals(MacroError.OFFLINE, vm.state.value.suggestError)
        suggestions.result = { ApiResult.Failure(ApiError.Http(500, "INTERNAL", "x", null)) }
        vm.suggest()
        assertEquals(MacroError.FAILED, vm.state.value.suggestError)
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
        vm.suggest()
        assertNull(vm.state.value.suggestError)
        assertFalse(vm.state.value.suggesting)
    }

    @Test fun aLateSuggestionAfterDismissIsDropped() {
        val gate = CompletableDeferred<Unit>()
        suggestions.gate = gate
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
        val vm = vm()
        vm.suggest()
        assertTrue(vm.state.value.suggesting)
        vm.dismiss()
        gate.complete(Unit)
        assertNull(vm.state.value.suggestion)
        assertFalse(vm.state.value.suggesting)
    }

    @Test fun anOlderSuggestionLosesToANewerOne() {
        val first = CompletableDeferred<Unit>()
        suggestions.gate = first
        suggestions.result = { ApiResult.Success(testMacroSuggestion(proteinG = 1)) }
        val vm = vm()
        vm.suggest()
        suggestions.gate = null
        suggestions.result = { ApiResult.Success(testMacroSuggestion(proteinG = 2)) }
        vm.suggest()
        first.complete(Unit)
        assertEquals(2, vm.state.value.suggestion!!.suggestion!!.proteinG)
    }

    @Test fun unitsFollowThePreference() {
        val vm = vm()
        units.units.value = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, vm.state.value.units)
    }
}

@RunWith(AndroidJUnit4::class)
class MacroFormatTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun gramsAndKcalUseEnInHalfUpAndLakhGrouping() {
        assertEquals("112 g", MacroFormat.grams(res, 112))
        assertEquals("1,000 g", MacroFormat.grams(res, 1000))
        assertEquals("5,599 kcal", MacroFormat.kcal(res, 5599))
        assertEquals("1,00,000 kcal", MacroFormat.kcal(res, 100000))
        assertEquals("Not set", MacroFormat.grams(res, null))
    }

    @Test fun weightFollowsUnits() {
        assertEquals("87.5 kg", MacroFormat.weight(res, 87.5, UnitSystem.METRIC))
        assertEquals("154.3 lb", MacroFormat.weight(res, 70.0, UnitSystem.IMPERIAL))
    }

    @Test fun missingInputsUsePluralsAndLabels() {
        assertEquals("Add 1 detail to your profile to get a suggestion:", MacroFormat.missingTitle(res, listOf(MacroInput.WEIGHT)))
        assertEquals("Add 2 details to your profile to get a suggestion:", MacroFormat.missingTitle(res, listOf(MacroInput.CALORIE_TARGET, MacroInput.WEIGHT)))
        assertEquals("• Daily calorie target", MacroFormat.missingItem(res, MacroInput.CALORIE_TARGET))
        assertEquals("• Weight", MacroFormat.missingItem(res, MacroInput.WEIGHT))
        assertEquals("• Other profile details", MacroFormat.missingItem(res, MacroInput.UNKNOWN))
    }

    @Test fun goalLabels() {
        assertEquals("lose weight", MacroFormat.goal(res, "lose_weight", false))
        assertEquals("no active goal, so maintain", MacroFormat.goal(res, "maintain", true))
        assertEquals("maintain", MacroFormat.goal(res, "something_new", false))
    }
}
