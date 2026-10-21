package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.model.ProfileDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileMappingTest {
    @Test fun unchangedIsOmittedClearIsNullSetIsValue() {
        val body = ProfileChanges(
            displayName = Field.Set("Vipin"),
            weightKg = Field.Clear,
            sex = Field.Set(Sex.PREFER_NOT_TO_SAY),
            unitSystem = UnitSystem.IMPERIAL,
        ).toJson()
        assertEquals(
            """{"displayName":"Vipin","sex":"prefer_not_to_say","weightKg":null,"unitSystem":"imperial"}""",
            body.toString(),
        )
    }

    @Test fun emptyChangesSendEmptyObject() {
        assertEquals("{}", ProfileChanges().toJson().toString())
    }

    @Test fun fieldOfMapsNullToClear() {
        assertEquals(Field.Clear, Field.of<Int>(null))
        assertEquals(Field.Set(3), Field.of(3))
    }

    @Test fun dtoToDomainToleratesUnknownEnums() {
        val dto = fitnessJson().decodeFromString(
            ProfileDto.serializer(),
            """{"userId":"u","sex":"martian","activityLevel":"active","unitSystem":"imperial",
               "timezone":"Asia/Kolkata","locale":"en-IN","currency":"INR","onboardingCompleted":true,
               "updatedAt":"2026-10-21T08:00:00Z","newField":1}""",
        )
        val p = dto.toDomain()
        assertNull(p.sex)
        assertEquals(ActivityLevel.ACTIVE, p.activityLevel)
        assertEquals(UnitSystem.IMPERIAL, p.unitSystem)
        assertEquals(true, p.onboardingCompleted)
    }
}
