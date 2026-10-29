package com.vipin.fitness.feature.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.BodyMeasurement
import com.vipin.fitness.core.data.MeasureDimension
import com.vipin.fitness.core.data.MeasurementKind

/** Progress tab: charts from /progress/summary and the body-measurement log. */
@Composable
fun ProgressScreen(modifier: Modifier = Modifier) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, Modifier.testTag("progress_tab_charts"), text = { Text(stringResource(R.string.progress_charts)) })
            Tab(tab == 1, { tab = 1 }, Modifier.testTag("progress_tab_measurements"), text = { Text(stringResource(R.string.progress_measurements)) })
        }
        if (tab == 0) ChartsScreen(hiltViewModel()) else MeasurementsScreen(hiltViewModel())
    }
}

// ---------------- Charts ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartsScreen(vm: ChartsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChartRange.entries.forEach { r ->
                FilterChip(selected = r == s.range, onClick = { vm.setRange(r) }, label = { Text(stringResource(R.string.range_days, r.days.toInt())) })
            }
        }
        when {
            s.loading && s.summary == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.error != null -> {
                Text(s.error!!.resolve(), color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::load) { Text(stringResource(R.string.progress_retry)) }
            }
            else -> {
                s.summary?.let { sum ->
                    Text(stringResource(R.string.range_dates, ProgressFormat.shortDate(sum.from), ProgressFormat.shortDate(sum.to)), style = MaterialTheme.typography.bodySmall)
                }
                ChartMetric.entries.forEach { m -> ChartCard(m, s.series(m), s.units) }
            }
        }
    }
}

@Composable
private fun ChartCard(metric: ChartMetric, values: List<Double?>, units: UnitSystem) {
    val title = when (metric) {
        ChartMetric.WEIGHT -> stringResource(R.string.chart_weight, ProgressFormat.unitLabel(MeasureDimension.MASS, units))
        ChartMetric.BODY_FAT -> stringResource(R.string.chart_body_fat)
        ChartMetric.WAIST -> stringResource(R.string.chart_waist, ProgressFormat.unitLabel(MeasureDimension.LENGTH, units))
        ChartMetric.WATER -> stringResource(R.string.chart_water, ProgressFormat.waterUnitLabel(units))
        ChartMetric.ENERGY -> stringResource(R.string.chart_energy)
        ChartMetric.ACTIVE_MINUTES -> stringResource(R.string.chart_active_minutes)
        ChartMetric.WORKOUTS -> stringResource(R.string.chart_workouts)
    }
    val present = values.filterNotNull()
    Card(Modifier.fillMaxWidth().testTag("chart_${metric.name}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (present.isEmpty()) {
                Text(stringResource(R.string.chart_no_data), style = MaterialTheme.typography.bodyMedium)
            } else {
                val min = present.min()
                val max = present.max()
                Text(
                    stringResource(R.string.chart_min_max, ProgressFormat.chartValue(metric, min, units), ProgressFormat.chartValue(metric, max, units), present.size, values.size),
                    style = MaterialTheme.typography.bodySmall,
                )
                LineChart(values, Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = title })
            }
        }
    }
}

/** Line per run of days with data; a gap (null) breaks the line. Lone points are dots. */
@Composable
private fun LineChart(values: List<Double?>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val segments = chartSegments(values)
    val present = values.filterNotNull()
    val min = present.minOrNull() ?: 0.0
    val max = present.maxOrNull() ?: 0.0
    Canvas(modifier) {
        val n = (values.size - 1).coerceAtLeast(1)
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        fun pt(i: Int, v: Double) = Offset(
            x = size.width * i / n,
            y = if (max == min) size.height / 2 else (size.height * (1 - (v - min) / span)).toFloat(),
        )
        segments.forEach { run ->
            if (run.size == 1) {
                drawCircle(color, radius = 4.dp.toPx(), center = pt(run[0].first, run[0].second))
            } else {
                run.zipWithNext { a, b -> drawLine(color, pt(a.first, a.second), pt(b.first, b.second), strokeWidth = 2.dp.toPx()) }
                run.forEach { (i, v) -> drawCircle(color, radius = 2.dp.toPx(), center = pt(i, v), style = Stroke(1.dp.toPx())) }
            }
        }
    }
}

// ---------------- Measurements ----------------

@Composable
private fun MeasurementsScreen(vm: MeasurementsViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.progress_measurements), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Button(onClick = vm::startAdd, enabled = !s.saving, modifier = Modifier.testTag("add_measurement")) { Text(stringResource(R.string.measurement_add)) }
        }
        s.error?.let { Text(it.resolve(), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
        when {
            s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            s.loadError != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.loadError!!.resolve(), color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::load) { Text(stringResource(R.string.progress_retry)) }
            }
            s.items.isEmpty() -> Text(stringResource(R.string.measurements_empty), Modifier.padding(16.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("measurement_list"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(s.items, key = { it.id }) { m -> MeasurementRow(m, s.units, s.saving) { vm.startEdit(m) } }
                if (s.nextCursor != null) item {
                    TextButton(onClick = vm::loadMore, enabled = !s.loadingMore) { Text(stringResource(R.string.measurements_load_more)) }
                }
            }
        }
    }
    s.form?.let { MeasurementDialog(it, s, vm) }
}

@Composable
private fun MeasurementRow(m: BodyMeasurement, units: UnitSystem, saving: Boolean, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(enabled = !saving, onClick = onEdit).testTag("measurement_${m.id}")) {
        Column(Modifier.padding(12.dp)) {
            Text(ProgressFormat.date(m.date), style = MaterialTheme.typography.titleSmall)
            Text(
                MeasurementKind.entries.mapNotNull { k -> m.values[k]?.let { "${kindLabel(k)} ${ProgressFormat.measurement(k, it, units)}" } }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun kindLabel(k: MeasurementKind): String = stringResource(
    when (k) {
        MeasurementKind.WEIGHT -> R.string.kind_weight
        MeasurementKind.BODY_FAT -> R.string.kind_body_fat
        MeasurementKind.WAIST -> R.string.kind_waist
        MeasurementKind.CHEST -> R.string.kind_chest
        MeasurementKind.HIP -> R.string.kind_hip
        MeasurementKind.ARM -> R.string.kind_arm
        MeasurementKind.THIGH -> R.string.kind_thigh
    },
)

@Composable
private fun MeasurementDialog(form: MeasurementForm, s: MeasurementsState, vm: MeasurementsViewModel) {
    AlertDialog(
        onDismissRequest = vm::cancel,
        title = { Text(stringResource(if (form.isEdit) R.string.measurement_edit else R.string.measurement_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = vm::previousDate, enabled = !s.saving) { Text("‹") }
                    Text(
                        if (form.date == s.today) stringResource(R.string.progress_today) else ProgressFormat.date(form.date),
                        Modifier.weight(1f),
                    )
                    TextButton(onClick = vm::nextDate, enabled = !s.saving && form.date < s.today) { Text("›") }
                }
                MeasurementKind.entries.forEach { k ->
                    OutlinedTextField(
                        value = form.texts[k].orEmpty(),
                        onValueChange = { v -> vm.onText(k, v.filter { it.isDigit() || it == '.' || it == ',' }) },
                        label = { Text("${kindLabel(k)} (${ProgressFormat.unitLabel(k, form.units)})") },
                        singleLine = true,
                        isError = k.wire in form.errors,
                        supportingText = if (k.wire in form.errors) {
                            { Text(stringResource(R.string.measurement_invalid, ProgressFormat.number(toDisplay(k.dimension, k.min, form.units)), ProgressFormat.number(toDisplay(k.dimension, k.max, form.units)))) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("field_${k.wire}"),
                    )
                }
                if (MeasurementsViewModel.FIELD_NONE in form.errors) Text(stringResource(R.string.measurement_none), color = MaterialTheme.colorScheme.error)
                if (MeasurementsViewModel.FIELD_DATE in form.errors) Text(stringResource(R.string.measurement_future), color = MaterialTheme.colorScheme.error)
                s.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
                form.original?.let { m ->
                    TextButton(onClick = { vm.delete(m.id) }, enabled = !s.saving) {
                        Text(stringResource(R.string.measurement_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = vm::save, enabled = !s.saving, modifier = Modifier.testTag("save_measurement")) { Text(stringResource(R.string.progress_save)) } },
        dismissButton = { TextButton(onClick = vm::cancel, enabled = !s.saving) { Text(stringResource(R.string.progress_cancel)) } },
    )
}
