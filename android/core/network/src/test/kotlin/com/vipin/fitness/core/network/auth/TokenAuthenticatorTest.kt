package com.vipin.fitness.core.network.auth

import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.http.GET
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest {
    private interface TestApi {
        @GET("me/profile") fun profile(): Call<ResponseBody>
        @Unauthenticated @GET("exercises") fun publicCall(): Call<ResponseBody>
    }

    private class MemoryTokenStore(var tokens: AuthTokens?) : TokenStore {
        override fun get() = synchronized(this) { tokens }
        override fun save(tokens: AuthTokens) = synchronized(this) { this.tokens = tokens }
        override fun clear() = synchronized(this) { tokens = null }
    }

    private val server = MockWebServer()
    private val refreshCalls = AtomicInteger()
    private val authHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private var refreshResponse: () -> MockResponse = { tokenPair("new-access", "new-refresh") }
    private var validAccess = "new-access"

    private val store = MemoryTokenStore(AuthTokens("old-access", "old-refresh", 0))
    private val events = SessionEvents()
    private lateinit var api: TestApi

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (path.endsWith("/auth/refresh")) {
                    refreshCalls.incrementAndGet()
                    Thread.sleep(100) // widen the race window
                    return refreshResponse()
                }
                val auth = request.headers["Authorization"]
                authHeaders += auth
                return if (path.endsWith("/exercises") || auth == "Bearer $validAccess") {
                    MockResponse(code = 200, body = "{}")
                } else {
                    MockResponse(code = 401, body = """{"code":"UNAUTHORIZED","message":"no","requestId":"r"}""")
                }
            }
        }
        server.start()
        val baseUrl = server.url("/api/v1/").toString()
        val json = fitnessJson()
        val base = OkHttpClient()
        val refresher = RetrofitTokenRefresher(retrofit(baseUrl, base, json).create(RefreshApi::class.java)) { 1_000L }
        val client = base.newBuilder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenAuthenticator(store, refresher, events))
            .build()
        api = retrofit(baseUrl, client, json).create(TestApi::class.java)
    }

    @After fun tearDown() = server.close()

    @Test fun refreshesOnceAndRetriesWithNewToken() {
        val response = api.profile().execute()

        assertEquals(200, response.code())
        assertEquals(1, refreshCalls.get())
        assertEquals(listOf("Bearer old-access", "Bearer new-access"), authHeaders.toList())
        assertEquals(AuthTokens("new-access", "new-refresh", 1_000L + 900_000L), store.tokens)
    }

    @Test fun concurrent401sShareOneRefresh() {
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val codes = (1..n).map {
            pool.submit<Int> { start.await(); api.profile().execute().code() }
        }
        start.countDown()
        val results = codes.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(List(n) { 200 }, results)
        assertEquals(1, refreshCalls.get())
        assertEquals("new-refresh", store.tokens?.refreshToken)
    }

    @Test fun rejectedRefreshClearsSessionAndSignalsExpiry() = runBlocking {
        refreshResponse = { MockResponse(code = 401, body = """{"code":"REFRESH_TOKEN_REUSED","message":"x","requestId":"r"}""") }
        val signals = mutableListOf<Unit>()
        val job = launch(Dispatchers.Unconfined) { events.expired.collect { signals += it } }

        val response = withContext(Dispatchers.IO) { api.profile().execute() }
        job.cancel()

        assertEquals(401, response.code())
        assertEquals(1, refreshCalls.get())
        assertNull(store.tokens)
        assertEquals(1, signals.size)
    }

    @Test fun transientRefreshFailureKeepsSession() {
        refreshResponse = { MockResponse(code = 503, body = "") }

        val response = api.profile().execute()

        assertEquals(401, response.code())
        assertEquals("old-refresh", store.tokens?.refreshToken)
    }

    @Test fun doesNotLoopWhenNewTokenIsAlsoRejected() {
        validAccess = "never"

        val response = api.profile().execute()

        assertEquals(401, response.code())
        assertEquals(1, refreshCalls.get())
        assertEquals(2, authHeaders.size)
    }

    @Test fun unauthenticatedCallsCarryNoBearer() {
        val response = api.publicCall().execute()

        assertEquals(200, response.code())
        assertEquals(listOf<String?>(null), authHeaders.toList())
    }

    private fun tokenPair(access: String, refresh: String) = MockResponse(
        code = 200,
        body = """{"accessToken":"$access","refreshToken":"$refresh","tokenType":"Bearer","expiresIn":900,"extra":1}""",
    )
}
