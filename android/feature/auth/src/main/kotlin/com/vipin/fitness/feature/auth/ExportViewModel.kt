package com.vipin.fitness.feature.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.AccountRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ExportError { RATE_LIMITED, OFFLINE, FAILED, SAVE_FAILED }

data class ExportState(
    val loading: Boolean = false,
    val error: ExportError? = null,
    /** The written export, ready to share or save. */
    val file: Uri? = null,
    /** One-shot: open the share sheet for [file] (right after a successful export). */
    val shareRequested: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val account: AccountRepository,
    private val files: ExportFiles,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportState())
    val state: StateFlow<ExportState> = _state.asStateFlow()

    val suggestedName: String get() = files.suggestedName()

    /** Start and Retry. A read with no side effects, so no idempotency key is needed. */
    fun export() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null, saved = false) } // before launch: ignores double taps
        viewModelScope.launch {
            when (val r = account.exportJson()) {
                is ApiResult.Success -> {
                    val uri = runCatching { files.write(r.value) }.getOrNull()
                    _state.update {
                        if (uri == null) it.copy(loading = false, error = ExportError.SAVE_FAILED)
                        else it.copy(loading = false, file = uri, shareRequested = true)
                    }
                }
                is ApiResult.Failure -> _state.update { it.copy(loading = false, error = r.error.toExportError()) }
            }
        }
    }

    fun share() = _state.update { if (it.file != null) it.copy(shareRequested = true) else it }
    fun shareShown() = _state.update { it.copy(shareRequested = false) }

    /** The user picked where to save (ACTION_CREATE_DOCUMENT); null = cancelled. */
    fun saveTo(target: Uri?) {
        val from = _state.value.file ?: return
        if (target == null) return
        viewModelScope.launch {
            val ok = runCatching { files.copy(from, target) }.isSuccess
            _state.update { it.copy(saved = ok, error = if (ok) null else ExportError.SAVE_FAILED) }
        }
    }
}

internal fun ApiError.toExportError(): ExportError = when (this) {
    ApiError.Network -> ExportError.OFFLINE
    is ApiError.Http -> if (status == 429) ExportError.RATE_LIMITED else ExportError.FAILED
    is ApiError.Unexpected -> ExportError.FAILED
}
