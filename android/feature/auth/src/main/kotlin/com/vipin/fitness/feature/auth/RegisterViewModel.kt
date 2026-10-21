package com.vipin.fitness.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.AuthFailure
import com.vipin.fitness.core.data.AuthOutcome
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.designsystem.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    val emailError: UiText? = null,
    val passwordError: UiText? = null,
    val error: UiText? = null,
    /** 409 EMAIL_TAKEN: show "Sign in instead" with this email. */
    val emailTaken: Boolean = false,
    val registered: Boolean = false,
)

@HiltViewModel
class RegisterViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {
    /** Swappable in tests. */
    internal var newKey: () -> String = ::newIdempotencyKey

    private val _state = MutableStateFlow(RegisterState())
    val state: StateFlow<RegisterState> = _state.asStateFlow()

    /** One key per sign-up attempt; kept across retries of the same details, renewed when they change. */
    private var idempotencyKey: String? = null

    fun onEmail(v: String) = edit { it.copy(email = v, emailError = null) }
    fun onPassword(v: String) = edit { it.copy(password = v, passwordError = null) }
    fun onDisplayName(v: String) = edit { it.copy(displayName = v.take(60)) }

    private fun edit(f: (RegisterState) -> RegisterState) {
        idempotencyKey = null
        _state.update { f(it).copy(error = null, emailTaken = false) }
    }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val emailError = if (AuthValidation.isEmail(s.email)) null else UiText.Res(R.string.auth_error_email)
        val passwordError = if (s.password.length !in AuthValidation.MIN_PASSWORD..AuthValidation.MAX_PASSWORD) {
            UiText.Res(R.string.auth_error_password_short)
        } else {
            null
        }
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        val key = idempotencyKey ?: newKey().also { idempotencyKey = it }
        _state.update { it.copy(submitting = true, error = null, emailTaken = false) }
        viewModelScope.launch {
            when (val r = session.register(s.email, s.password, s.displayName, key)) {
                AuthOutcome.Success -> _state.update { it.copy(submitting = false, registered = true, password = "") }
                is AuthOutcome.Failure -> _state.update {
                    it.copy(
                        submitting = false,
                        error = r.reason.toUiText(),
                        emailTaken = r.reason == AuthFailure.EmailTaken,
                    )
                }
            }
        }
    }
}
