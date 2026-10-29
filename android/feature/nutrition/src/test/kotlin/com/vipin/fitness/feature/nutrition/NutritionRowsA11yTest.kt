package com.vipin.fitness.feature.nutrition

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.DailyNutrition
import com.vipin.fitness.core.data.Food
import com.vipin.fitness.core.data.FoodLog
import com.vipin.fitness.core.data.MealType
import com.vipin.fitness.core.data.Nutrients
import com.vipin.fitness.core.data.ServingUnit
import com.vipin.fitness.core.data.WaterLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Tappable rows: ≥48dp tall, a Button role and a spoken action label. */
@RunWith(AndroidJUnit4::class)
class NutritionRowsA11yTest {
    @get:Rule val compose = createComposeRule()
    private val day = LocalDate.of(2026, 10, 29)

    private fun check(tag: String, label: String) {
        val n = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        with(compose.density) { assertTrue("$tag ${n.boundsInRoot.height.toDp()}", n.boundsInRoot.height.toDp().value >= 48f) }
        assertEquals(Role.Button, n.config.getOrNull(SemanticsProperties.Role))
        assertEquals(label, n.config[SemanticsActions.OnClick].label)
    }

    @Test fun waterAndFoodLogRowsAreLabelledButtonsOfAtLeast48dp() {
        val edits = mutableListOf<String>()
        val daily = DailyNutrition(day, 1, Nutrients(), emptyMap(), 250, null, null)
        val log = FoodLog("f1", "food", "Dal", day, MealType.LUNCH, 1.0, Nutrients(energyKcal = 120.0))
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                MealCard(MealType.LUNCH, listOf(log), 120.0, saving = false, onAdd = {}, onEdit = { edits += it.id })
                WaterCard(daily, listOf(WaterLog("w1", day, 250)), saving = false, onAdd = {}, onEdit = { edits += it.id })
            }
        }
        check("log_f1", "Edit entry")
        check("water_log_w1", "Edit water")
        compose.onNodeWithTag("water_log_w1").performClick()
        compose.onNodeWithTag("log_f1").performClick()
        assertEquals(listOf("w1", "f1"), edits)
    }

    @Test fun foodSearchRowIsALabelledButton() {
        var picked = 0
        compose.setContent { FoodRow(Food("x", "Rice", servingSize = 100.0, servingUnit = ServingUnit.entries.first(), nutrients = Nutrients())) { picked++ } }
        check("food_x", "Choose food")
        compose.onNodeWithTag("food_x").performClick()
        assertEquals(1, picked)
    }
}
