package com.vipin.fitness.feature.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.HomeRepository
import com.vipin.fitness.core.data.ProfileZone
import com.vipin.fitness.core.data.XpBalance
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
import java.time.ZoneId
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
    /** Header total; null until loaded or when it fails (the list still shows). */
    val balance: XpBalance? = null,
    /** Times show in the profile's zone (APP_ZONE until it loads or if it can't). */
    val zone: ZoneId = APP_ZONE,
) {
    val empty get() = !loading && error == null && items.isEmpty()
}

/** The read-only XP ledger, newest first, paged by cursor. */
@HiltViewModel
class XpLedgerViewModel @Inject constructor(
    private val repo: XpLedgerRepository,
    private val home: HomeRepository,
    private val profileZone: ProfileZone,
) : ViewModel() {
    /** Bumped by every refresh; a page that lands for an older generation is ignored. */
    private var generation = 0
    private val _state = MutableStateFlow(XpLedgerState())
    val state: StateFlow<XpLedgerState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val gen = ++generation
        _state.update { XpLedgerState(loading = true, balance = it.balance, zone = it.zone) }
        viewModelScope.launch {
            val r = repo.ledger(null)
            if (gen != generation) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, items = r.value.items, nextCursor = r.value.nextCursor)
                    is ApiResult.Failure -> it.copy(loading = false, error = r.error.toLedgerError())
                }
            }
        }
        viewModelScope.launch {
            val z = profileZone.zone()
            _state.update { it.copy(zone = z) }
        }
        viewModelScope.launch {
            (home.xpBalance() as? ApiResult.Success)?.value?.let { b -> _state.update { it.copy(balance = b) } }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        _state.update { it.copy(loadingMore = true, moreError = null) }
        val gen = generation
        viewModelScope.launch {
            val r = repo.ledger(cursor)
            // A refresh started meanwhile: this page belongs to the old list.
            if (gen != generation) return@launch
            when (r) {
                is ApiResult.Success -> _state.update {
                    val seen = it.items.mapTo(HashSet()) { e -> e.id }
                    it.copy(loadingMore = false, items = it.items + r.value.items.filter { e -> e.id !in seen }, nextCursor = r.value.nextCursor)
                }
                is ApiResult.Failure -> _state.update { it.copy(loadingMore = false, moreError = r.error.toLedgerError()) }
            }
        }
    }
}
