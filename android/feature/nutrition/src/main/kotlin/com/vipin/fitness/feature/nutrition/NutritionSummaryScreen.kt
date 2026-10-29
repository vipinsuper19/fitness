package com.vipin.fitness.feature.nutrition

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.NutritionSummaryPoint
import com.vipin.fitness.core.data.SummaryBucket
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object SummaryTags {
    const val LOADING = "summary_loading"
    const val ERROR = "summary_error"
    const val RETRY = "summary_retry"
    const val BACK = "summary_back"
    fun bucket(b: SummaryBucket) = "summary_bucket_${b.wire}"
    fun point(d: LocalDate) = "summary_point_$d"
}

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", NutritionFormat.LOCALE)
private val shortFmt = DateTimeFormatter.ofPattern("d MMM yyyy", NutritionFormat.LOCALE)

@Composable
fun NutritionSummaryRoute(onBack: () -> Unit, modifier: Modifier = Modifier, vm: NutritionSummaryViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    NutritionSummaryContent(s, onSelect = vm::select, onRetry = vm::load, onBack = onBack, modifier = modifier)
}

@Composable
fun NutritionSummaryContent(
    s: SummaryState,
    onSelect: (SummaryBucket) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.summary_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack, modifier = Modifier.testTag(SummaryTags.BACK)) { Text(stringResource(R.string.summary_back)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryBucket.entries.forEach { b ->
                FilterChip(
                    selected = s.bucket == b,
                    onClick = { onSelect(b) },
                    label = { Text(stringResource(b.label())) },
                    modifier = Modifier.testTag(SummaryTags.bucket(b)),
                )
            }
        }
        when {
            s.loading -> Box(Modifier.fillMaxSize().testTag(SummaryTags.LOADING), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.error != null -> Column(Modifier.fillMaxSize().padding(24.dp).testTag(SummaryTags.ERROR), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (s.error == SummaryError.OFFLINE) R.string.summary_offline else R.string.summary_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetry, modifier = Modifier.testTag(SummaryTags.RETRY)) { Text(stringResource(R.string.nutrition_retry)) }
            }
            else -> LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Newest period first; empty periods are shown too.
                items(s.summary?.points.orEmpty().asReversed(), key = { it.period.toString() }) { p -> PointCard(p, s.bucket, s.units) }
            }
        }
    }
}

private fun SummaryBucket.label() = when (this) {
    SummaryBucket.DAY -> R.string.summary_day
    SummaryBucket.WEEK -> R.string.summary_week
    SummaryBucket.MONTH -> R.string.summary_month
}

@Composable
private fun periodLabel(p: NutritionSummaryPoint, bucket: SummaryBucket): String = when (bucket) {
    SummaryBucket.DAY -> NutritionFormat.date(p.period)
    SummaryBucket.WEEK -> stringResource(R.string.summary_week_of, p.period.format(shortFmt))
    SummaryBucket.MONTH -> p.period.format(monthFmt)
}

@Composable
private fun PointCard(p: NutritionSummaryPoint, bucket: SummaryBucket, units: UnitSystem) {
    Card(Modifier.fillMaxWidth().testTag(SummaryTags.point(p.period))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(periodLabel(p, bucket), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.summary_days_logged, p.daysLogged, p.daysInPeriod))
            Text(stringResource(R.string.summary_water_days_logged, p.waterDaysLogged, p.daysInPeriod))
            if (p.empty) {
                Text(stringResource(R.string.summary_nothing_logged), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            p.avgPerLoggedDay?.let { n ->
                Text(stringResource(R.string.summary_avg_per_day), style = MaterialTheme.typography.labelLarge)
                Text("${NutritionFormat.kcal(n.energyKcal)} · ${NutritionFormat.macros(n)}")
            }
            p.avgWaterMlPerLoggedDay?.let { Text(stringResource(R.string.summary_avg_water, formatWater(it, units))) }
            p.adherence?.let { a ->
                val parts = listOfNotNull(
                    a.kcalPct?.let { stringResource(R.string.summary_kcal_pct, formatPct(it)) },
                    a.proteinPct?.let { stringResource(R.string.summary_protein_pct, formatPct(it)) },
                    a.carbsPct?.let { stringResource(R.string.summary_carbs_pct, formatPct(it)) },
                    a.fatPct?.let { stringResource(R.string.summary_fat_pct, formatPct(it)) },
                    a.waterPct?.let { stringResource(R.string.summary_water_pct, formatPct(it)) },
                )
                if (parts.isNotEmpty()) {
                    Text(stringResource(R.string.summary_adherence), style = MaterialTheme.typography.labelLarge)
                    Text(parts.joinToString(" · "))
                }
            }
        }
    }
}
