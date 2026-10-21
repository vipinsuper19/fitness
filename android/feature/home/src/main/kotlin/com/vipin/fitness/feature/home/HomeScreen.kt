package com.vipin.fitness.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vipin.fitness.core.common.UnitConversion
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.XpBalance

/**
 * Home dashboard. Each card is its own slot composable, so other features (e.g. workout
 * tracking) can add or replace one via [extraSlots] without touching the others.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    planDayContent: @Composable (PlanDay) -> Unit = { DefaultPlanDayContent(it) },
    extraSlots: LazyListScope.(HomeState) -> Unit = {},
) {
    val vm: HomeViewModel = hiltViewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    HomeContent(s, onRetry = vm::refresh, modifier = modifier, planDayContent = planDayContent, extraSlots = extraSlots)
}

@Composable
fun HomeContent(
    s: HomeState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    planDayContent: @Composable (PlanDay) -> Unit = { DefaultPlanDayContent(it) },
    extraSlots: LazyListScope.(HomeState) -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize().testTag("home_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "plan") { PlanDaySlot(s.plan, onRetry, planDayContent) }
        item(key = "nutrition") { NutritionSlot(s.nutrition, s.units, onRetry) }
        item(key = "streak") { StreakSlot(s.streak, onRetry) }
        item(key = "xp") { XpSlot(s.xp, onRetry) }
        extraSlots(s)
    }
}

/** Card frame shared by every slot: title, then loading / error with retry / content. */
@Composable
fun <T> SlotCard(title: String, slot: Slot<T>, onRetry: () -> Unit, tag: String, content: @Composable (T) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (slot) {
                Slot.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is Slot.Failed -> {
                    Text(slot.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.home_retry)) }
                }
                is Slot.Ready -> content(slot.value)
            }
        }
    }
}

/** Today's plan card. [content] renders a loaded plan day; T13 can pass start/resume content here. */
@Composable
fun PlanDaySlot(
    slot: Slot<PlanDay>,
    onRetry: () -> Unit,
    content: @Composable (PlanDay) -> Unit = { DefaultPlanDayContent(it) },
) = SlotCard(stringResource(R.string.home_plan_title), slot, onRetry, "slot_plan", content)

/** Plan name and today's workout, or the rest-day / no-plan empty state. */
@Composable
fun DefaultPlanDayContent(d: PlanDay) {
    when {
        d.plan == null -> Text(stringResource(R.string.home_plan_none))
        d.workout == null -> {
            Text(d.plan.name, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.home_plan_rest))
        }
        else -> {
            Text(d.plan.name, style = MaterialTheme.typography.bodySmall)
            Text(d.workout.name, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.home_plan_exercises, d.workout.exercises.size))
        }
    }
}

@Composable
fun NutritionSlot(slot: Slot<DailyNutrition>, units: UnitSystem, onRetry: () -> Unit) =
    SlotCard(stringResource(R.string.home_nutrition_title), slot, onRetry, "slot_nutrition") { d ->
        if (d.entryCount == 0 && d.waterMl == 0) {
            Text(stringResource(R.string.home_nutrition_empty))
        } else {
            val kcal = HomeFormat.number(d.totals.energyKcal, 0)
            Text(
                d.calorieTargetKcal?.let { stringResource(R.string.home_kcal_of_target, kcal, HomeFormat.number(it.toDouble(), 0)) }
                    ?: stringResource(R.string.home_kcal, kcal),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                stringResource(
                    R.string.home_macros,
                    HomeFormat.number(d.totals.proteinG), HomeFormat.number(d.totals.carbsG), HomeFormat.number(d.totals.fatG),
                ),
            )
            val water = HomeFormat.water(d.waterMl, units)
            Text(d.waterTargetMl?.let { stringResource(R.string.home_water_of_target, water, HomeFormat.water(it, units)) } ?: stringResource(R.string.home_water, water))
        }
    }

@Composable
fun StreakSlot(slot: Slot<Streak>, onRetry: () -> Unit) =
    SlotCard(stringResource(R.string.home_streak_title), slot, onRetry, "slot_streak") { st ->
        if (st.days == 0) {
            Text(stringResource(R.string.home_streak_empty))
        } else {
            Text(stringResource(R.string.home_streak_days, st.days), style = MaterialTheme.typography.headlineSmall)
            if (st.workoutsToday == 0) Text(stringResource(R.string.home_streak_keep_alive))
        }
    }

@Composable
fun XpSlot(slot: Slot<XpBalance>, onRetry: () -> Unit) =
    SlotCard(stringResource(R.string.home_xp_title), slot, onRetry, "slot_xp") { xp ->
        if (xp.total == 0) {
            Text(stringResource(R.string.home_xp_empty))
        } else {
            Text(stringResource(R.string.home_xp_level, xp.level, HomeFormat.number(xp.total.toDouble(), 0)), style = MaterialTheme.typography.headlineSmall)
            LinearProgressIndicator(
                progress = { (xp.xpIntoLevel.toFloat() / xp.xpForNextLevel).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.home_xp_next, xp.xpIntoLevel, xp.xpForNextLevel), style = MaterialTheme.typography.bodySmall)
        }
    }

/** en-IN, half-up (same rules as nutrition). Water is ml, or US fl oz for imperial. */
internal object HomeFormat {
    private val LOCALE = java.util.Locale.forLanguageTag("en-IN")

    fun number(v: Double, maxDecimals: Int = 1): String =
        java.text.NumberFormat.getNumberInstance(LOCALE).apply {
            maximumFractionDigits = maxDecimals
            minimumFractionDigits = 0
            roundingMode = java.math.RoundingMode.HALF_UP
        }.format(java.math.BigDecimal.valueOf(v))

    fun water(ml: Int, units: UnitSystem): String =
        if (units == UnitSystem.METRIC) "${number(ml.toDouble(), 0)} ml" else "${number(UnitConversion.mlToFlOz(ml.toDouble()))} fl oz"
}
