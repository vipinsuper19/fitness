package com.vipin.fitness.core.designsystem

/** Test tags for the Notification settings screen (feature:onboarding, from Profile). */
object NotificationSettingsTags {
    const val OPEN = "notif_open"
    const val SCREEN = "notif_screen"
    const val LOADING = "notif_loading"
    const val LOAD_ERROR = "notif_load_error"
    const val RETRY = "notif_retry"
    const val MASTER = "notif_master"
    const val TYPES_SUMMARY = "notif_types_summary"
    const val QUIET_ENABLED = "notif_quiet_enabled"
    const val QUIET_START = "notif_quiet_start"
    const val QUIET_END = "notif_quiet_end"
    const val QUIET_WINDOW = "notif_quiet_window"
    const val TIME_PICKER = "notif_time_picker"
    const val TIME_PICKER_OK = "notif_time_picker_ok"
    const val TIMEZONE = "notif_timezone"
    const val SAVE = "notif_save"
    const val DISCARD = "notif_discard"
    const val SAVED = "notif_saved"
    const val SAVE_ERROR = "notif_save_error"
    const val SAVE_RETRY = "notif_save_retry"

    /** One switch per known type, by wire name (e.g. `notif_type_water_reminder`). */
    fun type(wire: String) = "notif_type_$wire"
}
