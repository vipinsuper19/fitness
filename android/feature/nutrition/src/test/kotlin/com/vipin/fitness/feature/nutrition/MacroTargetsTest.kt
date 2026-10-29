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
    private val zone = com.vipin.fitness.core.data.ProfileZone { profiles }
    private fun vm() = MacroTargetsViewModel(profiles, suggestions, units, zone).also { it.newKey = { "k${++keys}" } }

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

    // --- Manual editing (P/C/F) ---

    private fun editing(): MacroTargetsViewModel = vm().also { it.edit() }

    @Test fun editStartsFromTheCurrentTargets() {
        val vm = editing()
        assertEquals(MacroEdit("90", "", ""), vm.state.value.edit)
        assertFalse(vm.state.value.canSave) // nothing changed yet
    }

    @Test fun saveSendsOnlyTheChangedMacroFields() {
        val vm = editing()
        vm.onField(MacroField.CARBS, "250")
        vm.onField(MacroField.FAT, " 60 ")
        assertTrue(vm.state.value.canSave)
        vm.save()
        val (c, key) = profiles.updates.single()
        assertEquals(Field.Unchanged, c.dailyProteinTargetG)
        assertEquals(Field.Set(250), c.dailyCarbsTargetG)
        assertEquals(Field.Set(60), c.dailyFatTargetG)
        assertEquals(com.vipin.fitness.core.data.ProfileChanges(dailyCarbsTargetG = Field.Set(250), dailyFatTargetG = Field.Set(60)), c)
        assertEquals("k1", key)
        val s = vm.state.value
        assertTrue(s.saved); assertNull(s.edit); assertFalse(s.saving)
        assertEquals(MacroTargets(90, 250, 60, 2000), s.targets)
    }

    @Test fun emptyOrZeroClears() {
        val vm = editing()
        vm.onField(MacroField.PROTEIN, "0")
        vm.save()
        assertEquals(Field.Clear, profiles.updates.single().first.dailyProteinTargetG)
        assertNull(vm.state.value.targets!!.proteinG)
        vm.edit(); vm.onField(MacroField.FAT, "40"); vm.save()
        vm.edit(); vm.onField(MacroField.FAT, ""); vm.save()
        assertEquals(Field.Clear, profiles.updates.last().first.dailyFatTargetG)
    }

    @Test fun invalidInputBlocksSaveWithPerFieldErrors() {
        val vm = editing()
        vm.onField(MacroField.PROTEIN, "12.5")
        vm.onField(MacroField.CARBS, "1001")
        assertEquals(mapOf(MacroField.PROTEIN to GramsError.NOT_WHOLE, MacroField.CARBS to GramsError.TOO_LARGE), vm.state.value.editErrors)
        assertFalse(vm.state.value.canSave)
        vm.save()
        assertTrue(profiles.updates.isEmpty())
    }

    @Test fun offlineRetryReusesTheKeyAndAChangedValueGetsANewOne() {
        val vm = editing()
        vm.onField(MacroField.FAT, "55")
        profiles.failUpdates = ApiResult.Failure(ApiError.Network)
        vm.save()
        assertEquals(MacroError.OFFLINE, vm.state.value.saveError)
        assertEquals(MacroEdit("90", "", "55"), vm.state.value.edit) // kept for retry
        vm.save()
        vm.onField(MacroField.FAT, "56")
        vm.save()
        assertEquals(listOf("k1", "k1", "k2"), profiles.updates.map { it.second })
    }

    @Test fun rateLimitedKeepsTheKeyAndTheRetryAfter() {
        val vm = editing()
        vm.onField(MacroField.FAT, "55")
        profiles.failUpdates = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "x", null, retryAfterMs = 12_000))
        vm.save()
        assertEquals(MacroError.RATE_LIMITED, vm.state.value.saveError)
        assertEquals(12_000L, vm.state.value.retryAfterMs)
        profiles.failUpdates = null
        vm.save()
        assertEquals(listOf("k1", "k1"), profiles.updates.map { it.second })
        assertNull(vm.state.value.retryAfterMs)
        assertTrue(vm.state.value.saved)
    }

    @Test fun a400IsInvalidAndTheNextSaveGetsANewKey() {
        val vm = editing()
        vm.onField(MacroField.FAT, "55")
        profiles.failUpdates = ApiResult.Failure(ApiError.Http(400, "VALIDATION_ERROR", "x", null))
        vm.save()
        assertEquals(MacroError.INVALID, vm.state.value.saveError)
        profiles.failUpdates = null
        vm.save()
        assertEquals(listOf("k1", "k2"), profiles.updates.map { it.second })
    }

    @Test fun cancelDiscardsTheEditAndTheKey() {
        val vm = editing()
        vm.onField(MacroField.FAT, "55")
        profiles.failUpdates = ApiResult.Failure(ApiError.Network)
        vm.save()
        vm.cancelEdit()
        assertNull(vm.state.value.edit); assertNull(vm.state.value.saveError)
        profiles.failUpdates = null
        vm.edit(); vm.onField(MacroField.FAT, "55"); vm.save()
        assertEquals(listOf("k1", "k2"), profiles.updates.map { it.second })
    }

    @Test fun editAndSuggestAreExclusive() {
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
        val vm = vm()
        vm.suggest()
        vm.edit()
        assertNull(vm.state.value.suggestion) // editing closes the suggestion
        vm.suggest()
        assertEquals(1, suggestions.calls) // no suggestion while editing
    }

    @Test fun typingIsIgnoredWhileSavingAndALateSaveForAnotherUserIsDropped() = kotlinx.coroutines.test.runTest {
        val gate = CompletableDeferred<Unit>()
        profiles.gate = gate
        val vm = editing()
        vm.onField(MacroField.FAT, "55")
        vm.save()
        assertTrue(vm.state.value.saving)
        vm.onField(MacroField.FAT, "99")
        assertEquals("55", vm.state.value.edit!!.fat)
        zone.clearUserState() // signed out / another user signed in
        gate.complete(Unit)
        assertFalse(vm.state.value.saved)
        assertEquals(MacroTargets(90, null, null, 2000), vm.state.value.targets)
    }

    @Test fun parseGramsRules() {
        assertEquals(GramsInput.Ok(null), parseGrams(""))
        assertEquals(GramsInput.Ok(null), parseGrams("  "))
        assertEquals(GramsInput.Ok(null), parseGrams("0"))
        assertEquals(GramsInput.Ok(null), parseGrams("000"))
        assertEquals(GramsInput.Ok(7), parseGrams("007"))
        assertEquals(GramsInput.Ok(1000), parseGrams("1000"))
        assertEquals(GramsInput.Invalid(GramsError.TOO_LARGE), parseGrams("1001"))
        assertEquals(GramsInput.Invalid(GramsError.TOO_LARGE), parseGrams("99999999999999"))
        listOf("1.5", "-3", "1e3", "abc", "1,000", "+5").forEach {
            assertEquals(it, GramsInput.Invalid(GramsError.NOT_WHOLE), parseGrams(it))
        }
    }

    @Test fun changesIsNullWhenNothingDiffers() {
        val t = MacroTargets(90, null, 50, 2000)
        assertNull(MacroEdit("90", "", "50").changes(t))
        assertNull(MacroEdit("090", "0", "50").changes(t)) // same values written differently
        assertNull(MacroEdit("x", "", "50").changes(t))
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

    @Test fun editAndSaveMessages() {
        assertEquals("Enter a whole number of grams", MacroFormat.gramsError(res, GramsError.NOT_WHOLE))
        assertEquals("At most 1,000 g", MacroFormat.gramsError(res, GramsError.TOO_LARGE))
        assertEquals("Too many changes. Try again in 30 s.", MacroFormat.saveError(res, MacroError.RATE_LIMITED, 30_000))
        assertEquals("Too many changes. Try again in 2 s.", MacroFormat.saveError(res, MacroError.RATE_LIMITED, 1_001))
        assertEquals("Too many changes. Try again shortly.", MacroFormat.saveError(res, MacroError.RATE_LIMITED, null))
        assertEquals("You're offline. Check your connection.", MacroFormat.saveError(res, MacroError.OFFLINE, null))
        assertEquals("The server didn't accept these values. Check them and try again.", MacroFormat.saveError(res, MacroError.INVALID, null))
        assertEquals("Couldn't save your targets. Nothing was changed.", MacroFormat.saveError(res, MacroError.FAILED, null))
        assertEquals("Too many changes. Try again in 5 s.", MacroFormat.applyError(res, MacroError.RATE_LIMITED, 5_000))
    }

    @Test fun goalLabels() {
        assertEquals("lose weight", MacroFormat.goal(res, "lose_weight", false))
        assertEquals("no active goal, so maintain", MacroFormat.goal(res, "maintain", true))
        assertEquals("maintain", MacroFormat.goal(res, "something_new", false))
    }
}
