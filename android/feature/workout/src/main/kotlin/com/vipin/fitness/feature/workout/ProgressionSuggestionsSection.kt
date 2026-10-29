package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.progression.ProgressionReason
import com.vipin.fitness.core.data.progression.ProgressionSuggestion
import com.vipin.fitness.core.designsystem.ProgressionTags

@Composable
fun ProgressionSuggestionsRoute(planId: String, modifier: Modifier = Modifier, viewModel: ProgressionSuggestionsViewModel = hiltViewModel()) {
    LaunchedEffect(planId) { viewModel.load(planId) }
    val s by viewModel.state.collectAsStateWithLifecycle()
    ProgressionSuggestionsSection(s, viewModel::accept, viewModel::skip, viewModel::apply, viewModel::retry, modifier)
}

@Composable
fun ProgressionSuggestionsSection(
    s: ProgressionUiState,
    onAccept: (String) -> Unit,
    onSkip: (String) -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag(ProgressionTags.SECTION), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.progression_title), style = MaterialTheme.typography.titleMedium)
        when {
            s.loading && s.data == null -> CircularProgressIndicator(Modifier.testTag(ProgressionTags.LOADING))
            s.loadError == ProgressionLoadError.ARCHIVED ->
                Text(stringResource(R.string.progression_archived), Modifier.testTag(ProgressionTags.ERROR))
            s.loadError != null && s.data == null -> LoadErrorRow(s, onRetry)
            s.rows.isEmpty() -> Text(stringResource(R.string.progression_empty), Modifier.testTag(ProgressionTags.EMPTY))
            else -> {
                // A failed refresh over shown suggestions (e.g. 429 after an apply): say so, keep them.
                if (s.loadError != null) LoadErrorRow(s, onRetry)
                Text(stringResource(R.string.progression_intro), style = MaterialTheme.typography.bodySmall)
                s.rows.forEach { row ->
                    SuggestionCard(row.suggestion, row.workoutName, s.names, s.units, s.decisions[row.suggestion.key], !s.applying, onAccept, onSkip)
                }
                Button(onClick = onApply, enabled = s.canApply, modifier = Modifier.fillMaxWidth().testTag(ProgressionTags.APPLY)) {
                    Text(stringResource(R.string.progression_apply, s.acceptedKeys.size))
                }
            }
        }
        s.status?.let { Text(statusText(it), Modifier.testTag(ProgressionTags.APPLY_STATUS), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LoadErrorRow(s: ProgressionUiState, onRetry: () -> Unit) {
    val text = when (s.loadError) {
        ProgressionLoadError.OFFLINE -> stringResource(R.string.progression_offline)
        ProgressionLoadError.RATE_LIMITED -> s.loadRetryAfterMs?.let { stringResource(R.string.progression_rate_limited, ((it + 999) / 1000).toInt()) }
            ?: stringResource(R.string.progression_rate_limited_unknown)
        else -> stringResource(R.string.progression_failed)
    }
    Text(text, Modifier.testTag(ProgressionTags.ERROR), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = onRetry, enabled = !s.loading, modifier = Modifier.testTag(ProgressionTags.RETRY)) { Text(stringResource(R.string.progression_retry)) }
}

@Composable
private fun statusText(st: ApplyStatus): String = when (st) {
    is ApplyStatus.Applied -> stringResource(R.string.progression_status_applied, st.newVersion)
    is ApplyStatus.Conflict -> st.currentVersion?.let { stringResource(R.string.progression_status_conflict, it) }
        ?: stringResource(R.string.progression_status_conflict_unknown)
    ApplyStatus.Archived -> stringResource(R.string.progression_archived)
    ApplyStatus.KeyReused -> stringResource(R.string.progression_status_key_reused)
    ApplyStatus.InProgress -> stringResource(R.string.progression_status_in_progress)
    is ApplyStatus.RateLimited -> st.retryAfterMs?.let { stringResource(R.string.progression_status_rate_limited, ((it + 999) / 1000).toInt()) }
        ?: stringResource(R.string.progression_status_rate_limited_unknown)
    ApplyStatus.Offline -> stringResource(R.string.progression_status_offline)
    ApplyStatus.Failed -> stringResource(R.string.progression_status_failed)
}

@Composable
private fun SuggestionCard(
    sg: ProgressionSuggestion,
    workoutName: String,
    names: Map<String, String>,
    units: UnitSystem,
    decision: SuggestionDecision?,
    enabled: Boolean,
    onAccept: (String) -> Unit,
    onSkip: (String) -> Unit,
) {
    val key = sg.key
    Card(Modifier.fillMaxWidth().testTag(ProgressionTags.row(key))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val name = names[sg.exerciseId] ?: stringResource(R.string.progression_unknown_exercise, sg.order + 1)
            Text(stringResource(R.string.progression_row_title, workoutName, name), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(ProgressionFormat.actionRes(sg.action)), style = MaterialTheme.typography.labelMedium)
            val current = ProgressionFormat.prescription(sg.current, sg.trackingType, units)
            val value = sg.suggested?.let { stringResource(R.string.progression_change, current, ProgressionFormat.prescription(it, sg.trackingType, units)) } ?: current
            Text(value, Modifier.testTag(ProgressionTags.value(key)))
            Text(reasonText(sg, names), Modifier.testTag(ProgressionTags.reason(key)), style = MaterialTheme.typography.bodySmall)
            val why = ProgressionFormat.whyParts(sg.inputs).map { p -> stringResource(p.res, *p.args.toTypedArray()) }
            if (why.isNotEmpty()) {
                Text(
                    why.joinToString(stringResource(R.string.progression_why_separator)),
                    Modifier.testTag(ProgressionTags.why(key)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sg.actionable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val accepted = decision == SuggestionDecision.ACCEPT
                    val skipped = decision == SuggestionDecision.SKIP
                    if (accepted) Button(onClick = { onAccept(key) }, enabled = enabled, modifier = Modifier.testTag(ProgressionTags.accept(key))) { Text(stringResource(R.string.progression_accepted)) }
                    else OutlinedButton(onClick = { onAccept(key) }, enabled = enabled, modifier = Modifier.testTag(ProgressionTags.accept(key))) { Text(stringResource(R.string.progression_accept)) }
                    if (skipped) Button(onClick = { onSkip(key) }, enabled = enabled, modifier = Modifier.testTag(ProgressionTags.skip(key))) { Text(stringResource(R.string.progression_skipped)) }
                    else OutlinedButton(onClick = { onSkip(key) }, enabled = enabled, modifier = Modifier.testTag(ProgressionTags.skip(key))) { Text(stringResource(R.string.progression_skip)) }
                }
            }
        }
    }
}

@Composable
private fun reasonText(sg: ProgressionSuggestion, names: Map<String, String>): String = when {
    sg.reason == ProgressionReason.COOLDOWN ->
        sg.cooldownUntil?.let { stringResource(R.string.progression_reason_cooldown, ProgressionFormat.dateTime(it)) }
            ?: stringResource(R.string.progression_reason_cooldown_no_date)
    sg.reason == ProgressionReason.REP_CEILING && sg.harderVariations.isNotEmpty() ->
        stringResource(
            R.string.progression_reason_rep_ceiling_variations,
            sg.harderVariations.mapNotNull { names[it] }.ifEmpty { sg.harderVariations }.joinToString(", "),
        )
    else -> stringResource(ProgressionFormat.reasonRes(sg.reason, hasVariations = false))
}
