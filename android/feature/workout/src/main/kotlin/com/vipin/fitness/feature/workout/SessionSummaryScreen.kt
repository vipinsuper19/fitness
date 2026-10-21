package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SessionSummaryRoute(
    localId: String,
    onDone: () -> Unit,
    viewModel: SessionSummaryViewModel = hiltViewModel<SessionSummaryViewModel, SessionSummaryViewModel.Factory>(
        key = localId,
        creationCallback = { it.create(localId) },
    ),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val session = s.session
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.workout_done), style = MaterialTheme.typography.headlineSmall)
        if (session != null) {
            Text(session.name, style = MaterialTheme.typography.titleMedium)
            val done = session.completedAt ?: session.startedAt
            Text(stringResource(R.string.active_time, formatClock(session.activeSeconds(done))))
            Text(pluralStringResource(R.plurals.n_sets_done, session.completedSets, session.completedSets))
            session.perceivedDifficulty?.let { Text(stringResource(R.string.felt, it)) }
            Text(
                if (s.xpPending) stringResource(R.string.xp_pending) else stringResource(R.string.xp_awarded, session.xpAwarded ?: 0),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("xp"),
            )
            session.syncError?.let { Text(stringResource(R.string.sync_error), color = MaterialTheme.colorScheme.error) }
            SessionBadgesSection(synced = !s.xpPending)
        }
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("summary_done")) { Text(stringResource(R.string.done)) }
    }
}
