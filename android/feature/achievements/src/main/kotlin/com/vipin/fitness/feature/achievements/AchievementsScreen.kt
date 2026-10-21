package com.vipin.fitness.feature.achievements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.data.achievements.Badge
import com.vipin.fitness.core.data.achievements.Challenge
import com.vipin.fitness.core.data.achievements.ChallengeStatus
import com.vipin.fitness.core.data.achievements.ChallengeTemplate
import com.vipin.fitness.core.data.achievements.ChallengeType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Badges grid and challenges, as two tabs. */
@Composable
fun AchievementsRoute(
    modifier: Modifier = Modifier,
    badges: BadgesViewModel = hiltViewModel(),
    challenges: ChallengesViewModel = hiltViewModel(),
) {
    val b by badges.state.collectAsStateWithLifecycle()
    val c by challenges.state.collectAsStateWithLifecycle()
    AchievementsContent(
        badges = b,
        challenges = c,
        now = challenges.now(),
        badgeActions = BadgeActions(badges::refresh, badges::dismissCelebration),
        challengeActions = ChallengeActions(
            refresh = challenges::refresh,
            selectTarget = challenges::selectTarget,
            join = challenges::join,
            askLeave = challenges::askLeave,
            confirmLeave = challenges::confirmLeave,
            cancelLeave = challenges::cancelLeave,
            loadMore = challenges::loadMoreHistory,
            messageShown = challenges::messageShown,
        ),
        modifier = modifier,
    )
}

class BadgeActions(val refresh: () -> Unit = {}, val dismissCelebration: () -> Unit = {})

class ChallengeActions(
    val refresh: () -> Unit = {},
    val selectTarget: (String, Int) -> Unit = { _, _ -> },
    val join: (ChallengeTemplate) -> Unit = {},
    val askLeave: (Challenge) -> Unit = {},
    val confirmLeave: () -> Unit = {},
    val cancelLeave: () -> Unit = {},
    val loadMore: () -> Unit = {},
    val messageShown: () -> Unit = {},
)

/** Stateless; Compose tests drive it directly. */
@Composable
fun AchievementsContent(
    badges: BadgesState,
    challenges: ChallengesState,
    now: Instant,
    badgeActions: BadgeActions,
    challengeActions: ChallengeActions,
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
) {
    var tab by rememberSaveable { mutableIntStateOf(initialTab) }
    val snackbar = remember { SnackbarHostState() }
    val msg = challenges.message?.let { messageText(it) }
    LaunchedEffect(challenges.message) {
        if (msg != null) {
            challengeActions.messageShown()
            snackbar.showSnackbar(msg)
        }
    }
    Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, Modifier.testTag("tab_badges"), text = { Text(stringResource(R.string.tab_badges)) })
                Tab(tab == 1, { tab = 1 }, Modifier.testTag("tab_challenges"), text = { Text(stringResource(R.string.tab_challenges)) })
            }
            if (tab == 0) BadgesTab(badges, badgeActions) else ChallengesTab(challenges, now, challengeActions)
        }
    }
    if (badges.celebrate.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = badgeActions.dismissCelebration,
            confirmButton = { TextButton(badgeActions.dismissCelebration, Modifier.testTag("badge_earned_ok")) { Text(stringResource(R.string.badge_earned_ok)) } },
            title = { Text(stringResource(R.string.badge_earned_title)) },
            text = {
                Column(Modifier.testTag("badge_earned_sheet"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.celebrate.forEach { Text("${it.title} · ${stringResource(R.string.bonus_xp, it.bonusXp)}") }
                }
            },
        )
    }
    challenges.confirmLeave?.let { c ->
        AlertDialog(
            onDismissRequest = challengeActions.cancelLeave,
            confirmButton = { TextButton(challengeActions.confirmLeave, Modifier.testTag("leave_confirm")) { Text(stringResource(R.string.challenge_leave)) } },
            dismissButton = { TextButton(challengeActions.cancelLeave) { Text(stringResource(R.string.challenge_leave_cancel)) } },
            title = { Text(stringResource(R.string.challenge_leave_title)) },
            text = { Text("${c.title}\n\n${stringResource(R.string.challenge_leave_body)}") },
        )
    }
}

@Composable
private fun messageText(m: ChallengeMessage) = stringResource(
    when (m) {
        ChallengeMessage.JOINED -> R.string.msg_joined
        ChallengeMessage.LEFT -> R.string.msg_left
        ChallengeMessage.ALREADY_JOINED -> R.string.msg_already_joined
        ChallengeMessage.LIMIT_REACHED -> R.string.msg_limit_reached
        ChallengeMessage.NOT_ACTIVE -> R.string.msg_not_active
        ChallengeMessage.OFFLINE -> R.string.msg_offline
        ChallengeMessage.FAILED -> R.string.msg_failed
    },
)

@Composable
private fun LoadFailed(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.achievements_load_failed))
        Button(onRetry, Modifier.testTag("achievements_retry")) { Text(stringResource(R.string.achievements_retry)) }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

@Composable
private fun BadgesTab(s: BadgesState, a: BadgeActions) {
    when {
        s.badges.isEmpty() && s.loading -> Loading()
        s.badges.isEmpty() && s.error != null -> LoadFailed(a.refresh)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.fillMaxSize().testTag("badges_grid"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (s.badges.isEmpty()) stringResource(R.string.badges_empty)
                    else pluralStringResource(R.plurals.badges_earned_count, s.earnedCount, s.earnedCount, s.badges.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("badges_earned_count"),
                )
            }
            if (s.error != null) item(span = { GridItemSpan(maxLineSpan) }) { LoadFailed(a.refresh) }
            items(s.badges, key = { it.code }) { BadgeCard(it) }
        }
    }
}

@Composable
private fun BadgeCard(b: Badge) {
    Card(Modifier.fillMaxWidth().testTag("badge_${b.code}")) {
        Column(Modifier.padding(12.dp).alpha(if (b.earned) 1f else 0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleSmall)
            Text(b.description, style = MaterialTheme.typography.bodySmall)
            val earnedAt = b.earnedAt
            if (earnedAt != null) {
                Text(stringResource(R.string.badge_earned_on, DATE.format(earnedAt.atZone(APP_ZONE))), color = MaterialTheme.colorScheme.primary)
            } else {
                val p = b.progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.badge_progress, p.current.coerceAtMost(p.target), p.target), style = MaterialTheme.typography.labelSmall)
                } else {
                    Text(stringResource(R.string.badge_locked), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (b.bonusXp > 0) Text(stringResource(R.string.bonus_xp, b.bonusXp), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ChallengesTab(s: ChallengesState, now: Instant, a: ChallengeActions) {
    if (s.loading && s.templates.isEmpty() && s.active.isEmpty()) return Loading()
    LazyColumn(
        Modifier.fillMaxSize().testTag("challenges_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (s.loadError != null) item(key = "error") { LoadFailed(a.refresh) }
        item(key = "h_active") { Section(stringResource(R.string.challenges_active)) }
        if (s.active.isEmpty()) item(key = "none_active") { Text(stringResource(R.string.challenges_none_active)) }
        items(s.active, key = { "a_${it.id}" }) { ActiveCard(it, now, leaving = s.leaving == it.id, onLeave = { a.askLeave(it) }) }
        if (s.templates.isNotEmpty()) item(key = "h_join") { Section(stringResource(R.string.challenges_join_section)) }
        items(s.templates, key = { "t_${it.code}" }) { TemplateCard(it, s, a) }
        if (s.history.isNotEmpty()) item(key = "h_history") { Section(stringResource(R.string.challenges_history)) }
        items(s.history, key = { "h_${it.id}" }) { HistoryCard(it) }
        if (s.historyCursor != null) item(key = "more") {
            TextButton(a.loadMore, Modifier.testTag("challenges_load_more")) { Text(stringResource(R.string.challenges_load_more)) }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun ProgressLine(c: Challenge) {
    LinearProgressIndicator(progress = { c.fraction }, modifier = Modifier.fillMaxWidth())
    Text(stringResource(R.string.challenge_progress, c.progress, c.target, c.unit), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ActiveCard(c: Challenge, now: Instant, leaving: Boolean, onLeave: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("active_${c.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            ProgressLine(c)
            val left = c.daysLeft(now)
            Text(
                when (left) {
                    null -> stringResource(R.string.challenge_no_deadline)
                    0, 1 -> stringResource(R.string.challenge_ends_today)
                    else -> pluralStringResource(R.plurals.challenge_days_left, left, left)
                },
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedButton(onLeave, enabled = !leaving, modifier = Modifier.testTag("leave_${c.id}")) { Text(stringResource(R.string.challenge_leave)) }
        }
    }
}

@Composable
private fun TemplateCard(t: ChallengeTemplate, s: ChallengesState, a: ChallengeActions) {
    val joined = s.isJoined(t)
    Card(Modifier.fillMaxWidth().testTag("template_${t.code}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (t.type) {
                ChallengeType.WEEKLY -> Text(stringResource(R.string.type_weekly), style = MaterialTheme.typography.labelSmall)
                ChallengeType.PERSONAL -> Text(stringResource(R.string.type_personal), style = MaterialTheme.typography.labelSmall)
                ChallengeType.UNKNOWN -> Unit
            }
            Text(t.title, style = MaterialTheme.typography.titleSmall)
            if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall)
            if (!joined) {
                val selected = s.target(t)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.options.forEach { o ->
                        FilterChip(
                            selected = o.target == selected,
                            onClick = { a.selectTarget(t.code, o.target) },
                            label = {
                                Text(
                                    o.deadlineDays?.let { d -> pluralStringResource(R.plurals.target_with_deadline, d, o.target, d) }
                                        ?: "${o.target} ${t.unit}",
                                )
                            },
                            modifier = Modifier.testTag("target_${t.code}_${o.target}"),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { a.join(t) },
                    enabled = !joined && s.joining == null && t.options.isNotEmpty(),
                    modifier = Modifier.testTag("join_${t.code}"),
                ) { Text(stringResource(if (joined) R.string.challenge_joined else R.string.challenge_join)) }
                if (t.bonusXp > 0) Text(stringResource(R.string.bonus_xp, t.bonusXp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun HistoryCard(c: Challenge) {
    Card(Modifier.fillMaxWidth().testTag("history_${c.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            ProgressLine(c)
            Text(
                stringResource(
                    when (c.status) {
                        ChallengeStatus.COMPLETED -> R.string.status_completed
                        ChallengeStatus.EXPIRED -> R.string.status_expired
                        ChallengeStatus.LEFT -> R.string.status_left
                        ChallengeStatus.ACTIVE, ChallengeStatus.UNKNOWN -> R.string.status_unknown
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
            )
            if (c.xpAwarded > 0) Text(stringResource(R.string.bonus_xp, c.xpAwarded), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Home entry card (via Home's extraSlots, wired in :app). */
@Composable
fun AchievementsHomeCard(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Card(onClick = onOpen, modifier = modifier.fillMaxWidth().testTag("home_achievements")) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.home_badges_entry), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_badges_entry_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
