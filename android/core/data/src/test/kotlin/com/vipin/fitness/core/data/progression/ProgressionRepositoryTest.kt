package com.vipin.fitness.core.data.progression

import com.vipin.fitness.core.data.workout.ActivePlan
import com.vipin.fitness.core.data.workout.WorkoutRepository
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.ProgressionApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private fun fixture(name: String) =
    ProgressionRepositoryTest::class.java.classLoader!!.getResource("fixtures/progression/$name")!!.readText()

class ProgressionRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private var refreshes = 0
    private lateinit var repo: DefaultProgressionRepository

    /** Only refreshActivePlan is used by the repository. */
    private val workouts = Proxy.newProxyInstance(WorkoutRepository::class.java.classLoader, arrayOf(WorkoutRepository::class.java)) { _, m, _ ->
        when (m.name) {
            "refreshActivePlan" -> { refreshes++; ApiResult.Success(ActivePlan.None) }
            "getActivePlan" -> MutableStateFlow(null)
            else -> error("unexpected ${m.name}")
        }
    } as WorkoutRepository

    @Before fun setUp() {
        server.start()
        val r = buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultProgressionRepository(r.create(ProgressionApi::class.java), workouts, json, Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneOffset.UTC))
    }

    @After fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse(code = 200, body = body)
    private fun loadMixed(): ProgressionData {
        server.enqueue(ok(fixture("plan_v4.json")))
        server.enqueue(ok(fixture("suggestions_mixed.json")))
        return kotlinx.coroutines.runBlocking { (repo.load(PLAN) as ApiResult.Success).value }
    }

    @Test fun mixedFixtureDecodesAndMatchesByWorkoutAndOrder() {
        val d = loadMixed()
        assertEquals("/api/v1/workout-plans/$PLAN", server.takeRequest().url.encodedPath)
        assertEquals("/api/v1/workout-plans/$PLAN/progression-suggestions", server.takeRequest().url.encodedPath)
        assertEquals(4, d.planVersion)
        assertEquals(0, d.unmatched)
        val s = d.rows.map { it.suggestion }
        assertEquals(listOf(0, 1, 2), s.map { it.order })
        assertEquals(d.plan.workouts[0].exercises.map { it.exerciseId }, s.map { it.exerciseId })
        assertEquals(ProgressionAction.INCREASE, s[0].action)
        assertEquals(41.0, s[0].suggested!!.targetWeightKg!!, 0.0)
        assertEquals(ProgressionReason.COOLDOWN, s[1].reason)
        assertNull(s[1].suggested)
        assertEquals(Instant.parse("2026-11-01T13:00:00Z"), s[1].cooldownUntil)
        assertEquals(ProgressionAction.DELOAD, s[2].action)
        assertEquals(ProgressionReason.REPEATED_MISSES, s[2].reason)
        assertTrue(s.all { it.inputs != null })
        val i0 = s[0].inputs!!
        assertEquals(3, i0.sessionsMetTarget)
        assertEquals("percent_increase", i0.step!!.rule)
        assertEquals(2.5, i0.step!!.percent!!, 0.0)
        assertTrue(s[1].inputs!!.cooldownActive)
        assertEquals(Instant.parse("2026-11-01T13:00:00Z"), s[1].inputs!!.cooldownUntil)
        assertEquals(-10.0, s[2].inputs!!.step!!.percent!!, 0.0)
    }

    @Test fun noHistoryFixtureDecodes() = runTest {
        server.enqueue(ok(fixture("plan_v4.json").replace("\"version\": 4", "\"version\": 1")))
        server.enqueue(ok(fixture("suggestions_no_history.json")))
        val s = (repo.load(PLAN) as ApiResult.Success).value.rows.single().suggestion
        assertEquals(ProgressionReason.NOT_ENOUGH_HISTORY, s.reason)
        assertEquals(ProgressionAction.HOLD, s.action)
        assertTrue(!s.actionable)
        assertNull(s.inputs!!.maxPerceivedDifficulty)
        assertNull(s.inputs!!.step)
    }

    @Test fun missingInputsUnknownFieldsAndUnknownReasonAreTolerated() = runTest {
        server.enqueue(ok(fixture("plan_v4.json")))
        server.enqueue(ok("""{"planId":"$PLAN","planVersion":4,"generatedAt":"2026-10-29T09:00:00+05:30","windowSessions":3,"extra":1,
            "suggestions":[{"plannedWorkoutId":"$WORKOUT","exerciseId":"x","order":0,"trackingType":"reps_weight","action":"increase",
            "reason":"NEW_RULE","reasonText":"t","current":{"sets":3},"suggested":{"sets":4},"cooldownUntil":null,"basedOn":[]},
            {"plannedWorkoutId":"$WORKOUT","exerciseId":"x","order":1,"trackingType":"duration","action":"hold","reason":"COOLDOWN","reasonText":"t",
            "current":{"sets":3},"suggested":null,"cooldownUntil":null,"basedOn":[],"inputs":{"newThing":true,"cooldown":{"active":true,"future":1},"step":{"rule":"new_rule","x":2}}},
            {"plannedWorkoutId":"other","exerciseId":"x","order":0,"trackingType":"reps","action":"increase","reason":"ALL_SETS_COMPLETED","reasonText":"t",
            "current":{"sets":3},"suggested":{"sets":4},"cooldownUntil":null,"basedOn":[]}]}"""))
        val d = (repo.load(PLAN) as ApiResult.Success).value
        assertEquals(1, d.unmatched)
        assertEquals(ProgressionReason.UNKNOWN, d.rows[0].suggestion.reason)
        assertNull(d.rows[0].suggestion.inputs)
        assertEquals("new_rule", d.rows[1].suggestion.inputs!!.step!!.rule)
    }

    @Test fun versionMismatchRefetchesBoth() = runTest {
        server.enqueue(ok(fixture("plan_v4.json").replace("\"version\": 4", "\"version\": 5")))
        server.enqueue(ok(fixture("suggestions_mixed.json")))
        server.enqueue(ok(fixture("plan_v4.json")))
        server.enqueue(ok(fixture("suggestions_mixed.json")))
        val d = (repo.load(PLAN) as ApiResult.Success).value
        assertEquals(4, d.planVersion)
        assertEquals(4, server.requestCount)
    }

    @Test fun suggestionsRateLimitIsAnHttp429WithRetryAfter() = runTest {
        server.enqueue(ok(fixture("plan_v4.json")))
        server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "12"), body = """{"code":"RATE_LIMITED","message":"Slow down"}"""))
        val e = (repo.load(PLAN) as ApiResult.Failure).error as ApiError.Http
        assertEquals(429, e.status)
        assertEquals(12_000L, e.retryAfterMs)
    }

    @Test fun applyBodyEqualsTheSpecExampleAndSendsTheKey() = runTest {
        val d = loadMixed()
        server.takeRequest(); server.takeRequest()
        server.enqueue(ok(fixture("plan_v4.json").replace("\"version\": 4", "\"version\": 5")))
        val accepted = d.rows.map { it.suggestion.key }.toSet() // the hold is ignored even if "accepted"
        assertEquals(ApplyOutcome.Applied(5), repo.apply(d, accepted, "key-1"))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("/api/v1/workout-plans/$PLAN", req.url.encodedPath)
        assertEquals("key-1", req.headers["Idempotency-Key"])
        assertEquals(normal(Json.parseToJsonElement(fixture("apply_request.json"))), normal(Json.parseToJsonElement(req.body!!.utf8())))
        assertEquals(1, refreshes)
    }

    @Test fun onlyAcceptedEntriesChange() {
        val d = loadMixed()
        val body = buildProgressionUpdate(d, setOf(ProgressionSuggestion.keyOf(WORKOUT, 2)))!!
        val ex = body.workouts.single().exercises
        assertEquals(40.0, ex[0].targetWeightKg!!, 0.0)
        assertEquals(27.0, ex[2].targetWeightKg!!, 0.0)
        assertEquals(listOf(90, 60, 90), ex.map { it.restSeconds })
        assertNull(buildProgressionUpdate(d, emptySet()))
        assertNull(buildProgressionUpdate(d, setOf(ProgressionSuggestion.keyOf(WORKOUT, 1))))
    }

    @Test fun errorOutcomes() = runTest {
        val d = loadMixed()
        val all = setOf(ProgressionSuggestion.keyOf(WORKOUT, 0))
        server.enqueue(MockResponse(code = 409, body = fixture("error_stale_version.json")))
        assertEquals(ApplyOutcome.VersionConflict(5), repo.apply(d, all, "k"))
        server.enqueue(MockResponse(code = 409, body = fixture("error_key_reused.json")))
        assertEquals(ApplyOutcome.KeyReused, repo.apply(d, all, "k"))
        server.enqueue(MockResponse(code = 409, body = """{"code":"PLAN_ARCHIVED","message":"a"}"""))
        assertEquals(ApplyOutcome.PlanArchived, repo.apply(d, all, "k"))
        server.enqueue(MockResponse(code = 409, body = """{"code":"IDEMPOTENCY_IN_PROGRESS","message":"a"}"""))
        assertEquals(ApplyOutcome.InProgress, repo.apply(d, all, "k"))
        server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "30"), body = """{"code":"RATE_LIMITED","message":"a"}"""))
        assertEquals(ApplyOutcome.RateLimited(30_000), repo.apply(d, all, "k"))
        server.enqueue(MockResponse(code = 400, body = """{"code":"VALIDATION_FAILED","message":"bad"}"""))
        assertEquals(400, ((repo.apply(d, all, "k") as ApplyOutcome.Failed).error as ApiError.Http).status)
        assertEquals(0, refreshes)
        server.close()
        assertEquals(ApplyOutcome.Offline, repo.apply(d, all, "k"))
    }

    @Test fun aRepeatedExerciseIsMatchedByOrderNotId() {
        val d = loadMixed()
        // Same exercise at orders 0 and 2: only the accepted position changes.
        val plan = d.plan.copy(workouts = d.plan.workouts.map { w -> w.copy(exercises = w.exercises.map { it.copy(exerciseId = "same") }) })
        val m = matchSuggestions(plan, d.rows.map { it.suggestion })
        val ex = buildProgressionUpdate(m, setOf(ProgressionSuggestion.keyOf(WORKOUT, 2)))!!.workouts.single().exercises
        assertEquals(listOf(40.0, null, 27.0), ex.map { it.targetWeightKg })
    }

    @Test fun signOutBumpsTheUserGeneration() = runTest {
        repo.clearUserState()
        repo.onSignedIn("u2")
        assertEquals(2, repo.userGeneration.value)
    }

    /** Explicit nulls and omitted keys are the same to the server. */
    private fun normal(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterValues { it != JsonNull }.mapValues { normal(it.value) })
        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map(::normal))
        is kotlinx.serialization.json.JsonPrimitive -> e.content.toDoubleOrNull()?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: e
        else -> e
    }

    private companion object {
        const val PLAN = "3c7e9a1b-5d2f-4a6c-8e0b-1f3d5a7c9e24"
        const val WORKOUT = "0b9d3c2e-1f4a-4c55-9a7e-2d6f8e1a7b01"
    }
}
