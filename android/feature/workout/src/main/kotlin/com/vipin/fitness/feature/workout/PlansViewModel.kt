package com.vipin.fitness.feature.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vipin.fitness.core.data.newIdempotencyKey
import com.vipin.fitness.core.data.workout.PlanManagementRepository
import com.vipin.fitness.core.data.workout.PlanName
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.data.workout.PlanSummary
import com.vipin.fitness.core.data.workout.isPlanArchived
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
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

    fun perform(planId: String, action: PlanAction) {
        if (action == PlanAction.RENAME) return startRename(planId)
        if (_state.value.busyId != null) return
        val key = newKey()
        _state.update { it.copy(busyId = planId, actionError = null) }
        viewModelScope.launch {
            val r: ApiResult<*> = when (action) {
                PlanAction.PAUSE -> repo.pause(planId, key)
                PlanAction.RESUME -> repo.resume(planId, key)
                PlanAction.RESTART, PlanAction.MAKE_ACTIVE -> repo.restart(planId, key)
                PlanAction.ARCHIVE -> repo.archive(planId, key)
                PlanAction.UNARCHIVE -> repo.unarchive(planId, key)
                PlanAction.RENAME -> error("handled above")
            }
            _state.update { it.copy(busyId = null) }
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
        val key = newKey()
        _state.update { it.copy(rename = rename.copy(saving = true, error = null)) }
        viewModelScope.launch {
            when (val r = repo.rename(rename.planId, name, key)) {
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
