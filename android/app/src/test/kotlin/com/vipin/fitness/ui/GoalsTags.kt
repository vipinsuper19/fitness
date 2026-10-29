package com.vipin.fitness.ui

/** Goals (#76) test tags: the literal values GoalsScreen/ProgressScreen/HomeScreen use (main has no tags object). */
object GoalsTags {
    const val TAB = "progress_tab_goals"
    const val ADD = "goal_add"
    const val EMPTY = "goal_empty"
    const val WRITE_ERROR = "goal_write_error"
    const val TARGET = "goal_target"
    const val START = "goal_start"
    const val DATE = "goal_date"
    const val SAVE = "goal_save"
    const val HOME_SLOT = "slot_goals"
    fun card(id: String) = "goal_$id"
    fun bar(id: String) = "goal_bar_$id"
    fun milestone(id: String) = "goal_milestone_$id"
    fun achieved(id: String) = "goal_achieved_$id"
    fun abandon(id: String) = "goal_abandon_$id"
    fun reactivate(id: String) = "goal_reactivate_$id"
    fun delete(id: String) = "goal_delete_$id"
    fun type(wire: String) = "goal_type_$wire"
    fun homeBar(id: String) = "home_goal_bar_$id"
}
