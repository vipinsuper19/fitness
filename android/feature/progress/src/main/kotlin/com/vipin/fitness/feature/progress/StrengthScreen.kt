package com.vipin.fitness.feature.progress

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ExerciseProgressPoint
import com.vipin.fitness.core.data.PersonalRecords
import com.vipin.fitness.core.data.SummaryBucket
import java.time.ZoneId

object StrengthTags {
    const val TAB = "progress_tab_strength"
    const val LIST = "strength_list"
    const val LOADING = "strength_loading"
    const val EMPTY = "strength_empty"
    const val ERROR = "strength_error"
    const val RETRY = "strength_retry"
    const val MORE_RETRY = "strength_more_retry"
    const val DETAIL = "strength_detail"
    const val DETAIL_BACK = "strength_detail_back"
    const val DETAIL_ERROR = "strength_detail_error"
    const val DETAIL_RETRY = "strength_detail_retry"
    const val PR = "strength_pr"
    fun row(id: String) = "strength_row_$id"
    fun bucket(b: SummaryBucket) = "strength_bucket_${b.wire}"
    fun point(period: String) = "strength_point_$period"
}

@Composable
fun StrengthScreen(vm: StrengthViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    StrengthContent(s, vm::refresh, vm::loadMore, vm::open, vm::setBucket, vm::closeDetail, vm::retryDetail)
}

@Composable
fun StrengthContent(
    s: StrengthState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpen: (String) -> Unit,
    onBucket: (SummaryBucket) -> Unit,
    onBack: () -> Unit,
    onRetryDetail: () -> Unit,
) {
    val d = s.detail
    if (d != null) {
        BackHandler(onBack = onBack)
        DetailBody(d, s.units, s.zone, onBucket, onBack, onRetryDetail)
        return
    }
    when {
        s.loading -> Box(Modifier.fillMaxSize().testTag(StrengthTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.error != null -> ErrorBox(s.error, StrengthTags.ERROR, StrengthTags.RETRY, onRetry)
        s.empty -> Box(Modifier.fillMaxSize().padding(24.dp).testTag(StrengthTags.EMPTY), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.strength_empty))
        }
        else -> LazyColumn(Modifier.fillMaxSize().testTag(StrengthTags.LIST), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.items, key = { it.exerciseId }) { e ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(e.exerciseId) }.testTag(StrengthTags.row(e.exerciseId))) {
                    Column(Modifier.padding(16.dp)) {
                        Text(e.name, style = MaterialTheme.typography.titleMedium)
                        val last = e.lastPerformedAt?.let { StrengthFormat.date(it, s.zone) } ?: ""
                        Text(stringResource(R.string.strength_sessions, StrengthFormat.reps(e.sessions), last), style = MaterialTheme.typography.bodySmall)
                        Text(prLine(e.pr, s.units), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                LaunchedEffect(s.items.size, s.nextCursor) { if (s.nextCursor != null && s.moreError == null) onLoadMore() }
                when {
                    s.loadingMore -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    s.moreError != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(s.moreError.message()), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = onLoadMore, modifier = Modifier.testTag(StrengthTags.MORE_RETRY)) { Text(stringResource(R.string.xp_retry)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorBox(e: StrengthError, tag: String, retryTag: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp).testTag(tag), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(e.message()))
        if (e != StrengthError.NOT_FOUND) Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(retryTag)) { Text(stringResource(R.string.xp_retry)) }
    }
}

@Composable
private fun DetailBody(d: StrengthDetailState, units: UnitSystem, zone: ZoneId, onBucket: (SummaryBucket) -> Unit, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(StrengthTags.DETAIL)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag(StrengthTags.DETAIL_BACK)) { Text(stringResource(R.string.strength_back)) }
            Text(d.progress?.name ?: "", style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryBucket.entries.forEach { b ->
                FilterChip(d.bucket == b, { onBucket(b) }, label = { Text(stringResource(b.label())) }, modifier = Modifier.testTag(StrengthTags.bucket(b)))
            }
        }
        val p = d.progress
        when {
            d.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d.error != null -> ErrorBox(d.error, StrengthTags.DETAIL_ERROR, StrengthTags.DETAIL_RETRY, onRetry)
            p != null -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Card(Modifier.fillMaxWidth().testTag(StrengthTags.PR)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(stringResource(R.string.strength_records), style = MaterialTheme.typography.titleSmall)
                            prRows(p.pr, units, zone).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (p.pr.isEmpty) Text(stringResource(R.string.strength_no_records))
                        }
                    }
                }
                items(p.points.asReversed(), key = { it.period.toString() }) { pt ->
                    Column(Modifier.fillMaxWidth().testTag(StrengthTags.point(pt.period.toString()))) {
                        Text(ProgressFormat.date(pt.period), style = MaterialTheme.typography.labelLarge)
                        Text(if (pt.empty) stringResource(R.string.strength_no_sets) else pointLine(pt, units), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** Compact one-liner of the non-null records. */
internal fun prLine(pr: PersonalRecords, units: UnitSystem): String = listOfNotNull(
    pr.maxWeightKg?.let { "Top ${StrengthFormat.weight(it, units)}" },
    pr.best1RmKg?.let { "1RM ${StrengthFormat.weight(it, units)}" },
    pr.maxReps?.let { "${StrengthFormat.reps(it)} reps" },
    pr.maxDurationSec?.let { StrengthFormat.duration(it) },
    pr.maxDistanceM?.let { StrengthFormat.distance(it, units) },
).joinToString(" · ")

internal fun prRows(pr: PersonalRecords, units: UnitSystem, zone: ZoneId): List<String> {
    fun at(t: java.time.Instant?) = t?.let { " (${StrengthFormat.date(it, zone)})" } ?: ""
    return listOfNotNull(
        pr.maxWeightKg?.let { "Heaviest: ${StrengthFormat.weight(it, units)}${at(pr.maxWeightKgAchievedAt)}" },
        pr.best1RmKg?.let { "Best est. 1RM: ${StrengthFormat.weight(it, units)}${at(pr.best1RmKgAchievedAt)}" },
        pr.maxReps?.let { "Most reps: ${StrengthFormat.reps(it)}${at(pr.maxRepsAchievedAt)}" },
        pr.maxDurationSec?.let { "Longest set: ${StrengthFormat.duration(it)}${at(pr.maxDurationSecAchievedAt)}" },
        pr.maxDistanceM?.let { "Longest distance: ${StrengthFormat.distance(it, units)}${at(pr.maxDistanceMAchievedAt)}" },
    )
}

internal fun pointLine(p: ExerciseProgressPoint, units: UnitSystem): String = listOfNotNull(
    "${p.sets} sets",
    p.volumeKg?.let { "Volume ${StrengthFormat.volume(it, units)}" },
    p.totalReps?.let { "${StrengthFormat.reps(it)} reps" },
    p.topSetWeightKg?.let { "Top ${StrengthFormat.weight(it, units)}" },
    p.best1RmKg?.let { "1RM ${StrengthFormat.weight(it, units)}" },
    p.totalDurationSec?.let { "Time ${StrengthFormat.duration(it)}" },
    p.maxSetDurationSec?.let { "Longest ${StrengthFormat.duration(it)}" },
    p.totalDistanceM?.let { "Distance ${StrengthFormat.distance(it, units)}" },
    p.maxSetDistanceM?.let { "Best set ${StrengthFormat.distance(it, units)}" },
).joinToString(" · ")

private fun SummaryBucket.label() = when (this) {
    SummaryBucket.DAY -> R.string.strength_bucket_day
    SummaryBucket.WEEK -> R.string.strength_bucket_week
    SummaryBucket.MONTH -> R.string.strength_bucket_month
}

private fun StrengthError.message() = when (this) {
    StrengthError.OFFLINE -> R.string.strength_offline
    StrengthError.FAILED -> R.string.strength_failed
    StrengthError.NOT_FOUND -> R.string.strength_not_found
}
