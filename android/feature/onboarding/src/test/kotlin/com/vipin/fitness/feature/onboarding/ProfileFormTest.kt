package com.vipin.fitness.feature.onboarding

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.data.Field
import com.vipin.fitness.core.data.Profile
import com.vipin.fitness.core.data.ProfileChanges
import com.vipin.fitness.core.data.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFormTest {
    private val year = 2026
    private val all = ProfileField.entries.toSet()
    private val profile = Profile(displayName = "Vipin", birthYear = 1990, sex = Sex.MALE, heightCm = 175.0, weightKg = 72.5)

    @Test fun untouchedFormSendsNothing() {
        val f = ProfileForm.from(profile)
        assertEquals(ProfileChanges(), f.changes(all, f, year))
    }

    @Test fun emptiedFieldClearsEditedFieldSetsOthersUnchanged() {
        val initial = ProfileForm.from(profile)
        val edited = initial.copy(birthYear = "", weight = "74")
        assertEquals(
            ProfileChanges(birthYear = Field.Clear, weightKg = Field.Set(74.0)),
            edited.changes(all, initial, year),
        )
    }

    @Test fun switchingUnitsAloneOnlyChangesUnitSystem() {
        val initial = ProfileForm.from(profile)
        val imperial = initial.withUnits(UnitSystem.IMPERIAL)
        assertEquals("5", imperial.heightFt)
        assertEquals("9", imperial.heightIn)
        assertEquals("159.8", imperial.weight)
        assertEquals(ProfileChanges(unitSystem = UnitSystem.IMPERIAL), imperial.changes(all, initial, year))
    }

    @Test fun imperialInputConvertsToMetric() {
        val initial = ProfileForm(units = UnitSystem.IMPERIAL)
        val f = initial.copy(heightFt = "6", heightIn = "0", weight = "180")
        val c = f.changes(all, initial, year)
        assertEquals(Field.Set(182.9), c.heightCm)
        assertEquals(Field.Set(81.65), c.weightKg)
    }

    @Test fun validationRanges() {
        val bad = ProfileForm(birthYear = "2030", heightCm = "30", weight = "abc", calories = "100", water = "50")
        assertEquals(
            mapOf(
                ProfileField.BIRTH_YEAR to FieldError.BIRTH_YEAR,
                ProfileField.HEIGHT to FieldError.HEIGHT,
                ProfileField.WEIGHT to FieldError.WEIGHT,
                ProfileField.CALORIES to FieldError.CALORIES,
                ProfileField.WATER to FieldError.WATER,
            ),
            bad.errors(all, year),
        )
        assertTrue(ProfileForm(heightCm = "175,5", weight = "").errors(all, year).isEmpty()) // comma decimal, empty ok
    }

    @Test fun onlyRequestedFieldsAreConsidered() {
        val initial = ProfileForm()
        val f = initial.copy(displayName = "A", weight = "70")
        assertEquals(ProfileChanges(displayName = Field.Set("A")), f.changes(setOf(ProfileField.NAME), initial, year))
    }
}
