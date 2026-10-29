package com.vipin.fitness.feature.progress

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins the values QA mirrors; a change here must be mirrored in app/src/test/.../ui/GoalsTags.kt. */
class GoalsTagsTest {
    @Test fun valuesAreUnchanged() {
        assertEquals(
            listOf(
                "progress_tab_goals", "goal_add", "goal_empty", "goal_write_error", "goal_target", "goal_start", "goal_date", "goal_save", "slot_goals",
                "goal_g1", "goal_bar_g1", "goal_milestone_g1", "goal_achieved_g1", "goal_abandon_g1", "goal_reactivate_g1", "goal_delete_g1",
                "goal_type_weight", "home_goal_bar_g1",
            ),
            with(GoalsTags) {
                listOf(
                    TAB, ADD, EMPTY, WRITE_ERROR, TARGET, START, DATE, SAVE, HOME_SLOT,
                    card("g1"), bar("g1"), milestone("g1"), achieved("g1"), abandon("g1"), reactivate("g1"), delete("g1"),
                    type("weight"), homeBar("g1"),
                )
            },
        )
    }
}
