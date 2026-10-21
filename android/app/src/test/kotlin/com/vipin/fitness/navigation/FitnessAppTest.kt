package com.vipin.fitness.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.designsystem.FitnessTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FitnessAppTest {
    @get:Rule val compose = createComposeRule()
    private var profileOpened = false

    @Test
    fun tabsAreInExpectedOrderAndStartAtHome() {
        assertEquals(
            listOf("HOME", "WORKOUT", "NUTRITION", "PROGRESS"),
            TopLevelDestination.entries.map { it.name },
        )
        assertEquals(TopLevelDestination.HOME, TopLevelDestination.START)
    }

    @Test
    fun startsOnHomeAndSwitchesTabs() {
        compose.setContent { FitnessTheme { FitnessApp(onOpenProfile = { profileOpened = true }, tabContent = { dest, m -> Text("${dest.name} content", m) }) } }
        compose.onNodeWithText("HOME content").assertExists()

        compose.onNodeWithTag("tab_HOME").assertIsSelected()

        compose.onNodeWithTag("tab_WORKOUT").performClick()
        compose.onNodeWithText("WORKOUT content").assertExists()
        compose.onNodeWithTag("tab_WORKOUT").assertIsSelected()
        compose.onNodeWithTag("tab_HOME").assertIsNotSelected()

        compose.onNodeWithTag("tab_PROGRESS").performClick()
        compose.onNodeWithText("PROGRESS content").assertExists()

        compose.onNodeWithTag("open_profile").performClick()
        assertEquals(true, profileOpened)
    }
}
