package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.workout.PlanName
import com.vipin.fitness.core.data.workout.PlanStatus
import com.vipin.fitness.core.data.workout.PlanSummary

object PlansTags {
    const val LOADING = "plans_loading"
    const val ERROR = "plans_error"
    const val EMPTY = "plans_empty"
    const val RETRY = "plans_retry"
    const val ARCHIVED_NOTICE = "plans_archived_notice"
    const val REFRESH = "plans_refresh"
    const val NEW_PLAN = "plans_new"
    const val RENAME_FIELD = "plans_rename_field"
    const val RENAME_SAVE = "plans_rename_save"
    const val RENAME_ERROR = "plans_rename_error"
    fun filter(status: PlanStatus?) = "plans_filter_${status?.wire ?: "all"}"
    fun row(id: String) = "plans_row_$id"
    fun action(id: String, action: PlanAction) = "plans_${action.name.lowercase()}_$id"
}

/** "Edit as today": a new or changed plan goes through the existing builder ([onNewPlan]). */
@Composable
fun PlansRoute(onNewPlan: () -> Unit, viewModel: PlansViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    PlansContent(
        s,
        onFilter = viewModel::setFilter,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onAction = viewModel::perform,
        onDismissError = viewModel::dismissActionError,
        onRename = viewModel::submitRename,
        onCancelRename = viewModel::cancelRename,
        onNewPlan = onNewPlan,
    )
}

private val filters = listOf(null, PlanStatus.ACTIVE, PlanStatus.PAUSED, PlanStatus.INACTIVE, PlanStatus.ARCHIVED)

private fun PlanStatus?.label() = when (this) {
    null -> R.string.plans_filter_all
    PlanStatus.ACTIVE -> R.string.plans_filter_active
    PlanStatus.PAUSED -> R.string.plans_filter_paused
    PlanStatus.INACTIVE -> R.string.plans_filter_inactive
    PlanStatus.ARCHIVED -> R.string.plans_filter_archived
}

private fun PlanAction.label() = when (this) {
    PlanAction.PAUSE -> R.string.plan_pause
    PlanAction.RESUME -> R.string.plan_resume
    PlanAction.RESTART -> R.string.plan_restart
    PlanAction.MAKE_ACTIVE -> R.string.plan_make_active
    PlanAction.ARCHIVE -> R.string.plan_archive
    PlanAction.UNARCHIVE -> R.string.plan_unarchive
    PlanAction.RENAME -> R.string.plan_rename
}

private fun PlansError.message() = when (this) {
    PlansError.OFFLINE -> R.string.plans_offline
    PlansError.ARCHIVED -> R.string.plan_was_archived
    PlansError.INVALID_NAME -> R.string.plan_name_invalid
    PlansError.FAILED -> R.string.plans_failed
}

@Composable
fun PlansContent(
    s: PlansState,
    onFilter: (PlanStatus?) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAction: (String, PlanAction) -> Unit,
    onDismissError: () -> Unit,
    onRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onNewPlan: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.plans_title), style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { f ->
                FilterChip(
                    selected = s.filter == f,
                    onClick = { onFilter(f) },
                    label = { Text(stringResource(f.label())) },
                    modifier = Modifier.testTag(PlansTags.filter(f)),
                )
            }
        }
        s.actionError?.let { e ->
            Card(Modifier.fillMaxWidth().testTag(if (e == PlansError.ARCHIVED) PlansTags.ARCHIVED_NOTICE else PlansTags.ERROR)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(e.message()), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    if (e == PlansError.ARCHIVED) {
                        TextButton(onClick = { onDismissError(); onRefresh() }, modifier = Modifier.testTag(PlansTags.REFRESH)) {
                            Text(stringResource(R.string.plan_refresh))
                        }
                    } else {
                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.plan_cancel)) }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                s.loading -> Box(Modifier.fillMaxSize().testTag(PlansTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                s.error != null -> Column(
                    Modifier.fillMaxSize().testTag(PlansTags.ERROR),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(s.error.message()))
                    Button(onClick = onRefresh, modifier = Modifier.padding(top = 12.dp).testTag(PlansTags.RETRY)) { Text(stringResource(R.string.retry)) }
                }
                s.empty -> Text(stringResource(R.string.plans_empty), Modifier.padding(top = 24.dp).testTag(PlansTags.EMPTY))
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(s.items, key = { it.id }) { p -> PlanRow(p, busy = s.busyId != null, onAction = onAction) }
                    item {
                        when {
                            s.loadingMore -> CircularProgressIndicator(Modifier.padding(8.dp))
                            s.moreError != null -> OutlinedButton(onClick = onLoadMore) { Text(stringResource(R.string.retry)) }
                            s.nextCursor != null -> LaunchedEffect(s.nextCursor) { onLoadMore() }
                        }
                    }
                }
            }
        }
        Button(onClick = onNewPlan, modifier = Modifier.fillMaxWidth().testTag(PlansTags.NEW_PLAN)) { Text(stringResource(R.string.start_new_plan)) }
    }
    s.rename?.let { RenameDialog(it, onRename, onCancelRename) }
}

@Composable
private fun PlanRow(p: PlanSummary, busy: Boolean, onAction: (String, PlanAction) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(PlansTags.row(p.id))) {
        Column(Modifier.padding(12.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            val week = p.currentWeek
            Text(
                stringResource(p.status.label()) + " · " +
                    (if (week != null) stringResource(R.string.plans_week, week, p.totalWeeks) else stringResource(R.string.plans_weeks, p.totalWeeks)) +
                    " · " + stringResource(R.string.plans_sessions, p.completedSessions),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                p.status.actions().forEach { a ->
                    OutlinedButton(onClick = { onAction(p.id, a) }, enabled = !busy, modifier = Modifier.testTag(PlansTags.action(p.id, a))) {
                        Text(stringResource(a.label()))
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(r: RenameState, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable(r.planId) { mutableStateOf(r.initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.plan_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(PlanName.MAX + 20) },
                    label = { Text(stringResource(R.string.plan_name_label)) },
                    singleLine = true,
                    isError = r.error != null,
                    modifier = Modifier.testTag(PlansTags.RENAME_FIELD),
                )
                r.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(PlansTags.RENAME_ERROR)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = !r.saving, modifier = Modifier.testTag(PlansTags.RENAME_SAVE)) { Text(stringResource(R.string.plan_save)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.plan_cancel)) } },
    )
}
