package com.vipin.fitness.feature.workout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.history.PastSession
import com.vipin.fitness.core.data.history.PastSet
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object HistoryTags {
    const val LIST = "history_list"
    const val EMPTY = "history_empty"
    const val ERROR = "history_error"
    const val RETRY = "history_retry"
    const val LOADING = "history_loading"
    const val MORE_RETRY = "history_more_retry"
    fun row(id: String) = "history_row_$id"
    const val DETAIL = "past_session"
    const val SET = "past_session_set"
}

private fun HistoryError.message() = when (this) {
    HistoryError.OFFLINE -> R.string.history_offline
    HistoryError.FAILED -> R.string.history_failed
    HistoryError.NOT_FOUND -> R.string.history_not_found
}

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
internal fun PastSession.whenText(zone: ZoneId = ZoneId.systemDefault()): String = dateFormat.format((completedAt ?: startedAt).atZone(zone))

@Composable
fun HistoryRoute(onOpen: (String) -> Unit, viewModel: HistoryViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    HistoryContent(s, onOpen, onRetry = viewModel::refresh, onLoadMore = viewModel::loadMore)
}

@Composable
fun HistoryContent(s: HistoryState, onOpen: (String) -> Unit, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    when {
        s.loading -> Box(Modifier.fillMaxSize().testTag(HistoryTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.error != null -> ErrorState(s.error, onRetry)
        s.empty -> Column(
            Modifier.fillMaxSize().padding(24.dp).testTag(HistoryTags.EMPTY),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.history_empty_body), modifier = Modifier.padding(top = 8.dp))
        }
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag(HistoryTags.LIST),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall) }
            items(s.items, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(p.id) }.testTag(HistoryTags.row(p.id))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(p.whenText(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.active_time, formatClock(p.activeSeconds.toLong())) + " · " +
                                pluralStringResource(R.plurals.history_sets, p.completedSets, p.completedSets),
                        )
                    }
                }
            }
            item {
                when {
                    s.loadingMore -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    s.moreError != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(s.moreError.message()), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onLoadMore, modifier = Modifier.testTag(HistoryTags.MORE_RETRY)) { Text(stringResource(R.string.retry)) }
                    }
                    // Reaching the end of the list fetches the next page.
                    s.nextCursor != null -> LaunchedEffect(s.nextCursor) { onLoadMore() }
                }
            }
        }
    }
}

@Composable
private fun ErrorState(error: HistoryError, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).testTag(HistoryTags.ERROR),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(error.message()))
        if (error != HistoryError.NOT_FOUND) {
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(HistoryTags.RETRY)) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun PastSessionRoute(
    sessionId: String,
    viewModel: PastSessionViewModel = hiltViewModel<PastSessionViewModel, PastSessionViewModel.Factory>(
        key = sessionId,
        creationCallback = { it.create(sessionId) },
    ),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    PastSessionContent(s, onRetry = viewModel::load)
}

@Composable
fun PastSessionContent(s: PastSessionState, onRetry: () -> Unit) {
    val session = s.session
    when {
        s.loading -> Box(Modifier.fillMaxSize().testTag(HistoryTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.error != null || session == null -> ErrorState(s.error ?: HistoryError.FAILED, onRetry)
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag(HistoryTags.DETAIL),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(session.whenText(), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.active_time, formatClock(session.activeSeconds.toLong())))
                session.perceivedDifficulty?.let { Text(stringResource(R.string.history_difficulty, it)) }
                session.notes?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            val byExercise = session.sets.groupBy { it.exerciseId }
            byExercise.forEach { (exerciseId, sets) ->
                item(key = exerciseId) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.exerciseNames[exerciseId] ?: stringResource(R.string.history_unknown_exercise), style = MaterialTheme.typography.titleMedium)
                            sets.sortedBy { it.setIndex }.forEach { set ->
                                Row(Modifier.fillMaxWidth().testTag(HistoryTags.SET)) {
                                    Text(stringResource(R.string.history_set_n, set.setIndex + 1), modifier = Modifier.weight(1f))
                                    Text(set.summary() + if (set.completed) "" else " · " + stringResource(R.string.history_skipped))
                                }
                            }
                        }
                    }
                }
            }
            if (byExercise.isEmpty()) item { Text(stringResource(R.string.history_no_sets)) }
        }
    }
}

/** "10 × 20 kg", "45 s", "1.2 km"; whatever the set recorded. */
internal fun PastSet.summary(): String = listOfNotNull(
    when {
        reps != null && weightKg != null -> "$reps × ${trimNumber(weightKg)} kg"
        reps != null -> "$reps reps"
        weightKg != null -> "${trimNumber(weightKg)} kg"
        else -> null
    },
    durationSeconds?.let { if (it >= 60) formatClock(it.toLong()) else "$it s" },
    distanceM?.let { if (it >= 1000) "${trimNumber(it / 1000)} km" else "${trimNumber(it)} m" },
).joinToString(" · ").ifEmpty { "–" }

private fun trimNumber(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else "%.1f".format(d)
