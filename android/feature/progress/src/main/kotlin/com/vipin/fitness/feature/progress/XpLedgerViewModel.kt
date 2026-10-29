package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.xp.XpEntry
import com.vipin.fitness.core.data.xp.XpLedgerRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class XpLedgerError { OFFLINE, FAILED }

private fun ApiError.toLedgerError() = if (this == ApiError.Network) XpLedgerError.OFFLINE else XpLedgerError.FAILED

data class XpLedgerState(
    val loading: Boolean = true,
    val items: List<XpEntry> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    val error: XpLedgerError? = null,
    val moreError: XpLedgerError? = null,
) {
    val empty get() = !loading && error == null && items.isEmpty()
}

/** The read-only XP ledger, newest first, paged by cursor. */
@HiltViewModel
class XpLedgerViewModel @Inject constructor(private val repo: XpLedgerRepository) : ViewModel() {
    private val _state = MutableStateFlow(XpLedgerState())
    val state: StateFlow<XpLedgerState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = XpLedgerState(loading = true)
        viewModelScope.launch {
            _state.value = when (val r = repo.ledger(null)) {
                is ApiResult.Success -> XpLedgerState(loading = false, items = r.value.items, nextCursor = r.value.nextCursor)
                is ApiResult.Failure -> XpLedgerState(loading = false, error = r.error.toLedgerError())
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        viewModelScope.launch {
            when (val r = repo.ledger(cursor)) {
                is ApiResult.Success -> _state.update {
                    val seen = it.items.mapTo(HashSet()) { e -> e.id }
                    it.copy(loadingMore = false, items = it.items + r.value.items.filter { e -> e.id !in seen }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.toLedgerError()) }
            }
        }
    }
}
