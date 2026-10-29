package com.vipin.fitness.ui

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.vipin.fitness.MainActivity
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.designsystem.ConsentTags
import com.vipin.fitness.core.designsystem.SettingsLinkTags
import com.vipin.fitness.core.testing.FakeConsentRepository
import com.vipin.fitness.core.testing.FakeProfileFlagsCache
import com.vipin.fitness.core.testing.FakeProfileRepository
import com.vipin.fitness.core.testing.FakeSessionRepository
import com.vipin.fitness.feature.onboarding.LegalLinks
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * QA (#66): onboarding consent gate and help/legal links through the real MainActivity,
 * navigation and ConsentViewModel. Consent comes from FakeConsentModule's FakeConsentRepository
 * (no network); each test sets `consent.consented` before launch.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class)
class ConsentUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var session: FakeSessionRepository
    @Inject lateinit var profiles: FakeProfileRepository
    @Inject lateinit var flags: FakeProfileFlagsCache
    @Inject lateinit var consent: FakeConsentRepository
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun setUp() = hilt.inject()
    @After fun tearDown() { scenario?.close() }

    private fun launch() { scenario = ActivityScenario.launch(MainActivity::class.java); compose.waitForIdle() }
    private fun tag(t: String) = compose.onNodeWithTag(t)
    private fun waitTag(t: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitText(t: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun click(t: String) = tag(t).performScrollTo().performClick().also { compose.waitForIdle() }
    private fun type(label: String, v: String) = compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextInput(v)
    private fun signInAsNewUser() {
        type("Email", "asha@example.in"); type("Password", "correct horse")
        compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick(); compose.waitForIdle()
    }
    private fun signedInCompletedUser() {
        session.signedIn = true; flags.completed = true
        profiles.profile = Profile(displayName = "Asha", onboardingCompleted = true)
    }
    private fun lastViewIntent(): Intent? =
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).nextStartedActivity

    @Test fun newUser_seesConsentBeforeOnboarding_continueNeedsTheBox_thenStep1() {
        consent.consented = false
        launch(); signInAsNewUser()
        waitTag(ConsentTags.SCREEN)
        assertTrue("onboarding must not show before consent", !shows("Step 1 of 4"))
        tag(ConsentTags.CONTINUE).performScrollTo().assertIsNotEnabled()
        click(ConsentTags.AGREE)
        tag(ConsentTags.CONTINUE).assertIsEnabled()
        click(ConsentTags.CONTINUE)
        waitText("Step 1 of 4")
        assertEquals(1, consent.accepts)
        assertTrue(consent.consented)
    }

    @Test fun untickingTheBoxDisablesContinueAgain() {
        consent.consented = false
        launch(); signInAsNewUser(); waitTag(ConsentTags.SCREEN)
        click(ConsentTags.AGREE); click(ConsentTags.AGREE)
        tag(ConsentTags.CONTINUE).assertIsNotEnabled()
        assertEquals(0, consent.accepts)
    }

    @Test fun unidentifiedUser_staysOnConsentWithError_andCanRetry() {
        consent.consented = false; consent.canIdentify = false
        launch(); signInAsNewUser(); waitTag(ConsentTags.SCREEN)
        click(ConsentTags.AGREE); click(ConsentTags.CONTINUE)
        waitTag(ConsentTags.ERROR)
        assertTrue(!shows("Step 1 of 4"))
        tag(ConsentTags.CONTINUE).assertIsEnabled()
        consent.canIdentify = true
        click(ConsentTags.CONTINUE)
        waitText("Step 1 of 4")
        assertEquals(2, consent.accepts)
    }

    @Test fun alreadyConsentedNewUser_goesStraightToStep1() {
        consent.consented = true
        launch(); signInAsNewUser()
        waitText("Step 1 of 4")
        assertTrue(compose.onAllNodes(hasTestTag(ConsentTags.SCREEN)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun completedUserWithoutConsent_isGatedBeforeMain_thenReachesMain() {
        signedInCompletedUser(); consent.consented = false
        launch()
        waitTag(ConsentTags.SCREEN)
        assertTrue("Main must not show before consent", !shows("Today's plan"))
        click(ConsentTags.AGREE); click(ConsentTags.CONTINUE)
        waitText("Today's plan")
    }

    @Test fun completedConsentedUser_opensMainDirectly() {
        signedInCompletedUser(); consent.consented = true
        launch()
        waitText("Today's plan")
        assertTrue(compose.onAllNodes(hasTestTag(ConsentTags.SCREEN)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun consentPrivacyAndTermsLinksOpenTheConfiguredUrls() {
        consent.consented = false
        launch(); signInAsNewUser(); waitTag(ConsentTags.SCREEN)
        click(ConsentTags.PRIVACY)
        lastViewIntent().let { assertEquals(Intent.ACTION_VIEW, it?.action); assertEquals(LegalLinks.privacy, it?.data.toString()) }
        click(ConsentTags.TERMS)
        assertEquals(LegalLinks.terms, lastViewIntent()?.data.toString())
        assertEquals(0, consent.accepts)
    }

    @Test fun profileHelpAndLegalLinksOpenHelpFeedbackPrivacyTerms() {
        signedInCompletedUser(); consent.consented = true
        launch(); waitTag("open_profile"); tag("open_profile").performClick(); compose.waitForIdle(); waitTag(SettingsLinkTags.HELP)
        val opened = listOf(SettingsLinkTags.HELP, SettingsLinkTags.FEEDBACK, SettingsLinkTags.PRIVACY, SettingsLinkTags.TERMS).map {
            click(it); lastViewIntent()?.data.toString()
        }
        assertEquals(listOf(LegalLinks.help, LegalLinks.feedbackMailto, LegalLinks.privacy, LegalLinks.terms), opened)
    }
}
