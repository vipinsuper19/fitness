package com.vipin.fitness.feature.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vipin.fitness.core.data.MealType

internal fun MealType.label() = when (this) {
    MealType.BREAKFAST -> R.string.meal_breakfast
    MealType.LUNCH -> R.string.meal_lunch
    MealType.DINNER -> R.string.meal_dinner
    MealType.SNACK -> R.string.meal_snack
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MealPicker(selected: MealType, onSelect: (MealType) -> Unit, enabled: Boolean = true) {
    Text(stringResource(R.string.field_meal), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MealType.entries.forEach { m ->
            FilterChip(selected = m == selected, onClick = { onSelect(m) }, enabled = enabled, label = { Text(stringResource(m.label())) })
        }
    }
}

@Composable
internal fun NumberField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    decimal: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onValue(v.filter { it.isDigit() || (decimal && it == '.') || it == ',' }.take(9)) },
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
internal fun MacroFields(form: MacroForm, onChange: (MacroForm) -> Unit, error: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NumberField(form.kcal, { onChange(form.copy(kcal = it)) }, stringResource(R.string.field_kcal), Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(form.protein, { onChange(form.copy(protein = it)) }, stringResource(R.string.field_protein), Modifier.weight(1f))
            NumberField(form.carbs, { onChange(form.copy(carbs = it)) }, stringResource(R.string.field_carbs), Modifier.weight(1f))
            NumberField(form.fat, { onChange(form.copy(fat = it)) }, stringResource(R.string.field_fat), Modifier.weight(1f))
        }
        if (error) Text(stringResource(R.string.macros_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
