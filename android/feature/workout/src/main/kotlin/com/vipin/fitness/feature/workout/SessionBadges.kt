package com.vipin.fitness.feature.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Checks for newly earned badges once the session has [synced]. */
@Composable
fun SessionBadgesSection(synced: Boolean, viewModel: SessionBadgesViewModel = hiltViewModel()) {
    LaunchedEffect(synced) { if (synced) viewModel.check() }
    val s by viewModel.state.collectAsStateWithLifecycle()
    SessionBadgesContent(s)
}

@Composable
internal fun SessionBadgesContent(s: SessionBadgesState) {
    when (s) {
        SessionBadgesState.Idle, SessionBadgesState.None -> Unit
        SessionBadgesState.Unavailable -> Text(
            stringResource(R.string.badges_coming_soon),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("summary_badges_unavailable"),
        )
        is SessionBadgesState.Earned -> Card(Modifier.fillMaxWidth().testTag("summary_badges_earned")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.badges_earned_title), style = MaterialTheme.typography.titleMedium)
                s.badges.forEach { b ->
                    Text(b.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("summary_badge_${b.code}"))
                    if (b.bonusXp > 0) Text(stringResource(R.string.badge_bonus_xp, b.bonusXp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
