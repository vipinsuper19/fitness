package com.vipin.fitness.feature.onboarding

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.designsystem.ConsentTags
import com.vipin.fitness.core.designsystem.SettingsLinkTags
import com.vipin.fitness.core.testing.FakeConsentRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private class RecordingUris : UriHandler {
    val opened = mutableListOf<String>()
    override fun openUri(uri: String) { opened += uri }
}

@RunWith(AndroidJUnit4::class)
class ConsentAndLinksTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()

    @Test fun continueNeedsTheBoxTickedThenRecordsConsentAndShowsOnboarding() {
        val repo = FakeConsentRepository(consented = false)
        val vm = ConsentViewModel(repo)
        compose.setContent { ConsentGate(vm) { Text("steps") } }
        compose.onNodeWithTag(ConsentTags.CONTINUE).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(ConsentTags.AGREE).performScrollTo().performClick()
        compose.onNodeWithTag(ConsentTags.CONTINUE).assertIsEnabled().performClick()
        compose.onNodeWithText("steps").assertExists()
        assertEquals(1, repo.accepts)
    }

    @Test fun alreadyConsentedSkipsStraightToOnboarding() {
        val vm = ConsentViewModel(FakeConsentRepository(consented = true))
        compose.setContent { ConsentGate(vm) { Text("steps") } }
        compose.onNodeWithText("steps").assertExists()
        compose.onNodeWithTag(ConsentTags.SCREEN).assertDoesNotExist()
    }

    @Test fun consentLinksOpenPrivacyAndTerms() {
        val uris = RecordingUris()
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uris) { ConsentContent(saving = false, onContinue = {}) } }
        compose.onNodeWithTag(ConsentTags.PRIVACY).performScrollTo().performClick()
        compose.onNodeWithTag(ConsentTags.TERMS).performScrollTo().performClick()
        assertEquals(listOf(BuildConfig.PRIVACY_URL, BuildConfig.TERMS_URL), uris.opened)
    }

    @Test fun settingsLinksOpenHelpFeedbackEmailAndLegalPages() {
        val uris = RecordingUris()
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides uris) { SettingsLinks() } }
        listOf(SettingsLinkTags.HELP, SettingsLinkTags.FEEDBACK, SettingsLinkTags.PRIVACY, SettingsLinkTags.TERMS)
            .forEach { compose.onNodeWithTag(it).performClick() }
        assertEquals(BuildConfig.HELP_URL, uris.opened[0])
        assertTrue(uris.opened[1].startsWith("mailto:${BuildConfig.FEEDBACK_EMAIL}"))
        assertEquals(listOf(BuildConfig.PRIVACY_URL, BuildConfig.TERMS_URL), uris.opened.drop(2))
    }

    @Test fun everyStepExplainsWhyWeAsk() {
        assertEquals(OnboardingStep.entries.size, OnboardingStep.entries.map { it.why() }.toSet().size)
    }
}
