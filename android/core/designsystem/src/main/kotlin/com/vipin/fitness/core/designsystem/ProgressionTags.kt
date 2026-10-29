package com.vipin.fitness.core.designsystem

/** Test tags for the plan screen's progression Suggestions section. Per-row tags take the row key (`plannedWorkoutId#order`). */
object ProgressionTags {
    const val SECTION = "progression_section"
    const val LOADING = "progression_loading"
    const val EMPTY = "progression_empty"
    const val ERROR = "progression_error"
    const val RETRY = "progression_retry"
    const val APPLY = "progression_apply"
    const val APPLY_STATUS = "progression_apply_status"
    fun row(key: String) = "progression_row_$key"
    fun value(key: String) = "progression_value_$key"
    fun reason(key: String) = "progression_reason_$key"
    fun why(key: String) = "progression_why_$key"
    fun accept(key: String) = "progression_accept_$key"
    fun skip(key: String) = "progression_skip_$key"
}
