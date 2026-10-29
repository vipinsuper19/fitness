package com.vipin.fitness.feature.onboarding

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.testing.FAKE_NETWORK_FAILURE
import com.vipin.fitness.core.testing.FakeNotificationPreferencesRepository
import com.vipin.fitness.core.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.vipin.fitness.core.designsystem.NotificationSettingsTags as Tags

@RunWith(AndroidJUnit4::class)
class NotificationSettingsScreenTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val compose = createComposeRule()
    private val repo = FakeNotificationPreferencesRepository()

    private fun show(): NotificationSettingsViewModel {
        val vm = NotificationSettingsViewModel(repo).also { it.newKey = { "k" } }
        compose.setContent { NotificationSettingsRoute(onBack = {}, viewModel = vm) }
        return vm
    }

    @Test fun showsEveryKnownTypeTimezoneAndWindow() {
        show()
        NotificationType.entries.forEach { compose.onNodeWithTag(Tags.type(it.wire)).performScrollTo() }
        compose.onNodeWithTag(Tags.type(NotificationType.WATER_REMINDER.wire)).assertIsOff()
        compose.onNodeWithTag(Tags.type(NotificationType.BADGE_EARNED.wire)).assertIsOn()
        compose.onNodeWithTag(Tags.TIMEZONE).performScrollTo().assertTextContains("Asia/Kolkata", substring = true)
        compose.onNodeWithTag(Tags.QUIET_WINDOW).performScrollTo().assertTextContains("next day", substring = true)
        compose.onNodeWithTag(Tags.SAVE).performScrollTo().assertIsNotEnabled()
    }

    @Test fun masterOffDisablesTypesButKeepsThem() {
        show()
        compose.onNodeWithTag(Tags.MASTER).performClick()
        compose.onNodeWithTag(Tags.type(NotificationType.BADGE_EARNED.wire)).performScrollTo().assertIsNotEnabled().assertIsOn()
        compose.onNodeWithTag(Tags.SAVE).performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(false, repo.updates.single().first.enabled)
        compose.onNodeWithTag(Tags.SAVED).performScrollTo()
    }

    @Test fun offlineShowsRetry() {
        repo.getResult = { FAKE_NETWORK_FAILURE }
        show()
        compose.onNodeWithTag(Tags.LOAD_ERROR)
        repo.getResult = null
        compose.onNodeWithTag(Tags.RETRY).performClick()
        compose.onNodeWithTag(Tags.MASTER).assertIsOn()
    }
}
