package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.ProfileApi
import com.vipin.fitness.core.network.auth.AuthApi
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.SessionEvents
import com.vipin.fitness.core.network.auth.TokenStore
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.buildRetrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class RepositoriesTest {
    private class MemoryTokenStore(var tokens: AuthTokens? = null) : TokenStore {
        override fun get() = tokens
        override fun save(tokens: AuthTokens) { this.tokens = tokens }
        override fun clear() { tokens = null }
        override fun compareAndSet(expectedRefresh: String, tokens: AuthTokens): Boolean = synchronized(this) {
            if (this.tokens?.refreshToken != expectedRefresh) return false
            this.tokens = tokens
            true
        }
        override fun clearIf(expectedRefresh: String): Boolean = synchronized(this) {
            if (tokens?.refreshToken != expectedRefresh) return false
            tokens = null
            true
        }
    }

    private class MemoryCache : ProfileFlagsCache {
        var completed: Boolean? = null
        var units: UnitSystem? = null
        override suspend fun onboardingCompleted() = completed ?: false
        override suspend fun save(onboardingCompleted: Boolean, units: UnitSystem) { completed = onboardingCompleted; this.units = units }
        override suspend fun clear() { completed = null; units = null }
    }

    private val cache = MemoryCache()
    private val userState = object : UserScopedState {
        var clears = 0
        val signIns = mutableListOf<String>()
        override suspend fun clearUserState() { clears++ }
        override suspend fun onSignedIn(userId: String) { signIns += userId }
    }
    private val server = MockWebServer()
    private val json = fitnessJson()
    private val store = MemoryTokenStore()
    private lateinit var session: DefaultSessionRepository
    private lateinit var profiles: DefaultProfileRepository

    @Before fun setUp() {
        server.start()
        val r = buildRetrofit(server.url("/api/v1/").toString(), OkHttpClient(), json)
        val clock = Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)
        session = DefaultSessionRepository(r.create(AuthApi::class.java), store, SessionEvents(), json, clock, cache, setOf(userState))
        profiles = DefaultProfileRepository(r.create(ProfileApi::class.java), json, cache)
    }

    @After fun tearDown() = server.close()

    private val authBody = """{"accessToken":"a","refreshToken":"r","tokenType":"Bearer","expiresIn":900,
        "user":{"id":"u","email":"v@x.in","displayName":null,"createdAt":"2026-10-21T00:00:00Z"}}"""

    @Test fun signInStoresTokens() = runTest {
        server.enqueue(MockResponse(code = 200, body = authBody))
        assertEquals(AuthOutcome.Success, session.signIn(" v@x.in ", "pw"))
        assertEquals(AuthTokens("a", "r", 900_000), store.tokens)
        val req = server.takeRequest()
        assertEquals("""{"email":"v@x.in","password":"pw"}""", req.body?.utf8())
        assertNull(req.headers["Idempotency-Key"])
    }

    @Test fun successfulAuthClearsPreviousAccountsCache() = runTest {
        cache.completed = true
        cache.units = UnitSystem.IMPERIAL
        server.enqueue(MockResponse(code = 200, body = authBody))
        assertEquals(AuthOutcome.Success, session.signIn("other@x.in", "pw"))
        assertEquals(false, cache.onboardingCompleted())
        assertNull(cache.units)

        cache.completed = true
        server.enqueue(MockResponse(code = 201, body = authBody))
        assertEquals(AuthOutcome.Success, session.register("new@x.in", "password1", null, "k"))
        assertEquals(false, cache.onboardingCompleted())
    }

    @Test fun failedAuthKeepsCache() = runTest {
        cache.completed = true
        server.enqueue(MockResponse(code = 401, body = """{"code":"INVALID_CREDENTIALS","message":"no","requestId":"q"}"""))
        session.signIn("v@x.in", "bad")
        assertEquals(true, cache.onboardingCompleted())
    }

    @Test fun registerSendsIdempotencyKeyAndMapsEmailTaken() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"code":"EMAIL_TAKEN","message":"exists","requestId":"q"}"""))
        assertEquals(AuthOutcome.Failure(AuthFailure.EmailTaken), session.register("v@x.in", "password1", " ", "key-1"))
        val req = server.takeRequest()
        assertEquals("key-1", req.headers["Idempotency-Key"])
        assertEquals("""{"email":"v@x.in","password":"password1"}""", req.body?.utf8()) // blank name omitted
        assertNull(store.tokens)
    }

    @Test fun invalidCredentialsAndRateLimitMapped() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"code":"INVALID_CREDENTIALS","message":"no","requestId":"q"}"""))
        server.enqueue(MockResponse(code = 429, body = """{"code":"RATE_LIMITED","message":"slow","requestId":"q"}"""))
        assertEquals(AuthOutcome.Failure(AuthFailure.InvalidCredentials), session.signIn("a@b.c", "x"))
        assertEquals(AuthOutcome.Failure(AuthFailure.RateLimited), session.signIn("a@b.c", "x"))
    }

    @Test fun signOutClearsLocallyEvenIfServerFails() = runTest {
        store.tokens = AuthTokens("a", "r", 0)
        cache.completed = true
        server.enqueue(MockResponse(code = 500, body = ""))
        session.signOut()
        assertNull(store.tokens)
        assertNull(cache.completed)
        assertEquals(1, userState.clears)
        assertEquals("""{"refreshToken":"r"}""", server.takeRequest().body?.utf8())
    }

    @Test fun endSessionLocallyClearsOnlyTokens() = runTest {
        store.tokens = AuthTokens("a", "r", 0)
        cache.completed = true
        session.endSessionLocally()
        assertNull(store.tokens)
        assertEquals(0, userState.clears) // outbox, unsynced workouts... kept for the same user
        assertEquals(true, cache.completed)
        assertEquals(0, server.requestCount) // no server logout
    }

    @Test fun signInClearsUserStateOnlyOnSuccess() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"code":"INVALID_CREDENTIALS","message":"no","requestId":"q"}"""))
        server.enqueue(MockResponse(code = 200, body = authBody))
        session.signIn("a@b.c", "x")
        assertEquals(emptyList<String>(), userState.signIns)
        session.signIn("a@b.c", "x")
        assertEquals(listOf("u"), userState.signIns) // the signed-in user's id, for per-user state
        assertEquals(0, userState.clears)
    }

    @Test fun profileUpdateSendsPresenceAwareBodyAndKey() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"userId":"u","heightCm":180.0,"unitSystem":"metric","timezone":"Asia/Kolkata",
                    "locale":"en-IN","currency":"INR","onboardingCompleted":false,"updatedAt":"t"}""",
            ),
        )
        val result = profiles.update(ProfileChanges(heightCm = Field.Set(180.0), birthYear = Field.Clear), "k-9")
        assertEquals(180.0, (result as ApiResult.Success).value.heightCm!!, 0.0)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("k-9", req.headers["Idempotency-Key"])
        assertEquals("""{"birthYear":null,"heightCm":180.0}""", req.body?.utf8())
        assertEquals(false, cache.completed)
    }

    @Test fun profileLoadRefreshesLaunchCache() = runTest {
        server.enqueue(
            MockResponse(
                code = 200,
                body = """{"userId":"u","unitSystem":"imperial","timezone":"Asia/Kolkata",
                    "locale":"en-IN","currency":"INR","onboardingCompleted":true,"updatedAt":"t"}""",
            ),
        )
        server.enqueue(MockResponse(code = 503, body = ""))
        profiles.get()
        assertEquals(true, cache.completed)
        assertEquals(UnitSystem.IMPERIAL, cache.units)
        profiles.get() // failure leaves the cache alone
        assertEquals(true, cache.completed)
    }

    @Test fun idempotencyConflictsAndUnexpectedMapped() = runTest {
        server.enqueue(MockResponse(code = 409, body = """{"code":"IDEMPOTENCY_IN_PROGRESS","message":"busy","requestId":"q"}"""))
        server.enqueue(MockResponse(code = 409, body = """{"code":"IDEMPOTENCY_KEY_REUSED","message":"reused","requestId":"q"}"""))
        assertEquals(AuthOutcome.Failure(AuthFailure.InProgress), session.register("v@x.in", "password1", null, "k"))
        assertEquals(AuthOutcome.Failure(AuthFailure.KeyReused), session.register("v@x.in", "password1", null, "k"))
        assertEquals(AuthFailure.Unexpected, ApiError.Unexpected(IllegalStateException("secret detail")).toAuthFailure())
    }
}
