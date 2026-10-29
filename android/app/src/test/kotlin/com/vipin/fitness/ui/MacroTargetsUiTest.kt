package com.vipin.fitness.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.DietPreference
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.MacroInput
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeMacroSuggestionRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.core.testing.FakeUnitPreference
import com.vipin.fitness.core.testing.FakeWorkoutRepository
import com.vipin.fitness.core.testing.testMacroSuggestion
import com.vipin.fitness.core.testing.testMissingMacroInputs
import com.vipin.fitness.feature.nutrition.MacroTargetsTags as T
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/** Suggest macros (#120): Nutrition → Macro targets, through the real MainActivity with core:testing fakes. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class MacroTargetsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var workouts: FakeWorkoutRepository
    @Inject lateinit var units: FakeUnitPreference
    @Inject lateinit var suggestions: FakeMacroSuggestionRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() {
        hilt.inject()
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(
            displayName = "Asha", onboardingCompleted = true, weightKg = 70.0, dailyCalorieTargetKcal = 2000,
            dailyProteinTargetG = 90, dailyCarbsTargetG = null, dailyFatTargetG = 50,
        )
        workouts.refreshResult = { ApiResult.Success(ActivePlan.None) }
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
    }

    @After fun tearDown() { scenario?.close() }

    private fun tag(t: String) = compose.onNodeWithTag(t, useUnmergedTree = true)
    private fun present(t: String) = compose.onAllNodes(hasTestTag(t), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun showsIn(parent: String, t: String) =
        compose.onAllNodes(hasText(t, substring = true) and (hasTestTag(parent) or hasAnyAncestor(hasTestTag(parent))), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()
    private fun until(c: () -> Boolean) = compose.waitUntil(5_000) {
        Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c()
    }
    private fun waitTag(t: String) = until { present(t) }
    private fun scrollTo(t: String) { try { tag(t).performScrollTo() } catch (_: AssertionError) {} }
    private fun click(t: String) { waitTag(t); scrollTo(t); tag(t).performClick(); compose.waitForIdle() }

    private fun open() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        click("tab_NUTRITION")
        click(T.OPEN)
        waitTag(T.SCREEN)
    }
    private fun suggest() { open(); waitTag(T.SUGGEST); click(T.SUGGEST); waitTag(T.SUGGESTION) }

    @Test fun showsCurrentTargetsWithNotSet() {
        open(); waitTag(T.PROTEIN)
        assertTrue(showsIn(T.PROTEIN, "90 g"))
        assertTrue(showsIn(T.CARBS, "Not set"))
        assertTrue(showsIn(T.FAT, "50 g"))
        assertTrue(shows("2,000 kcal"))
        assertEquals(0, suggestions.calls) // nothing is suggested until asked
    }

    @Test fun suggestShowsReviewCardWithTotalNoteAndBasis() {
        suggest()
        assertTrue(showsIn(T.SUGGESTED_PROTEIN, "112 g"))
        assertTrue(showsIn(T.SUGGESTED_CARBS, "262 g"))
        assertTrue(showsIn(T.SUGGESTED_FAT, "56 g"))
        assertTrue(showsIn(T.SUGGESTED_KCAL, "2,000 kcal"))
        assertTrue(showsIn(T.SUGGESTION, "can be a little under your calorie target of 2,000 kcal"))
        assertTrue(showsIn(T.SUGGESTION, "Based on 70 kg and the goal: lose weight"))
        assertFalse(shows("Vegetarian protein ideas"))
        assertTrue(profiles.updates.isEmpty()) // reviewing never saves
    }

    @Test fun applySavesOnlyTheThreeGramTargets() {
        suggest(); click(T.APPLY)
        waitTag(T.APPLIED)
        val (c, _) = profiles.updates.single()
        assertEquals(Field.Set(112), c.dailyProteinTargetG)
        assertEquals(Field.Set(262), c.dailyCarbsTargetG)
        assertEquals(Field.Set(56), c.dailyFatTargetG)
        assertEquals(Field.Unchanged, c.dailyCalorieTargetKcal)
        assertEquals(Field.Unchanged, c.dailyWaterTargetMl)
        assertEquals(2000, profiles.profile.dailyCalorieTargetKcal)
        until { !present(T.SUGGESTION) }
        assertTrue(showsIn(T.PROTEIN, "112 g")); assertTrue(showsIn(T.CARBS, "262 g")); assertTrue(showsIn(T.FAT, "56 g"))
        assertTrue(shows("Macro targets saved."))
    }

    @Test fun applyOfflineThenRetryReusesTheIdempotencyKey() {
        profiles.failUpdates = FAKE_NETWORK_FAILURE
        suggest(); click(T.APPLY)
        waitTag(T.APPLY_ERROR)
        assertTrue(shows("You're offline"))
        assertTrue(showsIn(T.APPLY, "Retry"))
        assertTrue(showsIn(T.PROTEIN, "90 g")) // nothing changed
        profiles.failUpdates = null
        click(T.APPLY)
        waitTag(T.APPLIED)
        assertEquals(2, profiles.updates.size)
        assertEquals(profiles.updates[0].second, profiles.updates[1].second)
        assertFalse(present(T.APPLY_ERROR))
    }

    @Test fun aNewSuggestionAfterApplyGetsANewKey() {
        suggest(); click(T.APPLY); waitTag(T.APPLIED)
        click(T.SUGGEST); waitTag(T.SUGGESTION)
        assertFalse(present(T.APPLIED))
        click(T.APPLY); until { profiles.updates.size == 2 }
        assertTrue(profiles.updates[0].second != profiles.updates[1].second)
    }

    @Test fun zeroGramsIsSentAsClearAndShowsNotSet() {
        suggestions.result = { ApiResult.Success(testMacroSuggestion(proteinG = 150, carbsG = 300, fatG = 0)) }
        suggest()
        assertTrue(showsIn(T.SUGGESTED_FAT, "0 g"))
        click(T.APPLY); waitTag(T.APPLIED)
        assertEquals(Field.Clear, profiles.updates.single().first.dailyFatTargetG)
        assertTrue(showsIn(T.FAT, "Not set"))
    }

    @Test fun missingInputsListThemAndEditProfileOpensTheProfile() {
        suggestions.result = { ApiResult.Success(testMissingMacroInputs()) }
        open(); click(T.SUGGEST); waitTag(T.MISSING)
        assertTrue(shows("Add 2 details to your profile to get a suggestion:"))
        assertTrue(showsIn(T.missing(MacroInput.CALORIE_TARGET), "Daily calorie target"))
        assertTrue(showsIn(T.missing(MacroInput.WEIGHT), "Weight"))
        assertFalse(present(T.APPLY)); assertFalse(present(T.SUGGESTION))
        click(T.OPEN_PROFILE)
        until { shows("Sign out") }
        assertFalse(present(T.SCREEN))
    }

    @Test fun oneUnknownMissingInputUsesTheSingular() {
        suggestions.result = { ApiResult.Success(testMissingMacroInputs(MacroInput.UNKNOWN)) }
        open(); click(T.SUGGEST); waitTag(T.MISSING)
        assertTrue(shows("Add 1 detail to your profile to get a suggestion:"))
        assertTrue(showsIn(T.missing(MacroInput.UNKNOWN), "Other profile details"))
    }

    @Test fun suggestOfflineThenRetry() {
        suggestions.result = { FAKE_NETWORK_FAILURE }
        open(); click(T.SUGGEST); waitTag(T.SUGGEST_ERROR)
        assertTrue(shows("You're offline"))
        suggestions.result = { ApiResult.Success(testMacroSuggestion()) }
        click(T.SUGGEST_RETRY); waitTag(T.SUGGESTION)
        assertEquals(2, suggestions.calls)
        assertFalse(present(T.SUGGEST_ERROR))
    }

    @Test fun loadFailsThenRetry() {
        profiles.getResult = { FAKE_NETWORK_FAILURE }
        open(); waitTag(T.LOAD_ERROR)
        assertFalse(present(T.SUGGEST))
        profiles.getResult = null
        click(T.LOAD_RETRY); waitTag(T.PROTEIN)
        assertTrue(showsIn(T.PROTEIN, "90 g"))
    }

    @Test fun notNowHidesTheCardWithoutSavingAndAsksAgainNextTime() {
        suggest(); click(T.DISMISS)
        until { !present(T.SUGGESTION) }
        assertTrue(profiles.updates.isEmpty())
        click(T.SUGGEST); waitTag(T.SUGGESTION)
        assertEquals(2, suggestions.calls) // no cached suggestion
    }

    @Test fun basisWeightFollowsImperialUnits() {
        units.units.value = UnitSystem.IMPERIAL
        suggest()
        assertTrue(showsIn(T.SUGGESTION, "lb and the goal: lose weight"))
        assertFalse(showsIn(T.SUGGESTION, "70 kg"))
    }

    @Test fun vegetarianHintAndDefaultGoal() {
        val base = testMacroSuggestion()
        suggestions.result = { ApiResult.Success(base.copy(basis = base.basis!!.copy(dietPreference = DietPreference.VEGETARIAN, defaultGoal = true, goal = "maintain"))) }
        suggest()
        assertTrue(showsIn(T.SUGGESTION, "Vegetarian protein ideas: dal, paneer, curd, soy."))
        assertTrue(showsIn(T.SUGGESTION, "no active goal, so maintain"))
    }

    @Test fun backReturnsToTheDiary() {
        open(); click(T.BACK)
        waitTag(T.OPEN)
        assertFalse(present(T.SCREEN))
    }
}
