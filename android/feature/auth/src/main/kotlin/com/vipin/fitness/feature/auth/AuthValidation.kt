package com.vipin.fitness.feature.auth

import com.vipin.fitness.core.data.AuthFailure
import com.vipin.fitness.core.designsystem.UiText

internal object AuthValidation {
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 128
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun isEmail(s: String) = s.trim().length <= 254 && EMAIL.matches(s.trim())
}

internal fun AuthFailure.toUiText(): UiText = when (this) {
    AuthFailure.InvalidCredentials -> UiText.Res(R.string.auth_error_invalid_credentials)
    AuthFailure.EmailTaken -> UiText.Res(R.string.auth_error_email_taken)
    AuthFailure.RateLimited -> UiText.Res(R.string.auth_error_rate_limited)
    AuthFailure.Network -> UiText.Res(R.string.auth_error_network)
    is AuthFailure.Validation -> UiText.Raw(message)
    is AuthFailure.Other -> UiText.Raw(message)
}
