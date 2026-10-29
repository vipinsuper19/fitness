package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId

class ProfileZoneTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var profiles: DefaultProfileRepository
    private lateinit var zone: ProfileZone

    private object NoCache : ProfileFlagsCache {
        override suspend fun onboardingCompleted() = false
        override suspend fun save(onboardingCompleted: Boolean, units: UnitSystem) = Unit
        override suspend fun clear() = Unit
    }

    private fun profile(tz: String) = MockResponse(
        code = 200,
        body = """{"userId":"u","unitSystem":"metric","timezone":"$tz","locale":"en-IN","currency":"INR","onboardingCompleted":true,"updatedAt":"t"}""",
    )

    @Before fun setUp() {
        server.start()
        val r = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        zone = ProfileZone { profiles }
        profiles = DefaultProfileRepository(r.create(ProfileApi::class.java), json, NoCache, zone)
    }

    @After fun tearDown() = server.close()

    @Test fun fetchesOnceThenCaches() = runTest {
        server.enqueue(profile("Europe/London"))
        assertEquals(ZoneId.of("Europe/London"), zone.zone())
        assertEquals(ZoneId.of("Europe/London"), zone.zone())
        assertEquals(1, server.requestCount)
    }

    @Test fun failedFetchFallsBackAndRetries() = runTest {
        server.enqueue(MockResponse(code = 503, body = ""))
        server.enqueue(profile("America/New_York"))
        assertEquals(APP_ZONE, zone.zone())
        assertEquals(ZoneId.of("America/New_York"), zone.zone())
    }

    @Test fun accountSwitchDoesNotInheritTheZone() = runTest {
        server.enqueue(profile("America/New_York"))
        assertEquals(ZoneId.of("America/New_York"), zone.zone())
        // Sign-out, then another account signs in (both clear every UserScopedState).
        val scoped: UserScopedState = zone
        scoped.clearUserState()
        scoped.onSignedIn("u2")
        server.enqueue(profile("Europe/London"))
        assertEquals(ZoneId.of("Europe/London"), zone.zone())
        assertEquals(2, server.requestCount)
    }

    @Test fun timezoneChangeFromAProfileUpdateAppliesAtOnce() = runTest {
        server.enqueue(profile("Asia/Kolkata"))
        assertEquals(APP_ZONE, zone.zone())
        server.enqueue(profile("Asia/Tokyo"))
        profiles.update(ProfileChanges(), "k1")
        assertEquals(ZoneId.of("Asia/Tokyo"), zone.zone())
        // Any later profile load also refreshes it, with no extra fetch from ProfileZone.
        server.enqueue(profile("Europe/Paris"))
        profiles.get()
        assertEquals(ZoneId.of("Europe/Paris"), zone.zone())
        assertEquals(3, server.requestCount)
    }
}
