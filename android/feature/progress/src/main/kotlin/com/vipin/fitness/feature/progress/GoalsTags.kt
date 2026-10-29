package com.vipin.fitness.feature.progress

/**
 * Goals test tags. Values are the ones the screens have always used; QA mirrors them in
 * app/src/test/.../ui/GoalsTags.kt, so don't change them.
 */
object GoalsTags {
    /** Goals tab on the Progress screen. */
    const val TAB = "progress_tab_goals"
    const val ADD = "goal_add"
    const val EMPTY = "goal_empty"
    const val WRITE_ERROR = "goal_write_error"
    const val TARGET = "goal_target"
    const val START = "goal_start"
    const val DATE = "goal_date"
    const val SAVE = "goal_save"
    /** Home goals slot; HomeScreen (feature:home, no dependency on this module) uses the same literal. */
    const val HOME_SLOT = "slot_goals"
    fun card(id: String) = "goal_$id"
    fun bar(id: String) = "goal_bar_$id"
    fun milestone(id: String) = "goal_milestone_$id"
    fun achieved(id: String) = "goal_achieved_$id"
    fun abandon(id: String) = "goal_abandon_$id"
    fun reactivate(id: String) = "goal_reactivate_$id"
    fun delete(id: String) = "goal_delete_$id"
    fun type(wire: String) = "goal_type_$wire"
    /** Home goal progress bar; HomeScreen uses the same literal. */
    fun homeBar(id: String) = "home_goal_bar_$id"
}
