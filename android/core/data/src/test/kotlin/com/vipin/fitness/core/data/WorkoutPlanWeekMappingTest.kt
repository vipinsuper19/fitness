package com.vipin.fitness.core.data

import com.vipin.fitness.core.data.workout.toDomain
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.model.WorkoutPlanDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #37's plan fields: read when present, null when absent (older servers, inactive plans). */
class WorkoutPlanWeekMappingTest {
    private val json = fitnessJson()
    private fun plan(extra: String) = json.decodeFromString<WorkoutPlanDto>(
        """{"id":"p1","version":1,"name":"PPL","weeks":8,"workouts":[],"isActive":true,"source":"custom",
           "createdAt":"2026-10-01T00:00:00Z","activatedAt":null$extra}""",
    ).toDomain()

    @Test fun mapsCurrentWeekAndPlanEnded() {
        val p = plan(""","currentWeek":3,"planEnded":false""")
        assertEquals(3, p.currentWeek)
        assertEquals(false, p.planEnded)
        assertEquals(true, plan(""","currentWeek":8,"planEnded":true""").planEnded)
    }

    @Test fun missingOrNullFieldsStayNull() {
        assertNull(plan("").currentWeek)
        val p = plan(""","currentWeek":null,"planEnded":null""")
        assertNull(p.currentWeek)
        assertNull(p.planEnded)
    }
}
