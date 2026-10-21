package com.vipin.fitness.core.designsystem

// Test tags agreed with QA (#55, ProposedAccountContract.kt). Tests reference the values: keep them.

object AccountEntryTags {
    /** Profile: opens the Delete account screen. */
    const val OPEN_DELETE_ACCOUNT = "profile_delete_account"
    /** Profile: starts the data export (next PR). */
    const val OPEN_EXPORT = "profile_export_data"
}

object DeleteAccountTags {
    const val SCREEN = "delete_account_screen"
    const val PASSWORD = "delete_account_password"
    const val CONFIRM = "delete_account_confirm"
    const val CANCEL = "delete_account_cancel"
    const val PROGRESS = "delete_account_progress"
    const val ERROR = "delete_account_error"
}

object ExportTags {
    const val SCREEN = "export_screen"
    const val START = "export_start"
    const val PROGRESS = "export_progress"
    const val SUCCESS = "export_success"
    const val ERROR = "export_error"
    const val RETRY = "export_retry"
}
