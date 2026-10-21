package com.vipin.fitness.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.AuthOutcome
import com.vipin.fitness.core.data.SessionRepository
import com.vipin.fitness.core.designsystem.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInState(
    val email: String = "",
    val password: String = "",
    val submitting: Boolean = false,
    val emailError: UiText? = null,
    val passwordError: UiText? = null,
    val error: UiText? = null,
    val signedIn: Boolean = false,
)

@HiltViewModel
class SignInViewModel @Inject constructor(private val session: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(SignInState())
    val state: StateFlow<SignInState> = _state.asStateFlow()

    /** Pre-fills the email (e.g. from "Sign in instead" on register) unless the user typed one. */
    fun prefill(email: String?) {
        if (!email.isNullOrBlank() && _state.value.email.isEmpty()) _state.update { it.copy(email = email) }
    }

    fun onEmail(v: String) = _state.update { it.copy(email = v, emailError = null, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, passwordError = null, error = null) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        val emailError = if (AuthValidation.isEmail(s.email)) null else UiText.Res(R.string.auth_error_email)
        val passwordError = if (s.password.isEmpty()) UiText.Res(R.string.auth_error_password_required) else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(submitting = true, error = null) } // set before launch: ignores double taps
        viewModelScope.launch {
            when (val r = session.signIn(s.email, s.password)) {
                AuthOutcome.Success -> _state.update { it.copy(submitting = false, signedIn = true, password = "") }
                is AuthOutcome.Failure -> _state.update { it.copy(submitting = false, error = r.reason.toUiText()) }
            }
        }
    }
}
