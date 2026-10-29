package com.vipin.fitness.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import org.robolectric.Shadows.shadowOf

/** Shared helpers (same waiting pattern as WorkoutHistoryUiTest). */
class UiHelpers(private val compose: ComposeTestRule) {
    fun tag(t: String) = compose.onNodeWithTag(t)
    fun present(t: String) = compose.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty()
    fun shows(t: String) = compose.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
    fun until(c: () -> Boolean) = compose.waitUntil(5_000) { Thread.sleep(10); shadowOf(android.os.Looper.getMainLooper()).idle(); c() }
    fun waitTag(t: String) = until { present(t) }
    fun waitText(t: String) = until { shows(t) }
}
