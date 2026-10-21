package com.vipin.fitness.ui

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.AuthFailure
import com.vipin.fitness.core.data.AuthOutcome
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.Sex
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * T16c part 1: end-to-end UI flows through the real MainActivity, navigation and ViewModels,
 * with repositories replaced by in-memory fakes (see FakeDataModules). Robolectric, no emulator.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class AuthOnboardingUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() = hilt.inject()
    @After fun tearDown() { scenario?.close() }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }
    private fun text(t: String) = compose.onNodeWithText(t)
    private fun field(label: String) = compose.onNode(hasSetTextAction() and hasText(label))
    /** Clicks the clickable node with exactly this text (titles like "Sign in" share button labels). */
    private fun click(t: String) = compose.onNode(hasText(t) and hasClickAction()).performScrollTo().performClick().also { compose.waitForIdle() }
    private fun type(label: String, v: String) = field(label).performScrollTo().performTextInput(v)
    private fun waitFor(t: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun onStep(n: Int) = waitFor("Step $n of 4")

    // ---- sign-in ----

    @Test fun signedOutUserStartsOnSignIn_andValidatesInput() {
        launch()
        text("New here? Create an account").assertIsDisplayed()
        click("Sign in")
        text("Enter a valid email address").assertIsDisplayed()
        text("Enter your password").assertIsDisplayed()
        assertTrue(session.signInCalls.isEmpty())
    }

    @Test fun wrongPasswordShowsError_andStaysOnSignIn() {
        session.signInResult = AuthOutcome.Failure(AuthFailure.InvalidCredentials)
        launch()
        type("Email", "asha@example.in"); type("Password", "wrong-pass")
        click("Sign in")
        waitFor("Wrong email or password")
        assertEquals(listOf("asha@example.in" to "wrong-pass"), session.signInCalls)
    }

    @Test fun signInSucceeds_andOpensOnboarding() {
        launch()
        type("Email", "asha@example.in"); type("Password", "correct horse")
        click("Sign in")
        onStep(1)
        text("About you").assertExists()
    }

    @Test fun signInOfCompletedUserGoesStraightToMain() {
        profiles.profile = Profile(onboardingCompleted = true)
        launch()
        type("Email", "asha@example.in"); type("Password", "correct horse")
        click("Sign in")
        waitFor("Today's plan")
    }

    // ---- registration ----

    @Test fun registrationValidatesShortPassword() {
        launch()
        click("New here? Create an account")
        type("Email", "new@example.in"); type("Password", "short")
        click("Create account")
        compose.waitForIdle()
        text("Password must be at least 8 characters").assertExists()
        assertTrue(session.registerKeys.isEmpty())
    }

    @Test fun registrationSucceeds_andOpensOnboarding() {
        launch()
        click("New here? Create an account")
        type("Name (optional)", "Asha"); type("Email", "new@example.in"); type("Password", "correct horse")
        click("Create account")
        onStep(1)
        assertEquals(1, session.registerKeys.size)
    }

    @Test fun emailTaken_offersSignInInstead_withEmailPrefilled_andBackDoesNotReturnToRegister() {
        session.registerResult = AuthOutcome.Failure(AuthFailure.EmailTaken)
        launch()
        click("New here? Create an account")
        type("Email", "taken@example.in"); type("Password", "correct horse")
        click("Create account")
        waitFor("An account with this email already exists.")
        compose.onNodeWithTag("sign_in_instead").performScrollTo().performClick()
        compose.waitForIdle()
        text("New here? Create an account").assertIsDisplayed()
        field("Email").assert(hasText("taken@example.in"))
        // Signing in from here works with the prefilled email.
        type("Password", "correct horse")
        click("Sign in")
        onStep(1)
        assertEquals("taken@example.in", session.signInCalls.single().first)
    }

    // ---- onboarding ----

    private fun signedIn(p: Profile = Profile()) {
        session.signedIn = true
        profiles.profile = p
        launch()
    }

    @Test fun onboardingWalksAllFourSteps_savingEach_andFinishMarksCompleted() {
        signedIn()
        onStep(1); type("Name", "Asha"); click("Female"); click("Next")
        onStep(2); type("Height (cm)", "165"); type("Weight (kg)", "60"); click("Next")
        onStep(3); click("Moderate"); click("Beginner"); click("Next")
        onStep(4); click("Vegetarian"); type("Daily water target (ml)", "2500"); click("Finish")
        waitFor("Today's plan")
        val p = profiles.profile
        assertEquals("Asha", p.displayName); assertEquals(Sex.FEMALE, p.sex)
        assertEquals(165.0, p.heightCm); assertEquals(60.0, p.weightKg)
        assertEquals(2500, p.dailyWaterTargetMl)
        assertTrue(p.onboardingCompleted)
    }

    @Test fun onboardingResumesAtFirstUnansweredStep() {
        signedIn(Profile(displayName = "Asha", heightCm = 165.0))
        onStep(3)
        text("Activity").assertExists()
        click("Back")
        onStep(2)
        field("Height (cm)").assert(hasText("165"))
    }

    @Test fun skipEveryStep_finishesWithoutSavingAnswers() {
        signedIn()
        onStep(1); type("Name", "Not saved"); click("Skip")
        onStep(2); click("Skip")
        onStep(3); click("Skip")
        onStep(4); click("Skip")
        waitFor("Today's plan")
        assertEquals(null, profiles.profile.displayName)
        assertTrue(profiles.profile.onboardingCompleted)
        assertEquals(1, profiles.updates.size) // only the completion flag
    }

    @Test fun unitSwitch_kgToLb_andCmToFtIn_convertsAndSavesMetric() {
        signedIn(Profile(displayName = "Asha"))
        onStep(2)
        type("Height (cm)", "180"); type("Weight (kg)", "80")
        click("Imperial (lb, ft/in)")
        field("Height (ft)").assert(hasText("5"))
        field("(in)").assertExists()
        field("Weight (lb)").assert(hasText("176.4"))
        click("Next")
        onStep(3)
        assertEquals(UnitSystem.IMPERIAL, profiles.profile.unitSystem)
        assertEquals(80.0, profiles.profile.weightKg!!, 0.05)
        assertEquals(180.0, profiles.profile.heightCm!!, 0.5)
    }

    @Ignore("Known bug #27")
    @Test fun unitSwitch_lbToKg_andFtInToCm() {
        signedIn(Profile(displayName = "Asha", unitSystem = UnitSystem.IMPERIAL))
        onStep(2)
        type("Height (ft)", "5"); type("(in)", "10"); type("Weight (lb)", "154")
        click("Metric (kg, cm)")
        field("Height (cm)").assert(hasText("177.8"))
        field("Weight (kg)").assert(hasText("69.9"))
    }

    /** 5 ft 7.5 in = 171.45 cm exactly: must show 171.5 (HALF_UP), then 67.52 in -> 5 ft 8 in. */
    @Ignore("Known bug #27")
    @Test fun heightRoundTrip_ftInToCmToFtIn() {
        signedIn(Profile(displayName = "Asha", unitSystem = UnitSystem.IMPERIAL))
        onStep(2)
        type("Height (ft)", "5"); type("(in)", "7.5")
        click("Metric (kg, cm)")
        field("Height (cm)").assert(hasText("171.5"))
        click("Imperial (lb, ft/in)")
        field("Height (ft)").assert(hasText("5"))
        field("(in)").assert(hasText("8"))
    }

    /** 171.45 cm typed: 1-dp value is 171.5 -> 67.52 in -> 5 ft 8 in -> 172.7 cm. */
    @Ignore("Known bug #27")
    @Test fun heightRoundTrip_cmToFtInToCm() {
        signedIn(Profile(displayName = "Asha"))
        onStep(2)
        type("Height (cm)", "171.45")
        click("Imperial (lb, ft/in)")
        field("Height (ft)").assert(hasText("5"))
        field("(in)").assert(hasText("8"))
        click("Metric (kg, cm)")
        field("Height (cm)").assert(hasText("172.7"))
    }

    @Test fun invalidHeightBlocksNext_andShowsRange() {
        signedIn(Profile(displayName = "Asha"))
        onStep(2)
        type("Height (cm)", "20")
        click("Next")
        text("Height must be between 50 and 272 cm (1 ft 8 in to 8 ft 11 in)").assertExists()
        onStep(2)
        assertTrue(profiles.updates.isEmpty())
    }

    // ---- profile edit + sign-out ----

    private fun openProfile(p: Profile) {
        session.signedIn = true; flags.completed = true; profiles.profile = p
        launch()
        waitFor("Today's plan")
        compose.onNodeWithTag("open_profile").performClick()
        waitFor("Profile")
    }

    @Test fun profileEdit_showsSavedValues_savesChange() {
        openProfile(Profile(displayName = "Asha", weightKg = 60.0, onboardingCompleted = true))
        field("Name").assert(hasText("Asha"))
        field("Weight (kg)").assert(hasText("60"))
        field("Weight (kg)").performTextClearance(); type("Weight (kg)", "62.5")
        click("Save")
        waitFor("Saved")
        assertEquals(62.5, profiles.profile.weightKg)
        assertEquals("Asha", profiles.profile.displayName)
    }

    @Test fun profileEdit_clearingAFieldClearsItOnServer() {
        openProfile(Profile(displayName = "Asha", birthYear = 1995, onboardingCompleted = true))
        field("Birth year").performTextClearance()
        click("Save")
        waitFor("Saved")
        assertEquals(null, profiles.profile.birthYear)
        assertEquals(Field.Clear, profiles.updates.last().first.birthYear)
    }

    @Test fun profileEdit_unitSwitchShowsImperial() {
        openProfile(Profile(weightKg = 80.0, heightCm = 180.0, onboardingCompleted = true))
        click("Imperial (lb, ft/in)")
        text("Imperial (lb, ft/in)").assertIsSelected()
        field("Weight (lb)").assert(hasText("176.4"))
    }

    @Test fun signOut_returnsToSignIn_andBackCannotReopenProfile() {
        openProfile(Profile(onboardingCompleted = true))
        click("Sign out")
        waitFor("New here? Create an account")
        assertEquals(1, session.signOuts)
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodes(hasText("Sign out")).fetchSemanticsNodes().isEmpty())
    }
}
