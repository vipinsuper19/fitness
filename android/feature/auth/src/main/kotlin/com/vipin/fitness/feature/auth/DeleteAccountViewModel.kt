package com.vipin.fitness.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.AccountRepository
import com.vipin.fitness.core.data.DeleteAccountOutcome
import com.vipin.fitness.core.data.newIdempotencyKey
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DeleteAccountError { PASSWORD_REQUIRED, WRONG_PASSWORD, OFFLINE, RATE_LIMITED, FAILED }

data class DeleteAccountState(
    val password: String = "",
    val submitting: Boolean = false,
    val error: DeleteAccountError? = null,
    /** One-shot: the account is gone and local data cleared; go to sign-in. */
    val deleted: Boolean = false,
)

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(private val account: AccountRepository) : ViewModel() {
    private val _state = MutableStateFlow(DeleteAccountState())
    val state: StateFlow<DeleteAccountState> = _state.asStateFlow()

    /** Kept while the outcome is unknown (offline, 5xx), so a retry replays rather than repeats. */
    private var pendingKey: String? = null

    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun delete() {
        val s = _state.value
        if (s.submitting || s.deleted) return
        if (s.password.isEmpty()) {
            _state.update { it.copy(error = DeleteAccountError.PASSWORD_REQUIRED) }
            return
        }
        val key = pendingKey ?: newIdempotencyKey().also { pendingKey = it }
        _state.update { it.copy(submitting = true, error = null) } // before launch: ignores double taps
        viewModelScope.launch {
            val r = account.deleteAccount(s.password, key)
            if (r != DeleteAccountOutcome.Offline) pendingKey = null
            _state.update {
                when (r) {
                    DeleteAccountOutcome.Deleted -> it.copy(submitting = false, deleted = true, password = "")
                    DeleteAccountOutcome.WrongPassword -> it.copy(submitting = false, error = DeleteAccountError.WRONG_PASSWORD, password = "")
                    DeleteAccountOutcome.Offline -> it.copy(submitting = false, error = DeleteAccountError.OFFLINE)
                    DeleteAccountOutcome.RateLimited -> it.copy(submitting = false, error = DeleteAccountError.RATE_LIMITED)
                    DeleteAccountOutcome.Failed -> it.copy(submitting = false, error = DeleteAccountError.FAILED)
                }
            }
        }
    }
}
