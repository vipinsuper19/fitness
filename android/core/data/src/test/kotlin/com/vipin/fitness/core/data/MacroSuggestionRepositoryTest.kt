package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.MacroSuggestionApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import com.vipin.fitness.core.network.model.ProfileDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MacroSuggestionRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultMacroSuggestionRepository

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultMacroSuggestionRepository(r.create(MacroSuggestionApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private suspend fun fetch(body: String): MacroSuggestion {
        server.enqueue(MockResponse(code = 200, body = body))
        return (repo.suggestion() as ApiResult.Success).value
    }

    @Test fun specExampleAWithKcalAsTheSumOfGrams() = runTest {
        val s = fetch("""{"suggestion":{"proteinG":112,"carbsG":262,"fatG":56,"kcal":2000},"missingInputs":[],
            "basis":{"calorieTargetKcal":2000,"weightKg":70,"goal":"lose_weight","goalSource":"active_goal",
            "dietPreference":"vegetarian","proteinGPerKg":1.6,"fatPct":25},"formulaVersion":1}""")
        assertEquals("/api/v1/me/macro-suggestion", server.takeRequest().url.encodedPath)
        val g = s.suggestion!!
        assertEquals(SuggestedMacros(112, 262, 56, 2000), g)
        assertEquals(4 * g.proteinG + 4 * g.carbsG + 9 * g.fatG, g.kcal)
        assertTrue(s.missingInputs.isEmpty())
        assertEquals(DietPreference.VEGETARIAN, s.basis!!.dietPreference)
        assertFalse(s.basis!!.defaultGoal)
    }

    @Test fun specExampleFKcalFarBelowTarget() = runTest {
        val s = fetch("""{"suggestion":{"proteinG":24,"carbsG":1000,"fatG":167,"kcal":5599},"missingInputs":[],
            "basis":{"calorieTargetKcal":6000,"weightKg":20,"goal":"maintain","goalSource":"default","dietPreference":null,"proteinGPerKg":1.2,"fatPct":25},"formulaVersion":1}""")
        assertEquals(5599, s.suggestion!!.kcal)
        assertEquals(6000, s.basis!!.calorieTargetKcal)
        assertTrue(s.basis!!.defaultGoal)
    }

    @Test fun missingInputsInOrderWithNullSuggestion() = runTest {
        val s = fetch("""{"suggestion":null,"missingInputs":["CALORIE_TARGET","WEIGHT"],
            "basis":{"calorieTargetKcal":null,"weightKg":null,"goal":"maintain","goalSource":"default","dietPreference":null,"proteinGPerKg":1.2,"fatPct":25},"formulaVersion":1}""")
        assertNull(s.suggestion)
        assertEquals(listOf(MacroInput.CALORIE_TARGET, MacroInput.WEIGHT), s.missingInputs)
        assertNull(s.basis!!.weightKg)
    }

    @Test fun unknownInputsAndInconsistentPayloadsNeverShowASuggestion() = runTest {
        val unknown = fetch("""{"suggestion":null,"missingInputs":["WEIGHT","HEIGHT","AGE"],"basis":{"goal":"maintain","goalSource":"default"},"formulaVersion":2}""")
        assertEquals(listOf(MacroInput.WEIGHT, MacroInput.UNKNOWN), unknown.missingInputs)
        val both = fetch("""{"suggestion":{"proteinG":1,"carbsG":1,"fatG":1,"kcal":17},"missingInputs":["WEIGHT"],"basis":{"goal":"maintain","goalSource":"default"},"formulaVersion":1}""")
        assertNull(both.suggestion)
        val neither = fetch("""{"suggestion":null,"missingInputs":[],"basis":{"goal":"maintain","goalSource":"default"},"formulaVersion":1}""")
        assertEquals(listOf(MacroInput.UNKNOWN), neither.missingInputs)
    }

    @Test fun profileMacroTargetsReadZeroAsNullAndPutSetOrClear() {
        val p = ProfileDto(userId = "u", dailyProteinTargetG = 112, dailyCarbsTargetG = 0, dailyFatTargetG = null, updatedAt = "2026-10-29T00:00:00Z").toDomain()
        assertEquals(112, p.dailyProteinTargetG)
        assertNull(p.dailyCarbsTargetG)
        assertNull(p.dailyFatTargetG)
        val body = ProfileChanges(dailyProteinTargetG = Field.Set(112), dailyCarbsTargetG = Field.Clear).toJson()
        assertEquals(JsonPrimitive(112), body["dailyProteinTargetG"])
        assertEquals(JsonNull, body["dailyCarbsTargetG"])
        assertFalse("dailyFatTargetG" in body)
    }
}
