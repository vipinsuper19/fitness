package com.vipin.fitness.core.data

import com.vipin.fitness.core.data.workout.TrackingType
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.StrengthProgressApi
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
import java.time.Instant
import java.time.LocalDate

class StrengthProgressRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultStrengthProgressRepository
    private val from = LocalDate.parse("2026-08-01")
    private val to = LocalDate.parse("2026-10-29")

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultStrengthProgressRepository(r.create(StrengthProgressApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private val nullPr = """{"maxWeightKg":null,"maxWeightKgAchievedAt":null,"maxReps":null,"maxRepsAchievedAt":null,"best1RmKg":null,
        "best1RmKgAchievedAt":null,"maxDurationSec":null,"maxDurationSecAchievedAt":null,"maxDistanceM":null,"maxDistanceMAchievedAt":null}"""

    @Test fun listDecodesAllTimePrsWithNullableDurationAndDistance() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-08-01","to":"2026-10-29","timezone":"Asia/Kolkata","nextCursor":"c2","items":[
          {"exerciseId":"bench","name":"Bench press","trackingType":"reps_weight","sessions":3,"lastPerformedAt":"2026-10-28T13:00:00Z",
           "pr":{"maxWeightKg":100.0,"maxWeightKgAchievedAt":"2026-01-02T10:00:00Z","maxReps":12,"maxRepsAchievedAt":"2026-10-01T10:00:00Z",
                 "best1RmKg":116.7,"best1RmKgAchievedAt":"2026-01-02T10:00:00Z","maxDurationSec":null,"maxDurationSecAchievedAt":null,
                 "maxDistanceM":null,"maxDistanceMAchievedAt":null}},
          {"exerciseId":"run","name":"Run","trackingType":"distance_duration","sessions":1,"lastPerformedAt":"2026-10-20T01:00:00Z",
           "pr":{"maxWeightKg":null,"maxWeightKgAchievedAt":null,"maxReps":null,"maxRepsAchievedAt":null,"best1RmKg":null,"best1RmKgAchievedAt":null,
                 "maxDurationSec":1800,"maxDurationSecAchievedAt":"2026-10-20T01:00:00Z","maxDistanceM":5000.5,"maxDistanceMAchievedAt":"2026-10-20T01:00:00Z"}},
          {"exerciseId":"new","name":"Never","trackingType":"something_new","sessions":1,"lastPerformedAt":"2026-10-20T01:00:00Z","pr":$nullPr}]}"""))
        val page = (repo.exercises(from, to, "c1") as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("/api/v1/progress/exercises?from=2026-08-01&to=2026-10-29&cursor=c1", req.url.encodedPath + "?" + req.url.encodedQuery)
        assertEquals("c2", page.nextCursor)
        val (bench, run, unknown) = page.items
        assertEquals(TrackingType.REPS_WEIGHT, bench.trackingType)
        assertEquals(100.0, bench.pr.maxWeightKg!!, 0.0)
        assertEquals(Instant.parse("2026-01-02T10:00:00Z"), bench.pr.maxWeightKgAchievedAt)
        assertEquals(12, bench.pr.maxReps)
        assertEquals(116.7, bench.pr.best1RmKg!!, 0.0)
        assertNull(bench.pr.maxDurationSec); assertNull(bench.pr.maxDurationSecAchievedAt)
        assertNull(bench.pr.maxDistanceM); assertNull(bench.pr.maxDistanceMAchievedAt)
        assertEquals(TrackingType.DISTANCE_DURATION, run.trackingType)
        assertNull(run.pr.maxWeightKg); assertNull(run.pr.maxReps); assertNull(run.pr.best1RmKg)
        assertEquals(1800, run.pr.maxDurationSec)
        assertEquals(5000.5, run.pr.maxDistanceM!!, 0.0)
        assertEquals(Instant.parse("2026-10-20T01:00:00Z"), run.pr.maxDistanceMAchievedAt)
        assertTrue(unknown.pr.isEmpty)
        assertEquals(TrackingType.REPS_WEIGHT, unknown.trackingType) // unknown type falls back
    }

    @Test fun firstPageSendsNoCursor() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"from":"2026-08-01","to":"2026-10-29","timezone":"Asia/Kolkata","items":[],"nextCursor":null}"""))
        val page = (repo.exercises(from, to, null) as ApiResult.Success).value
        assertEquals("from=2026-08-01&to=2026-10-29", server.takeRequest().url.encodedQuery)
        assertTrue(page.items.isEmpty()); assertNull(page.nextCursor)
    }

    @Test fun detailDecodesEveryNullablePointField() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"exerciseId":"plank","name":"Plank","trackingType":"duration","from":"2026-10-01","to":"2026-10-29",
          "bucket":"week","timezone":"Asia/Kolkata",
          "pr":{"maxWeightKg":null,"maxWeightKgAchievedAt":null,"maxReps":null,"maxRepsAchievedAt":null,"best1RmKg":null,"best1RmKgAchievedAt":null,
                "maxDurationSec":120,"maxDurationSecAchievedAt":"2026-10-05T03:00:00Z","maxDistanceM":null,"maxDistanceMAchievedAt":null},
          "points":[
            {"period":"2026-10-01","sessions":0,"sets":0,"volumeKg":null,"totalReps":null,"topSetWeightKg":null,"best1RmKg":null,
             "totalDurationSec":null,"totalDistanceM":null,"maxSetDurationSec":null,"maxSetDistanceM":null},
            {"period":"2026-10-05","sessions":2,"sets":6,"volumeKg":1250.25,"totalReps":40,"topSetWeightKg":62.5,"best1RmKg":70.8,
             "totalDurationSec":540,"totalDistanceM":1200.5,"maxSetDurationSec":120,"maxSetDistanceM":800.0}]}"""))
        val p = (repo.exercise("plank", LocalDate.parse("2026-10-01"), to, SummaryBucket.WEEK) as ApiResult.Success).value
        val req = server.takeRequest()
        assertEquals("/api/v1/progress/exercises/plank?from=2026-10-01&to=2026-10-29&bucket=week", req.url.encodedPath + "?" + req.url.encodedQuery)
        assertEquals(TrackingType.DURATION, p.trackingType)
        assertEquals(SummaryBucket.WEEK, p.bucket)
        assertEquals(120, p.pr.maxDurationSec)
        assertNull(p.pr.maxDistanceM)
        val (empty, full) = p.points
        assertTrue(empty.empty)
        listOf(empty.volumeKg, empty.totalReps, empty.topSetWeightKg, empty.best1RmKg, empty.totalDurationSec,
            empty.totalDistanceM, empty.maxSetDurationSec, empty.maxSetDistanceM).forEach { assertNull(it) }
        assertEquals(LocalDate.parse("2026-10-05"), full.period)
        assertEquals(2, full.sessions); assertEquals(6, full.sets)
        assertEquals(1250.25, full.volumeKg!!, 0.0)
        assertEquals(40, full.totalReps)
        assertEquals(62.5, full.topSetWeightKg!!, 0.0)
        assertEquals(70.8, full.best1RmKg!!, 0.0)
        assertEquals(540, full.totalDurationSec)
        assertEquals(1200.5, full.totalDistanceM!!, 0.0)
        assertEquals(120, full.maxSetDurationSec)
        assertEquals(800.0, full.maxSetDistanceM!!, 0.0)
    }

    @Test fun neverPerformedIsAllNullAndUnknownIs404() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"exerciseId":"x","name":"X","trackingType":"reps","from":"2026-10-29","to":"2026-10-29",
          "bucket":"day","timezone":"UTC","pr":$nullPr,"points":[{"period":"2026-10-29","sessions":0,"sets":0,"volumeKg":null,"totalReps":null,
          "topSetWeightKg":null,"best1RmKg":null,"totalDurationSec":null,"totalDistanceM":null,"maxSetDurationSec":null,"maxSetDistanceM":null}]}"""))
        val p = (repo.exercise("x", to, to, SummaryBucket.DAY) as ApiResult.Success).value
        assertTrue(p.pr.isEmpty)
        assertTrue(p.points.single().empty)
        server.enqueue(MockResponse(code = 404, body = """{"code":"NOT_FOUND","message":"x"}"""))
        val f = repo.exercise("gone", to, to, SummaryBucket.DAY) as ApiResult.Failure
        assertEquals(404, (f.error as ApiError.Http).status)
    }
}
