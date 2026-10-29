package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionSummaryApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class NutritionSummaryRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultNutritionSummaryRepository

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultNutritionSummaryRepository(r.create(NutritionSummaryApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun mapsPointsNullsAndIndependentMacroPcts() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-10-15","to":"2026-10-29","bucket":"week","timezone":"Asia/Kolkata",
            "targets":{"kcal":2000,"proteinG":80,"carbsG":null,"fatG":null,"waterMl":2500},
            "points":[
              {"period":"2026-10-15","daysInPeriod":4,"daysLogged":0,"totals":null,"avgPerLoggedDay":null,"waterMl":null,"waterDaysLogged":0,
               "avgWaterMlPerLoggedDay":null,"adherence":null,"entryCount":0,"entriesMissing":{"fiberG":0,"sugarG":0,"sodiumMg":0}},
              {"period":"2026-10-19","daysInPeriod":7,"daysLogged":3,
               "totals":{"energyKcal":5550.0,"proteinG":277.4,"carbsG":600.0,"fatG":180.2},
               "avgPerLoggedDay":{"energyKcal":1850.0,"proteinG":92.5,"carbsG":200.0,"fatG":60.1},
               "waterMl":4000,"waterDaysLogged":2,"avgWaterMlPerLoggedDay":2000,
               "adherence":{"kcalPct":92.5,"proteinPct":115.6,"carbsPct":null,"fatPct":null,"waterPct":80.0},
               "entryCount":9,"entriesMissing":{"fiberG":9,"sugarG":9,"sodiumMg":9}}]}"""))
        val s = (repo.summary(LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-29"), SummaryBucket.WEEK) as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("/api/v1/nutrition/summary?from=2026-10-15&to=2026-10-29&bucket=week", req.url.encodedPath + "?" + req.url.encodedQuery)
        assertEquals(SummaryBucket.WEEK, s.bucket)
        assertEquals(80, s.targets!!.proteinG)
        assertNull(s.targets!!.carbsG)
        val (empty, full) = s.points
        assertEquals(true, empty.empty)
        assertNull(empty.avgPerLoggedDay)
        assertNull(empty.adherence)
        assertEquals(3, full.daysLogged)
        assertEquals(2, full.waterDaysLogged)
        assertEquals(1850.0, full.avgPerLoggedDay!!.energyKcal, 0.0)
        assertEquals(115.6, full.adherence!!.proteinPct!!, 0.0)
        assertNull(full.adherence!!.carbsPct)
        assertEquals(2000, full.avgWaterMlPerLoggedDay)
    }

    @Test fun decodesPerPointTargetsAndTargetsChanged() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-10-01","to":"2026-10-29","bucket":"month","timezone":"Asia/Kolkata",
            "targets":{"kcal":2000,"proteinG":null,"carbsG":null,"fatG":null,"waterMl":null},
            "points":[
              {"period":"2026-10-01","daysInPeriod":29,"daysLogged":0,"totals":null,"avgPerLoggedDay":null,"waterMl":null,"waterDaysLogged":0,
               "avgWaterMlPerLoggedDay":null,"adherence":null,"entryCount":0,"entriesMissing":{"fiberG":0,"sugarG":0,"sodiumMg":0},
               "targets":{"kcal":2200,"proteinG":90,"carbsG":null,"fatG":70,"waterMl":3000},"targetsChanged":true},
              {"period":"2026-10-02","daysInPeriod":1,"daysLogged":0,"totals":null,"avgPerLoggedDay":null,"waterMl":null,"waterDaysLogged":0,
               "avgWaterMlPerLoggedDay":null,"adherence":null,"entryCount":0,"entriesMissing":{"fiberG":0,"sugarG":0,"sodiumMg":0},
               "targets":null,"targetsChanged":false},
              {"period":"2026-10-03","daysInPeriod":1,"daysLogged":0,"waterDaysLogged":0,"entryCount":0,"entriesMissing":{"fiberG":0,"sugarG":0,"sodiumMg":0}}]}"""))
        val s = (repo.summary(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-29"), SummaryBucket.MONTH) as ApiResult.Success).value
        val (a, b, c) = s.points
        assertEquals(NutritionTargets(2200, 90, null, 70, 3000), a.targets)
        assertEquals(true, a.targetsChanged)
        assertNull(b.targets)
        assertEquals(false, b.targetsChanged)
        // An older server without the fields.
        assertNull(c.targets)
        assertEquals(false, c.targetsChanged)
    }

    @Test fun nullTargetsAndUnknownBucket() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-10-29","to":"2026-10-29","bucket":"day","timezone":"Asia/Kolkata","targets":null,"points":[]}"""))
        val s = (repo.summary(LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-29"), SummaryBucket.DAY) as ApiResult.Success).value
        assertNull(s.targets)
        assertEquals(0, s.points.size)
    }
}
