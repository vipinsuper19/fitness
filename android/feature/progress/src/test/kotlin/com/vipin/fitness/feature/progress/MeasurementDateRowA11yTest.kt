package com.vipin.fitness.feature.progress

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MeasurementDateRowA11yTest {
    @get:Rule val compose = createComposeRule()

    @Test fun arrowsAreNamedForScreenReadersAndAtLeast48dp() {
        var prev = 0
        compose.setContent { MeasurementDateRow("Today", canPrevious = true, canNext = false, onPrevious = { prev++ }, onNext = {}) }
        val p = compose.onNodeWithTag(MeasurementDateTags.PREVIOUS)
        p.assertContentDescriptionEquals("Previous day").assertIsEnabled()
        compose.onNodeWithTag(MeasurementDateTags.NEXT).assertContentDescriptionEquals("Next day").assertIsNotEnabled()
        // The glyph itself isn't read out ("single left-pointing angle quotation mark").
        assertTrue(compose.onAllNodes(hasText("‹"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        p.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role))
        listOf(MeasurementDateTags.PREVIOUS, MeasurementDateTags.NEXT).forEach { t ->
            val b = compose.onNodeWithTag(t).fetchSemanticsNode().boundsInRoot
            with(compose.density) { assertTrue("$t ${b.height.toDp()}", b.height.toDp().value >= 48f) }
        }
        p.performClick()
        assertEquals(1, prev)
    }
}
