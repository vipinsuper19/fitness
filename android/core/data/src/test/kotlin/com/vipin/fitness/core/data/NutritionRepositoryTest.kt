package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class NutritionRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultNutritionRepository
    private val day = LocalDate.of(2026, 10, 21)

    @Before fun setUp() {
        server.start()
        val r = buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultNutritionRepository(r.create(NutritionApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private val n = """{"energyKcal":120,"proteinG":3.5,"carbsG":20,"fatG":2}"""
    private val logBody = """{"id":"l1","foodId":"f1","foodName":"Poha","date":"2026-10-21","mealType":"breakfast",
        "servings":1.5,"nutrients":$n,"nutrientsEdited":false,"createdAt":"2026-10-21T03:00:00Z"}"""
    private val foodBody = """{"id":"f9","name":"Dal","servingSize":1,"servingUnit":"katori","gramsPerServing":150,
        "nutrients":$n,"source":"custom"}"""

    @Test fun dayLoadsTotalsLogsAndWaterForTheLocalDate() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/v1/nutrition/daily" -> MockResponse(code = 200, body = """{"date":"2026-10-21","entryCount":2,
                    "entriesMissing":{"fiberG":1,"sugarG":2,"sodiumMg":0},"totals":$n,
                    "byMeal":{"breakfast":$n,"lunch":$n,"dinner":$n,"snack":$n},"waterMl":750,"calorieTargetKcal":2000,"waterTargetMl":null}""")
                "/api/v1/food-logs" -> MockResponse(code = 200, body = """{"items":[$logBody]}""")
                "/api/v1/water-logs" -> MockResponse(code = 200, body = """{"items":[{"id":"w1","date":"2026-10-21","amountMl":250,"createdAt":"2026-10-21T03:00:00Z"}]}""")
                else -> MockResponse(code = 404)
            }
        }
        val r = repo.day(day) as ApiResult.Success
        assertEquals(750, r.value.daily.waterMl)
        assertEquals(2000, r.value.daily.calorieTargetKcal)
        assertTrue(r.value.daily.partialOptionalTotals)
        assertEquals(MealType.BREAKFAST, r.value.foodLogs.single().mealType)
        assertEquals(1.5, r.value.foodLogs.single().servings, 0.0)
        assertEquals(250, r.value.waterLogs.single().amountMl)
        val paths = List(3) { server.takeRequest().url.toString() }
        assertTrue(paths.all { it.endsWith("date=2026-10-21") })
    }

    @Test fun searchTrimsQueryAndPassesCursor() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[$foodBody],"nextCursor":"c2"}"""))
        val page = (repo.searchFoods("  dal ", "c1") as ApiResult.Success).value
        assertEquals("c2", page.nextCursor)
        assertEquals(ServingUnit.KATORI, page.items.single().servingUnit)
        assertTrue(page.items.single().custom)
        val url = server.takeRequest().url
        assertEquals("dal", url.queryParameter("query"))
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun logFoodSendsKeyAndRoundedOverride() = runTest {
        server.enqueue(MockResponse(code = 201, body = logBody))
        repo.logFood("f1", day, MealType.LUNCH, 2.0, Nutrients(100.04, 3.06, 20.0, 2.0), "k-1")
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("k-1", req.headers["Idempotency-Key"])
        assertEquals(
            """{"foodId":"f1","date":"2026-10-21","mealType":"lunch","servings":2.0,""" +
                """"nutrientsOverride":{"energyKcal":100.0,"proteinG":3.1,"carbsG":20.0,"fatG":2.0}}""",
            req.body?.utf8(),
        )
    }

    @Test fun createFoodSendsKeyAndTrimmedFields() = runTest {
        server.enqueue(MockResponse(code = 201, body = foodBody))
        repo.createFood(NewFood(" Dal ", " ", 1.0, ServingUnit.KATORI, 150.0, Nutrients(120.0, 3.5, 20.0, 2.0)), "k-2")
        val req = server.takeRequest()
        assertEquals("k-2", req.headers["Idempotency-Key"])
        assertEquals(
            """{"name":"Dal","servingSize":1.0,"servingUnit":"katori","gramsPerServing":150.0,""" +
                """"nutrients":{"energyKcal":120.0,"proteinG":3.5,"carbsG":20.0,"fatG":2.0}}""",
            req.body?.utf8(),
        )
    }

    @Test fun updateFoodLogSendsOnlyChangedKeysAndNullToRestore() = runTest {
        server.enqueue(MockResponse(code = 200, body = logBody))
        server.enqueue(MockResponse(code = 200, body = logBody))
        repo.updateFoodLog("l1", FoodLogChanges(servings = 2.0), "k-3")
        repo.updateFoodLog("l1", FoodLogChanges(mealType = MealType.DINNER, nutrients = Field.Clear), "k-4")
        val a = server.takeRequest()
        assertEquals("PATCH", a.method)
        assertEquals("/api/v1/food-logs/l1", a.url.encodedPath)
        assertEquals("""{"servings":2.0}""", a.body?.utf8())
        assertEquals("k-3", a.headers["Idempotency-Key"])
        assertEquals("""{"mealType":"dinner","nutrientsOverride":null}""", server.takeRequest().body?.utf8())
    }

    @Test fun deleteMaps204AndErrors() = runTest {
        server.enqueue(MockResponse(code = 204))
        server.enqueue(MockResponse(code = 404, body = """{"code":"NOT_FOUND","message":"gone","requestId":"q"}"""))
        assertEquals(ApiResult.Success(Unit), repo.deleteFoodLog("l1", "k-5"))
        val r = repo.deleteWater("w1", "k-6") as ApiResult.Failure
        assertEquals("NOT_FOUND", (r.error as ApiError.Http).code)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("k-5", req.headers["Idempotency-Key"])
    }

    @Test fun waterWritesSendDateAmountAndKey() = runTest {
        val w = """{"id":"w1","date":"2026-10-21","amountMl":500,"createdAt":"2026-10-21T03:00:00Z"}"""
        server.enqueue(MockResponse(code = 201, body = w))
        server.enqueue(MockResponse(code = 200, body = w))
        assertEquals(500, (repo.addWater(day, 500, "k-7") as ApiResult.Success).value.amountMl)
        repo.updateWater("w1", day, 300, "k-8")
        assertEquals("""{"date":"2026-10-21","amountMl":500}""", server.takeRequest().body?.utf8())
        val u = server.takeRequest()
        assertEquals("PATCH", u.method)
        assertEquals("k-8", u.headers["Idempotency-Key"])
        assertEquals("""{"date":"2026-10-21","amountMl":300}""", u.body?.utf8())
    }
}
