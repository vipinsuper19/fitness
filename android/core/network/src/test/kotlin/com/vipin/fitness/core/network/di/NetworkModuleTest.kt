package com.vipin.fitness.core.network.di

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.DEFAULT_RETRY_AFTER_MS
import com.vipin.fitness.core.network.apiCall
import com.vipin.fitness.core.network.model.RefreshRequestDto
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import java.io.File

/**
 * Calls the real Hilt providers directly. `NetworkModule.retrofit` and `refreshApi` once called
 * themselves (a member shadowed the top-level helper) and overflowed the stack on first injection.
 */
class NetworkModuleTest {
    private val server = MockWebServer()
    private val json = NetworkModule.json()
    private lateinit var base: String

    @Before fun setUp() {
        server.start()
        base = server.url("/api/v1/").toString()
    }

    @After fun tearDown() = server.close()

    @Test fun retrofitProviderBuildsAndMakesARealCall() = runTest {
        val retrofit = NetworkModule.retrofit(base, NetworkModule.baseClient(), json)
        assertEquals(base, retrofit.baseUrl().toString())
        server.enqueue(MockResponse(code = 200, body = """{"userId":"u1","displayName":"Asha","onboardingCompleted":true,"unitSystem":"metric","updatedAt":"2026-10-29T10:00:00Z"}"""))
        val p = NetworkModule.profileApi(retrofit).get()
        assertEquals("u1", p.userId)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/v1/me/profile", req.url.encodedPath)
    }

    @Test fun refreshApiProviderMakesARealCall() {
        val api = NetworkModule.refreshApi(base, NetworkModule.baseClient(), json)
        server.enqueue(MockResponse(code = 200, body = """{"accessToken":"a","refreshToken":"r","expiresIn":900}"""))
        val r = api.refresh(RefreshRequestDto("old")).execute()
        assertEquals("a", r.body()!!.accessToken)
        assertEquals("/api/v1/auth/refresh", server.takeRequest().url.encodedPath)
    }

    @Test fun everyApiProviderInEveryNetworkModuleBuilds() {
        val retrofit = NetworkModule.retrofit(base, NetworkModule.baseClient(), json)
        NetworkModule.authApi(retrofit); NetworkModule.workoutApi(retrofit); NetworkModule.outboxApi(retrofit)
        // Every `@Provides fun x(retrofit: Retrofit)` in this package, so a new module is covered too.
        val dir = File("src/main/kotlin/com/vipin/fitness/core/network/di")
        val modules = dir.listFiles()!!.filter { it.name.endsWith("NetworkModule.kt") }.map { it.nameWithoutExtension }
        assertTrue(modules.size > 5)
        var built = 0
        modules.forEach { name ->
            val cls = Class.forName("com.vipin.fitness.core.network.di.$name")
            val instance = cls.getField("INSTANCE").get(null)
            cls.declaredMethods.filter { m -> m.parameterTypes.contentEquals(arrayOf(Retrofit::class.java)) }.forEach { m ->
                assertNotNull("$name.${m.name}", m.invoke(instance, retrofit))
                built++
            }
        }
        assertTrue("built $built", built >= modules.size)
    }

    @Test fun a429WithoutRetryAfterWaitsTheDefault() = runTest {
        val api = NetworkModule.profileApi(NetworkModule.retrofit(base, NetworkModule.baseClient(), json))
        server.enqueue(MockResponse(code = 429, body = """{"code":"RATE_LIMITED","message":"slow"}"""))
        val e = (apiCall(json) { api.get() } as ApiResult.Failure).error as ApiError.Http
        assertEquals(DEFAULT_RETRY_AFTER_MS, e.retryAfterMs)
        server.enqueue(MockResponse(code = 429, headers = Headers.headersOf("Retry-After", "5"), body = """{"code":"RATE_LIMITED","message":"slow"}"""))
        assertEquals(5_000L, ((apiCall(json) { api.get() } as ApiResult.Failure).error as ApiError.Http).retryAfterMs)
    }

}
