package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.workout.PlanManagementRepository
import com.vipin.fitness.core.data.workout.PlanName
import com.vipin.fitness.core.data.workout.PlanRowUpdate
import com.vipin.fitness.core.data.workout.applying
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.data.workout.PlanSummary
import com.vipin.fitness.core.data.workout.isPlanArchived
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.isRetryable
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PlansError { OFFLINE, FAILED, ARCHIVED, INVALID_NAME }

internal fun ApiError.toPlansError() = when {
    this == ApiError.Network -> PlansError.OFFLINE
    isPlanArchived() -> PlansError.ARCHIVED
    this is ApiError.Http && status == 400 && code == "VALIDATION_FAILED" -> PlansError.INVALID_NAME
    else -> PlansError.FAILED
}

enum class PlanAction { PAUSE, RESUME, RESTART, MAKE_ACTIVE, ARCHIVE, UNARCHIVE, RENAME }

/** What a row offers for its status. MAKE_ACTIVE and RESTART are the same call (activate, no body). */
fun PlanStatus.actions(): List<PlanAction> = when (this) {
    PlanStatus.ACTIVE -> listOf(PlanAction.PAUSE, PlanAction.RESTART, PlanAction.RENAME, PlanAction.ARCHIVE)
    PlanStatus.PAUSED -> listOf(PlanAction.RESUME, PlanAction.RESTART, PlanAction.RENAME, PlanAction.ARCHIVE)
    PlanStatus.INACTIVE -> listOf(PlanAction.MAKE_ACTIVE, PlanAction.RENAME, PlanAction.ARCHIVE)
    PlanStatus.ARCHIVED -> listOf(PlanAction.UNARCHIVE)
}

data class RenameState(val planId: String, val initial: String, val saving: Boolean = false, val error: PlansError? = null)

data class PlansState(
    /** null = everything except archived (the server default). */
    val filter: PlanStatus? = null,
    val loading: Boolean = true,
    val items: List<PlanSummary> = emptyList(),
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** First page failed. */
    val error: PlansError? = null,
    /** A later page failed; the list stays. */
    val moreError: PlansError? = null,
    /** Plan id with a write in flight. */
    val busyId: String? = null,
    /** A row action failed; ARCHIVED shows "This plan was archived" with Refresh. */
    val actionError: PlansError? = null,
    val rename: RenameState? = null,
) {
    val empty get() = !loading && error == null && items.isEmpty()
}

@HiltViewModel
class PlansViewModel @Inject constructor(
    private val repo: PlanManagementRepository,
) : ViewModel() {
    internal var newKey: () -> String = ::newIdempotencyKey
    private val _state = MutableStateFlow(PlansState())
    val state: StateFlow<PlansState> = _state.asStateFlow()

    /** Bumped on every refresh so a page that lands after one is dropped. */
    private var generation = 0

    init {
        refresh()
    }

    fun setFilter(filter: PlanStatus?) {
        if (filter == _state.value.filter && !_state.value.loading) return
        _state.update { it.copy(filter = filter) }
        refresh()
    }

    fun refresh() {
        val gen = ++generation
        val filter = _state.value.filter
        _state.update { PlansState(filter = filter, loading = true, rename = it.rename) }
        viewModelScope.launch {
            val r = repo.list(filter)
            if (gen != generation) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> it.copy(loading = false, items = r.value.items, nextCursor = r.value.nextCursor)
                    is ApiResult.Failure -> it.copy(loading = false, error = r.error.toPlansError())
                }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loading || s.loadingMore) return
        val gen = generation
        _state.update { it.copy(loadingMore = true, moreError = null) }
        viewModelScope.launch {
            val r = repo.list(s.filter, cursor)
            if (gen != generation) return@launch
            _state.update {
                when (r) {
                    is ApiResult.Success -> {
                        val seen = it.items.mapTo(HashSet()) { p -> p.id }
                        it.copy(loadingMore = false, items = it.items + r.value.items.filter { p -> p.id !in seen }, nextCursor = r.value.nextCursor)
                    }
                    is ApiResult.Failure -> it.copy(loadingMore = false, moreError = r.error.toPlansError())
                }
            }
        }
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    /**
     * One Idempotency-Key per (plan, action), kept while the outcome may still change on retry
     * (offline, 429, 5xx) so the retry is deduped; dropped once it succeeds or fails for good.
     */
    private val actionKeys = mutableMapOf<String, String>()

    private fun settleKey(slot: String, r: ApiResult<*>) {
        if (!(r is ApiResult.Failure && r.error.isRetryable)) actionKeys.remove(slot)
    }

    fun perform(planId: String, action: PlanAction) {
        if (action == PlanAction.RENAME) return startRename(planId)
        if (_state.value.busyId != null) return
        val key = actionKeys.getOrPut("$planId:$action") { newKey() }
        val status = _state.value.items.firstOrNull { it.id == planId }?.status
        _state.update { it.copy(busyId = planId, actionError = null) }
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                PlanAction.PAUSE -> repo.pause(planId, key)
                PlanAction.RESUME -> repo.resume(planId, key)
                PlanAction.RESTART, PlanAction.MAKE_ACTIVE -> repo.restart(planId, key, wasActive = status == PlanStatus.ACTIVE)
                PlanAction.ARCHIVE -> repo.archive(planId, key)
                PlanAction.UNARCHIVE -> repo.unarchive(planId, key)
                PlanAction.RENAME -> error("handled above")
            }
            _state.update { it.copy(busyId = null) }
            settleKey("$planId:$action", r)
            if (action == PlanAction.UNARCHIVE && r is ApiResult.Success) {
                // The 200 plan says where it went (paused or inactive): update the row, no reload.
                val u = r.value as PlanRowUpdate
                _state.update { s ->
                    // Under the Archived filter the restored plan no longer belongs in the list.
                    if (s.filter != null && s.filter != u.status) s.copy(items = s.items.filter { it.id != planId })
                    else s.copy(items = s.items.map { if (it.id == planId) it.applying(u) else it })
                }
                return@launch
            }
            when (r) {
                // Statuses move between filters (and switching pauses the old plan), so reload.
                is ApiResult.Success -> refresh()
                is ApiResult.Failure -> _state.update { it.copy(actionError = r.error.toPlansError()) }
            }
        }
    }

    fun startRename(planId: String) {
        val plan = _state.value.items.firstOrNull { it.id == planId } ?: return
        _state.update { it.copy(rename = RenameState(planId, plan.name)) }
    }

    fun cancelRename() = _state.update { it.copy(rename = null) }

    fun submitRename(raw: String) {
        val rename = _state.value.rename ?: return
        if (rename.saving) return
        val name = PlanName.normalize(raw)
            ?: return _state.update { it.copy(rename = rename.copy(error = PlansError.INVALID_NAME)) }
        val slot = "${rename.planId}:rename:$name"
        val key = actionKeys.getOrPut(slot) { newKey() }
        _state.update { it.copy(rename = rename.copy(saving = true, error = null)) }
        viewModelScope.launch {
            val renamed = repo.rename(rename.planId, name, key)
            settleKey(slot, renamed)
            when (val r = renamed) {
                is ApiResult.Success -> _state.update { s ->
                    s.copy(rename = null, items = s.items.map { if (it.id == rename.planId) it.copy(name = r.value.name) else it })
                }
                is ApiResult.Failure -> {
                    val e = r.error.toPlansError()
                    if (e == PlansError.ARCHIVED) {
                        // The rename can't succeed; close the dialog and show the archived notice.
                        _state.update { it.copy(rename = null, actionError = e) }
                    } else {
                        _state.update { it.copy(rename = rename.copy(saving = false, error = e)) }
                    }
                }
            }
        }
    }
}
