package com.vipin.fitness.core.data

import com.vipin.fitness.core.common.UnitSystem
import com.vipin.fitness.core.datastore.ProfileFlagsCache
import com.vipin.fitness.core.network.AccountApi
import com.vipin.fitness.core.network.auth.AuthTokens
import com.vipin.fitness.core.network.auth.TokenStore
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AccountRepositoryTest {
    private val tokens = object : TokenStore {
        var t: AuthTokens? = AuthTokens("a", "r", Long.MAX_VALUE)
        override fun get() = t
        override fun save(tokens: AuthTokens) { t = tokens }
        override fun clear() { t = null }
    }
    private val cache = object : ProfileFlagsCache {
        var cleared = 0
        override suspend fun onboardingCompleted() = true
        override suspend fun save(onboardingCompleted: Boolean, units: UnitSystem) = Unit
        override suspend fun clear() { cleared++ }
    }
    private val userState = object : UserScopedState {
        var clears = 0
        override suspend fun clearUserState() { clears++ }
    }
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultAccountRepository

    @Before fun setUp() {
        server.start()
        val api = retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(AccountApi::class.java)
        repo = DefaultAccountRepository(api, tokens, json, cache, setOf(userState))
    }

    @After fun tearDown() = server.close()

    @Test fun deleteSendsPasswordAndKeyThenClearsEverythingLocal() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertEquals(DeleteAccountOutcome.Deleted, repo.deleteAccount("hunter22", "k1"))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/me", req.url.encodedPath)
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals("""{"password":"hunter22"}""", req.body!!.utf8())
        assertNull(tokens.get())
        assertEquals(1, cache.cleared)
        assertEquals(1, userState.clears) // outbox, sessions, caches
    }

    @Test fun wrongPasswordKeepsTheSession() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"code":"INVALID_PASSWORD","message":"x"}"""))
        assertEquals(DeleteAccountOutcome.WrongPassword, repo.deleteAccount("nope", "k1"))
        assertNotNull(tokens.get())
        assertEquals(0, userState.clears)
    }

    @Test fun serverTroubleAndRateLimitsAreReported() = runTest {
        server.enqueue(MockResponse(code = 503, body = ""))
        assertEquals(DeleteAccountOutcome.Offline, repo.deleteAccount("p", "k1"))
        server.enqueue(MockResponse(code = 429, body = """{"code":"RATE_LIMITED","message":"x"}"""))
        assertEquals(DeleteAccountOutcome.RateLimited, repo.deleteAccount("p", "k1"))
        server.enqueue(MockResponse(code = 400, body = """{"code":"VALIDATION_FAILED","message":"x"}"""))
        assertEquals(DeleteAccountOutcome.Failed, repo.deleteAccount("p", "k1"))
        assertNotNull(tokens.get())
    }

    @Test fun offlineIsOffline() = runTest {
        server.close()
        assertEquals(DeleteAccountOutcome.Offline, repo.deleteAccount("p", "k1"))
        assertNotNull(tokens.get())
    }
}
