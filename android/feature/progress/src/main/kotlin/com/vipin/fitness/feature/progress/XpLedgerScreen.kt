package com.vipin.fitness.feature.progress

import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.data.xp.XpEntry
import com.vipin.fitness.core.data.xp.XpSourceType
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Test tags; values agreed with QA's contract (#92). */
object XpLedgerTags {
    /** On the Home XP card (slot_xp): opens the ledger. */
    const val OPEN = "xp_open_ledger"
    const val SCREEN = "xp_ledger_screen"
    const val LIST = "xp_ledger_list"
    const val EMPTY = "xp_ledger_empty"
    const val ERROR = "xp_ledger_error"
    const val RETRY = "xp_ledger_retry"
    const val BALANCE = "xp_ledger_balance"
    const val MORE_RETRY = "xp_ledger_more_retry"
    fun row(id: String) = "xp_row_$id"
    fun amount(id: String) = "xp_amount_$id"
}

/** en-IN, always signed: "+1,250" or "−20" (true minus sign). */
internal object XpFormat {
    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", ProgressFormat.LOCALE)

    fun amount(v: Int): String {
        val n = NumberFormat.getIntegerInstance(ProgressFormat.LOCALE).format(kotlin.math.abs(v.toLong()))
        return if (v < 0) "\u2212$n" else "+$n"
    }

    fun time(e: XpEntry, zone: ZoneId): String? = e.createdAt?.atZone(zone)?.let(dateTime::format)
}

@StringRes
internal fun XpSourceType.label(): Int = when (this) {
    XpSourceType.WORKOUT_SESSION -> R.string.xp_source_workout
    XpSourceType.ADJUSTMENT -> R.string.xp_source_adjustment
    XpSourceType.BADGE -> R.string.xp_source_badge
    XpSourceType.CHALLENGE -> R.string.xp_source_challenge
    XpSourceType.SESSION_CORRECTION -> R.string.xp_source_session_correction
    XpSourceType.UNKNOWN -> R.string.xp_source_other
}

/** Read-only XP history, opened from the Home XP card. */
@Composable
fun XpLedgerRoute(viewModel: XpLedgerViewModel = hiltViewModel()) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    XpLedgerContent(s, onRetry = viewModel::refresh, onLoadMore = viewModel::loadMore)
}

@Composable
fun XpLedgerContent(s: XpLedgerState, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(XpLedgerTags.SCREEN)) {
        Text(stringResource(R.string.xp_ledger_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp))
        s.balance?.let { b ->
            Text(
                stringResource(R.string.xp_ledger_balance, b.level, NumberFormat.getIntegerInstance(ProgressFormat.LOCALE).format(b.total.toLong())),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp).testTag(XpLedgerTags.BALANCE),
            )
        }
        Box(Modifier.weight(1f)) { LedgerBody(s, onRetry, onLoadMore) }
    }
}

@Composable
private fun LedgerBody(s: XpLedgerState, onRetry: () -> Unit, onLoadMore: () -> Unit) {
    when {
        s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.error != null -> Column(
            Modifier.fillMaxSize().padding(24.dp).testTag(XpLedgerTags.ERROR),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(s.error.message()))
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp).testTag(XpLedgerTags.RETRY)) { Text(stringResource(R.string.xp_retry)) }
        }
        s.empty -> Box(Modifier.fillMaxSize().padding(24.dp).testTag(XpLedgerTags.EMPTY), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.xp_ledger_empty))
        }
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag(XpLedgerTags.LIST),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(s.items, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().testTag(XpLedgerTags.row(e.id))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(e.sourceType.label()), style = MaterialTheme.typography.titleSmall)
                            if (e.reason.isNotBlank()) Text(e.reason, style = MaterialTheme.typography.bodyMedium)
                            XpFormat.time(e, s.zone)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(
                            stringResource(R.string.xp_amount, XpFormat.amount(e.amount)),
                            modifier = Modifier.testTag(XpLedgerTags.amount(e.id)),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (e.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item {
                when {
                    s.loadingMore -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(16.dp)) }
                    s.moreError != null -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(s.moreError.message()), color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = onLoadMore, modifier = Modifier.testTag(XpLedgerTags.MORE_RETRY)) { Text(stringResource(R.string.xp_retry)) }
                    }
                    s.nextCursor != null -> LaunchedEffect(s.nextCursor) { onLoadMore() }
                }
            }
        }
    }
}

private fun XpLedgerError.message() = when (this) {
    XpLedgerError.OFFLINE -> R.string.xp_ledger_offline
    XpLedgerError.FAILED -> R.string.xp_ledger_failed
}
