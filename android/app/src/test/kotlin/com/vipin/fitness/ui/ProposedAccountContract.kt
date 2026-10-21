package com.vipin.fitness.ui

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.CompletableDeferred

/**
 * PROPOSED (test-first, Android P0). Move these tag objects into main code — e.g.
 * feature/onboarding (Profile/Settings) — and use them in Modifier.testTag(...). Tests reference
 * these names, so keep the values.
 */
object AccountEntryTags {
    /** Button/row on the existing Profile screen that opens the Delete account screen/dialog. */
    const val OPEN_DELETE_ACCOUNT = "profile_delete_account"
    /** Button/row on the existing Profile screen that starts the data export. */
    const val OPEN_EXPORT = "profile_export_data"
}

object DeleteAccountTags {
    /** Root of the delete-account screen or dialog. */
    const val SCREEN = "delete_account_screen"
    /** Password OutlinedTextField (PasswordVisualTransformation). */
    const val PASSWORD = "delete_account_password"
    /** Destructive confirm button; disabled while password is empty or a request is in flight. */
    const val CONFIRM = "delete_account_confirm"
    /** Cancel / back to Profile; nothing is sent. */
    const val CANCEL = "delete_account_cancel"
    /** Progress indicator while DELETE /me is in flight. */
    const val PROGRESS = "delete_account_progress"
    /** Error text (wrong password, offline, server error). */
    const val ERROR = "delete_account_error"
}

object ExportTags {
    /** Root of the export screen/section. */
    const val SCREEN = "export_screen"
    /** Starts GET /me/export. Disabled while loading. */
    const val START = "export_start"
    /** Progress indicator while the export is loading. */
    const val PROGRESS = "export_progress"
    /** Success message after the file was written / shared. */
    const val SUCCESS = "export_success"
    /** Error text. */
    const val ERROR = "export_error"
    /** Retry button shown with ERROR. */
    const val RETRY = "export_retry"
}

/**
 * PROPOSED repository the Delete/Export ViewModels should depend on (core:data), so tests can fake
 * it. When the real one lands, delete this and replace the module in FakeAccountModule with
 * @TestInstallIn(replaces = [<real module>]).
 */
interface AccountRepository {
    /** DELETE /api/v1/me {password} + Idempotency-Key. 204 → Success; then local sign-out & data wipe. */
    suspend fun deleteAccount(password: String, idempotencyKey: String): ApiResult<Unit>
    /** GET /api/v1/me/export → raw JSON body (Cache-Control: no-store; never cached on disk except the user's file). */
    suspend fun exportJson(): ApiResult<String>
}

class FakeAccountRepository : AccountRepository {
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var exportResult: ApiResult<String> = ApiResult.Success("""{"exportedAt":"2026-10-21T11:30:00Z","user":{"id":"u1","email":"asha@example.in"}}""")
    var gate: CompletableDeferred<Unit>? = null
    val deleteCalls = mutableListOf<Pair<String, String>>()
    var exports = 0

    override suspend fun deleteAccount(password: String, idempotencyKey: String): ApiResult<Unit> {
        deleteCalls += password to idempotencyKey
        gate?.await()
        return deleteResult
    }

    override suspend fun exportJson(): ApiResult<String> {
        exports++
        gate?.await()
        return exportResult
    }

    companion object {
        val WRONG_PASSWORD = ApiResult.Failure(ApiError.Http(403, "INVALID_PASSWORD", "Wrong password", "req-1"))
        val SERVER_ERROR = ApiResult.Failure(ApiError.Http(500, "INTERNAL", "Internal error", "req-2"))
        val RATE_LIMITED = ApiResult.Failure(ApiError.Http(429, "RATE_LIMITED", "Too many requests", "req-3"))
    }
}
