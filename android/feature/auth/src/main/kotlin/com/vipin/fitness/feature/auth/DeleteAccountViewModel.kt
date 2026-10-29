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

enum class DeleteAccountError {
    WRONG_PASSWORD, OFFLINE, SERVER, RATE_LIMITED, FAILED,
    /**
     * 401 on a retry of an attempt whose outcome we never learned: the lost delete may have gone
     * through (tokens revoked), or the session just expired. We can't tell, so offer sign-in.
     */
    MAYBE_DELETED,
}

data class DeleteAccountState(
    val password: String = "",
    val submitting: Boolean = false,
    val error: DeleteAccountError? = null,
    /** One-shot: the account is gone and we're signed out; go to sign-in. */
    val deleted: Boolean = false,
    /** "Sign in to check" was tapped and the local sign-out is under way (ignores double taps). */
    val signingOutToCheck: Boolean = false,
    /** One-shot: the user chose "Sign in to check" and we're signed out; go to sign-in. */
    val signedOutToCheck: Boolean = false,
) {
    val canConfirm: Boolean get() =
        password.isNotEmpty() && !submitting && !deleted && !signingOutToCheck && !signedOutToCheck && error != DeleteAccountError.MAYBE_DELETED
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

    /**
     * The last attempt's outcome was never learned (offline, 5xx, 408, in progress), so it may have
     * gone through. Separate from [pendingKey], which a 429 also keeps (that request was refused
     * unprocessed, so it alone can't explain a later 401).
     */
    private var outcomeUnknown = false

    // MAYBE_DELETED stays until sign-in: typing can't make the account's state any clearer.
    fun onPassword(v: String) = _state.update {
        it.copy(password = v, error = it.error.takeIf { e -> e == DeleteAccountError.MAYBE_DELETED })
    }

    fun delete() {
        val s = _state.value
        if (!s.canConfirm) return
        // A retry of an attempt whose outcome we never learned (offline / 5xx).
        val retryingUnknown = outcomeUnknown
        val key = pendingKey ?: newIdempotencyKey().also { pendingKey = it }
        _state.update { it.copy(submitting = true, error = null) } // before launch: ignores double taps
        viewModelScope.launch {
            val r = account.deleteAccount(s.password, key)
            // Prod (checked 2026-10-21): after a successful delete the tokens are revoked, so a
            // same-key retry gets 401 UNAUTHENTICATED instead of a replayed 204. Only on such a
            // retry does 401 mean "it already went through"; a first attempt's 401 is a plain
            // expired session, and wiping local data then could lose unsynced work.
            val maybeDeleted = retryingUnknown && r is ApiResult.Failure && (r.error as? ApiError.Http)?.status == 401
            when {
                maybeDeleted -> {
                    // Ambiguous: don't claim it's deleted (or wipe consent); let sign-in tell.
                    pendingKey = null
                    outcomeUnknown = false
                    _state.update { it.copy(submitting = false, error = DeleteAccountError.MAYBE_DELETED) }
                }
                r is ApiResult.Success -> {
                    pendingKey = null
                    outcomeUnknown = false
                    // The deleted account's consent record goes too (sign-out alone keeps it).
                    consent?.forgetActiveUser()
                    // Local wipe: tokens, cached flags, every UserScopedState (outbox, sessions...).
                    session.signOut()
                    _state.update { it.copy(submitting = false, deleted = true, password = "") }
                }
                r is ApiResult.Failure -> {
                    val error = r.error.toDeleteError()
                    // Keep the key while a retry could still replay this attempt (429: not processed yet).
                    if (error != DeleteAccountError.OFFLINE && error != DeleteAccountError.SERVER && error != DeleteAccountError.RATE_LIMITED) pendingKey = null
                    // 429 leaves it as it was: an earlier lost attempt is still unresolved, but the 429 itself isn't.
                    if (error == DeleteAccountError.OFFLINE || error == DeleteAccountError.SERVER) outcomeUnknown = true
                    else if (error != DeleteAccountError.RATE_LIMITED) outcomeUnknown = false
                    _state.update { it.copy(submitting = false, error = error) }
                }
            }
        }
    }

    /**
     * "Sign in to check": end the session like an expiry (tokens only; no server logout, outbox and
     * consent kept). If the 401 was only an expired session, the same user signing in resumes their
     * unsynced work; if the account is gone, sign-in says so.
     */
    fun signInToCheck() {
        val s = _state.value
        if (s.error != DeleteAccountError.MAYBE_DELETED || s.signingOutToCheck || s.signedOutToCheck) return
        _state.update { it.copy(signingOutToCheck = true) } // before launch: ignores double taps
        viewModelScope.launch {
            session.endSessionLocally()
            _state.update { it.copy(signingOutToCheck = false, signedOutToCheck = true, password = "") }
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
