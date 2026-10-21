package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NutritionApi
import com.vipin.fitness.core.network.ProgressApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ProgressRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultProgressRepository
    private lateinit var home: DefaultHomeRepository
    private val day = LocalDate.of(2026, 10, 21)
    private val m = """{"id":"m1","date":"2026-10-21","weightKg":80.5,"waistCm":null,"createdAt":"2026-10-21T03:00:00Z"}"""

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultProgressRepository(r.create(ProgressApi::class.java), json)
        home = DefaultHomeRepository(r.create(ProgressApi::class.java), r.create(NutritionApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun listMapsOnlyPresentValuesAndPages() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[$m],"nextCursor":"c2"}"""))
        val page = (repo.measurements("c1") as ApiResult.Success).value
        assertEquals(mapOf(MeasurementKind.WEIGHT to 80.5), page.items.single().values)
        assertEquals("c2", page.nextCursor)
        val url = server.takeRequest().url
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun createSendsKeyDateAndRoundedMetric() = runTest {
        server.enqueue(MockResponse(code = 201, body = m))
        repo.addMeasurement(NewMeasurement(day, mapOf(MeasurementKind.WEIGHT to 80.0128, MeasurementKind.ARM to 31.0)), "key-1")
        val req = server.takeRequest()
        assertEquals("key-1", req.headers["Idempotency-Key"])
        assertEquals("""{"date":"2026-10-21","weightKg":80.01,"armCm":31.0}""", req.body!!.utf8())
    }

    @Test fun patchSendsOnlyChangedKeysAndNullToClear() = runTest {
        server.enqueue(MockResponse(code = 200, body = m))
        repo.updateMeasurement(
            "m1",
            MeasurementChanges(values = mapOf(MeasurementKind.WEIGHT to Field.Set(79.255), MeasurementKind.WAIST to Field.Clear, MeasurementKind.HIP to Field.Unchanged)),
            "key-2",
        )
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v1/body-measurements/m1", req.url.encodedPath)
        assertEquals("""{"weightKg":79.26,"waistCm":null}""", req.body!!.utf8())
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyPatchIsNeverSent() = runTest {
        repo.updateMeasurement("m1", MeasurementChanges(values = mapOf(MeasurementKind.HIP to Field.Unchanged)), "k")
    }

    @Test fun deleteMaps204() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertTrue(repo.deleteMeasurement("m1", "k") is ApiResult.Success)
        assertEquals("k", server.takeRequest().headers["Idempotency-Key"])
    }

    @Test fun summaryKeepsNullsAndFillsMissingDaysAsGaps() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-10-19","to":"2026-10-21","streakDays":2,"points":[
            {"date":"2026-10-19","weightKg":null,"workoutsCompleted":0,"activeMinutes":0,"energyKcal":0,"xpEarned":0},
            {"date":"2026-10-21","weightKg":80.2,"bodyFatPct":18.3,"waistCm":null,"waterMl":2250,"workoutsCompleted":1,"activeMinutes":40,"energyKcal":null,"xpEarned":50}]}"""))
        val s = (repo.summary(day.minusDays(2), day) as ApiResult.Success).value
        assertEquals(3, s.points.size)
        assertNull(s.points[0].weightKg)
        assertEquals(0.0, s.points[0].energyKcal!!, 0.0)
        assertEquals(ProgressPoint(day.minusDays(1)), s.points[1])
        assertNull(s.points[2].energyKcal)
        assertEquals(18.3, s.points[2].bodyFatPct!!, 0.0)
        assertNull(s.points[2].waistCm)
        assertEquals(2250, s.points[2].waterMl)
        assertNull(s.points[0].waterMl)
        assertEquals(2, s.streakDays)
        val url = server.takeRequest().url
        assertEquals("2026-10-19", url.queryParameter("from"))
        assertEquals("2026-10-21", url.queryParameter("to"))
    }

    @Test fun xpBalanceMaps() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"total":530,"level":3,"xpIntoLevel":30,"xpForNextLevel":200}"""))
        assertEquals(XpBalance(530, 3, 30, 200), (home.xpBalance() as ApiResult.Success).value)
    }
}
