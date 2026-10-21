package com.vipin.fitness.feature.onboarding

import androidx.annotation.StringRes
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.ActivityLevel
import com.vipin.fitness.core.data.DietPreference
import com.vipin.fitness.core.data.ExperienceLevel
import com.vipin.fitness.core.data.Sex

@StringRes internal fun FieldError.label() = when (this) {
    FieldError.BIRTH_YEAR -> R.string.err_birth_year
    FieldError.HEIGHT -> R.string.err_height
    FieldError.WEIGHT -> R.string.err_weight
    FieldError.CALORIES -> R.string.err_calories
    FieldError.WATER -> R.string.err_water
}

@StringRes private fun Sex.label() = when (this) {
    Sex.MALE -> R.string.sex_male
    Sex.FEMALE -> R.string.sex_female
    Sex.OTHER -> R.string.sex_other
    Sex.PREFER_NOT_TO_SAY -> R.string.sex_prefer_not
}

@StringRes private fun ActivityLevel.label() = when (this) {
    ActivityLevel.SEDENTARY -> R.string.activity_sedentary
    ActivityLevel.LIGHT -> R.string.activity_light
    ActivityLevel.MODERATE -> R.string.activity_moderate
    ActivityLevel.ACTIVE -> R.string.activity_active
    ActivityLevel.VERY_ACTIVE -> R.string.activity_very_active
}

@StringRes private fun ExperienceLevel.label() = when (this) {
    ExperienceLevel.BEGINNER -> R.string.exp_beginner
    ExperienceLevel.INTERMEDIATE -> R.string.exp_intermediate
    ExperienceLevel.ADVANCED -> R.string.exp_advanced
}

@StringRes private fun DietPreference.label() = when (this) {
    DietPreference.VEGETARIAN -> R.string.diet_vegetarian
    DietPreference.EGGETARIAN -> R.string.diet_eggetarian
    DietPreference.NON_VEGETARIAN -> R.string.diet_non_vegetarian
    DietPreference.VEGAN -> R.string.diet_vegan
    DietPreference.JAIN -> R.string.diet_jain
}

@StringRes private fun UnitSystem.label() = if (this == UnitSystem.METRIC) R.string.units_metric else R.string.units_imperial

/** Renders the inputs for [fields] in a fixed order. */
@Composable
internal fun ProfileFields(
    fields: Set<ProfileField>,
    form: ProfileForm,
    errors: Map<ProfileField, FieldError>,
    onForm: ((ProfileForm) -> ProfileForm) -> Unit,
    onUnits: (UnitSystem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (ProfileField.NAME in fields) {
            TextInput(R.string.field_name, form.displayName, null, KeyboardType.Text) { v -> onForm { it.copy(displayName = v.take(60)) } }
        }
        if (ProfileField.BIRTH_YEAR in fields) {
            TextInput(R.string.field_birth_year, form.birthYear, errors[ProfileField.BIRTH_YEAR], KeyboardType.Number) { v ->
                onForm { it.copy(birthYear = v.filter(Char::isDigit).take(4)) }
            }
        }
        if (ProfileField.SEX in fields) {
            Choices(R.string.field_sex, Sex.entries, form.sex, { it.label() }) { v -> onForm { it.copy(sex = v) } }
        }
        if (ProfileField.UNITS in fields) {
            Choices(R.string.field_units, UnitSystem.entries, form.units, { it.label() }, allowNone = false) { v -> v?.let(onUnits) }
        }
        if (ProfileField.HEIGHT in fields) {
            if (form.units == UnitSystem.METRIC) {
                TextInput(R.string.field_height_cm, form.heightCm, errors[ProfileField.HEIGHT], KeyboardType.Decimal) { v ->
                    onForm { it.copy(heightCm = v) }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextInput(R.string.field_height_ft, form.heightFt, errors[ProfileField.HEIGHT], KeyboardType.Number, Modifier.weight(1f)) { v ->
                        onForm { it.copy(heightFt = v.filter(Char::isDigit).take(1)) }
                    }
                    TextInput(R.string.field_height_in, form.heightIn, null, KeyboardType.Decimal, Modifier.weight(1f)) { v ->
                        onForm { it.copy(heightIn = v.take(4)) }
                    }
                }
            }
        }
        if (ProfileField.WEIGHT in fields) {
            val label = if (form.units == UnitSystem.METRIC) R.string.field_weight_kg else R.string.field_weight_lb
            TextInput(label, form.weight, errors[ProfileField.WEIGHT], KeyboardType.Decimal) { v -> onForm { it.copy(weight = v.take(6)) } }
        }
        if (ProfileField.ACTIVITY in fields) {
            Choices(R.string.field_activity, ActivityLevel.entries, form.activity, { it.label() }) { v -> onForm { it.copy(activity = v) } }
        }
        if (ProfileField.EXPERIENCE in fields) {
            Choices(R.string.field_experience, ExperienceLevel.entries, form.experience, { it.label() }) { v -> onForm { it.copy(experience = v) } }
        }
        if (ProfileField.DIET in fields) {
            Choices(R.string.field_diet, DietPreference.entries, form.diet, { it.label() }) { v -> onForm { it.copy(diet = v) } }
        }
        if (ProfileField.CALORIES in fields) {
            TextInput(R.string.field_calories, form.calories, errors[ProfileField.CALORIES], KeyboardType.Number) { v ->
                onForm { it.copy(calories = v.filter(Char::isDigit).take(4)) }
            }
        }
        if (ProfileField.WATER in fields) {
            TextInput(R.string.field_water, form.water, errors[ProfileField.WATER], KeyboardType.Number) { v ->
                onForm { it.copy(water = v.filter(Char::isDigit).take(5)) }
            }
        }
    }
}

@Composable
private fun TextInput(
    @StringRes label: Int,
    value: String,
    error: FieldError?,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it.label())) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Single choice; tapping the selected chip again clears it (unless [allowNone] is false). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    @StringRes title: Int,
    options: List<T>,
    selected: T?,
    label: (T) -> Int,
    allowNone: Boolean = true,
    onSelect: (T?) -> Unit,
) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(if (o == selected && allowNone) null else o) },
                    label = { Text(stringResource(label(o))) },
                )
            }
        }
    }
}
