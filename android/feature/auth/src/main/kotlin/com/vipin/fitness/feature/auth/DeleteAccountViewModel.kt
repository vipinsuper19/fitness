package com.vipin.fitness.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.AccountRepository
import com.vipin.fitness.core.data.ConsentRepository
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeleteAccountError { WRONG_PASSWORD, OFFLINE, SERVER, RATE_LIMITED, FAILED }

data class DeleteAccountState(
    val password: String = "",
    val submitting: Boolean = false,
    val error: DeleteAccountError? = null,
    /** One-shot: the account is gone and we're signed out; go to sign-in. */
    val deleted: Boolean = false,
) {
    val canConfirm: Boolean get() = password.isNotEmpty() && !submitting && !deleted
}

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val account: AccountRepository,
    private val session: SessionRepository,
    private val consent: ConsentRepository? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    /** Kept while the outcome is unknown (offline, 5xx, 408, in progress) so a retry replays, not repeats. */
    private var pendingKey: String? = null

    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun delete() {
        val s = _state.value
        if (!s.canConfirm) return
        // A retry of an attempt whose outcome we never learned (offline / 5xx).
        val retryingUnknown = pendingKey != null
        val key = pendingKey ?: newIdempotencyKey().also { pendingKey = it }
        _state.update { it.copy(submitting = true, error = null) } // before launch: ignores double taps
        viewModelScope.launch {
            val r = account.deleteAccount(s.password, key)
            // Prod (checked 2026-10-21): after a successful delete the tokens are revoked, so a
            // same-key retry gets 401 UNAUTHENTICATED instead of a replayed 204. Only on such a
            // retry does 401 mean "it already went through"; a first attempt's 401 is a plain
            // expired session, and wiping local data then could lose unsynced work.
            val alreadyDeleted = retryingUnknown && r is ApiResult.Failure && (r.error as? ApiError.Http)?.status == 401
            when {
                r is ApiResult.Success || alreadyDeleted -> {
                    pendingKey = null
                    // The deleted account's consent record goes too (sign-out alone keeps it).
                    consent?.forgetActiveUser()
                    // Local wipe: tokens, cached flags, every UserScopedState (outbox, sessions...).
                    session.signOut()
                    _state.update { it.copy(submitting = false, deleted = true, password = "") }
                }
                r is ApiResult.Failure -> {
                    val error = r.error.toDeleteError()
                    if (error != DeleteAccountError.OFFLINE && error != DeleteAccountError.SERVER) pendingKey = null
                    _state.update { it.copy(submitting = false, error = error) }
                }
            }
        }
    }
}

internal fun ApiError.toDeleteError(): DeleteAccountError = when (this) {
    ApiError.Network -> DeleteAccountError.OFFLINE
    is ApiError.Unexpected -> DeleteAccountError.FAILED
    is ApiError.Http -> when {
        code == "INVALID_PASSWORD" -> DeleteAccountError.WRONG_PASSWORD
        status == 429 -> DeleteAccountError.RATE_LIMITED
        status >= 500 || status == 408 || code == "IDEMPOTENCY_IN_PROGRESS" -> DeleteAccountError.SERVER
        else -> DeleteAccountError.FAILED
    }
}
