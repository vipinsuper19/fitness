package com.vipin.fitness.core.designsystem

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Text a ViewModel can produce without a Context: a resource, or a server-provided message. */
sealed interface UiText {
    data class Res(@StringRes val id: Int) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id)
        is Raw -> text
    }
}
