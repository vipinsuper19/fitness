package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.NotificationPreferencesApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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

class NotificationPreferencesRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultNotificationPreferencesRepository

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        repo = DefaultNotificationPreferencesRepository(r.create(NotificationPreferencesApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    private fun body(): JsonObject = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test fun specDefaultsFixtureDecodes() = runTest {
        server.enqueue(MockResponse(code = 200, body = SPEC_GET))
        val p = (repo.get() as ApiResult.Success).value
        assertEquals("/api/v1/me/notification-preferences", server.takeRequest().url.encodedPath)
        assertTrue(p.enabled)
        assertEquals(NotificationType.entries.toList(), p.types.keys.toList())
        assertFalse(p.types.getValue(NotificationType.WATER_REMINDER))
        assertFalse(p.types.getValue(NotificationType.FOOD_LOG_REMINDER))
        assertTrue(p.types.getValue(NotificationType.BADGE_EARNED))
        assertEquals(QuietHours(true, "22:00", "07:00"), p.quietHours)
        assertEquals("Asia/Kolkata", p.timezone)
        assertNull(p.updatedAt)
    }

    @Test fun unknownTypesAndFieldsAreIgnoredAndOrderIsTheClients() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"enabled":false,"types":{"reminders":true,"badge_earned":false,"workout_reminder":true,"sleep_tips":false},
            "quietHours":{"enabled":false,"start":"13:00","end":"15:00","extra":1},"timezone":"America/New_York","updatedAt":"2026-10-29T01:02:03.000Z","newField":{}}"""))
        val p = (repo.get() as ApiResult.Success).value
        assertEquals(listOf(NotificationType.WORKOUT_REMINDER, NotificationType.BADGE_EARNED), p.types.keys.toList())
        assertFalse(p.enabled)
        assertEquals(QuietHours(false, "13:00", "15:00"), p.quietHours)
        assertEquals("2026-10-29T01:02:03.000Z", p.updatedAt)
    }

    @Test fun patchSendsOnlyChangedFieldsWithTheKey() = runTest {
        server.enqueue(MockResponse(code = 200, body = SPEC_GET))
        repo.update(NotificationPreferencesChanges(types = mapOf(NotificationType.WATER_REMINDER to true)), "k1")
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals("""{"types":{"water_reminder":true}}""", req.body!!.utf8())
    }

    @Test fun quietWindowSendsStartAndEndTogether() = runTest {
        server.enqueue(MockResponse(code = 200, body = SPEC_GET))
        repo.update(NotificationPreferencesChanges(quietWindow = "23:00" to "07:00"), "k")
        val q = body()["quietHours"]!!.jsonObject
        assertEquals(setOf("start", "end"), q.keys)
    }

    @Test fun masterAndQuietEnabledOnly() = runTest {
        server.enqueue(MockResponse(code = 200, body = SPEC_GET))
        repo.update(NotificationPreferencesChanges(enabled = false, quietEnabled = false), "k")
        assertEquals("""{"enabled":false,"quietHours":{"enabled":false}}""", body().toString())
    }

    @Test fun responseReplacesWithTheFullMergedResult() = runTest {
        server.enqueue(MockResponse(code = 200, body = SPEC_GET.replace("\"water_reminder\": false", "\"water_reminder\": true").replace("null }", "\"2026-10-29T08:00:00.000Z\" }")))
        val p = (repo.update(NotificationPreferencesChanges(types = mapOf(NotificationType.WATER_REMINDER to true)), "k") as ApiResult.Success).value
        assertTrue(p.types.getValue(NotificationType.WATER_REMINDER))
        assertEquals(7, p.types.size)
        assertEquals("2026-10-29T08:00:00.000Z", p.updatedAt)
    }

    @Test fun validationFailedIs400() = runTest {
        server.enqueue(MockResponse(code = 400, body = """{"code":"VALIDATION_FAILED","message":"bad","details":{"fields":{"quietHours":"start and end must differ"}}}"""))
        val e = (repo.update(NotificationPreferencesChanges(quietWindow = "07:00" to "07:00"), "k") as ApiResult.Failure).error as ApiError.Http
        assertEquals(400, e.status)
        assertEquals("VALIDATION_FAILED", e.code)
    }

    @Test fun signOutAndSignInBumpTheUserGeneration() = runTest {
        assertEquals(0, repo.userGeneration.value)
        repo.clearUserState()
        repo.onSignedIn("u2")
        assertEquals(2, repo.userGeneration.value)
    }

    @Test fun changesBetweenPicksOnlyDifferences() {
        val saved = NotificationPreferences(true, mapOf(NotificationType.WATER_REMINDER to false, NotificationType.BADGE_EARNED to true), QuietHours(true, "22:00", "07:00"), "Asia/Kolkata", null)
        assertTrue(NotificationPreferencesChanges.between(saved, saved).isEmpty)
        val c = NotificationPreferencesChanges.between(saved, saved.copy(types = saved.types + (NotificationType.WATER_REMINDER to true), quietHours = saved.quietHours.copy(end = "06:30")))
        assertEquals(mapOf(NotificationType.WATER_REMINDER to true), c.types)
        assertEquals("22:00" to "06:30", c.quietWindow)
        assertNull(c.enabled)
        assertNull(c.quietEnabled)
    }

    private companion object {
        /** docs/specs/notification-preferences.md, GET example. */
        const val SPEC_GET = """{ "enabled": true,
  "types": { "workout_reminder": true, "streak_reminder": true, "water_reminder": false, "food_log_reminder": false,
             "challenge_updates": true, "badge_earned": true, "progression_suggestion": true },
  "quietHours": { "enabled": true, "start": "22:00", "end": "07:00" },
  "timezone": "Asia/Kolkata",
  "updatedAt": null }"""
    }
}
