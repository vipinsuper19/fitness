package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.common.time.APP_ZONE
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import com.vipin.fitness.core.network.ApiResult
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        server.enqueue(profile("Europe/Berlin"))
        assertEquals(ZoneId.of("Europe/Berlin"), zone.zone())
        server.enqueue(profile("Asia/Tokyo"))
        profiles.update(ProfileChanges(), "k1")
        assertEquals(ZoneId.of("Asia/Tokyo"), zone.zone())
        // Any later profile load also refreshes it, with no extra fetch from ProfileZone.
        server.enqueue(profile("Europe/Paris"))
        profiles.get()
        assertEquals(ZoneId.of("Europe/Paris"), zone.zone())
        assertEquals(3, server.requestCount)
    }

    @Test fun lateProfileResponseAfterAccountSwitchIsNotCached() = runTest {
        // Hold the first account's response until after sign-out and the next sign-in.
        val gate = java.util.concurrent.CountDownLatch(1)
        val seen = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                if (seen.incrementAndGet() == 1) {
                    gate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return profile("America/New_York")
                }
                return profile("Europe/London")
            }
        }
        val late = async(kotlinx.coroutines.Dispatchers.IO) { profiles.get() }
        while (seen.get() < 1) Thread.sleep(5)
        zone.clearUserState()
        zone.onSignedIn("u2")
        gate.countDown()
        assertTrue(late.await() is ApiResult.Success)
        // New York must not stick: the next call fetches again and gets u2's zone.
        assertEquals(ZoneId.of("Europe/London"), zone.zone())
        assertEquals(2, seen.get())
    }
}
