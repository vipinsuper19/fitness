package com.vipin.fitness.core.data

import com.vipin.fitness.core.network.AccountApi
import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.di.fitnessJson
import com.vipin.fitness.core.network.di.retrofit
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultAccountRepository

    @Before fun setUp() {
        server.start()
        repo = DefaultAccountRepository(retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(AccountApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun deleteSendsPasswordBodyAndKey() = runTest {
        server.enqueue(MockResponse(code = 204))
        assertEquals(ApiResult.Success(Unit), repo.deleteAccount("hunter22", "k1"))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/api/v1/me", req.url.encodedPath)
        assertEquals("k1", req.headers["Idempotency-Key"])
        assertEquals("""{"password":"hunter22"}""", req.body!!.utf8())
    }

    @Test fun wrongPasswordKeepsTheContractCode() = runTest {
        server.enqueue(MockResponse(code = 403, body = """{"code":"INVALID_PASSWORD","message":"Wrong password"}"""))
        val e = (repo.deleteAccount("nope", "k1") as ApiResult.Failure).error as ApiError.Http
        assertEquals(403, e.status)
        assertEquals("INVALID_PASSWORD", e.code)
    }

    @Test fun serverErrorIsAnHttpFailure() = runTest {
        server.enqueue(MockResponse(code = 503, body = ""))
        assertEquals(503, ((repo.deleteAccount("p", "k1") as ApiResult.Failure).error as ApiError.Http).status)
    }

    @Test fun offlineIsNetwork() = runTest {
        server.close()
        assertEquals(ApiError.Network, (repo.deleteAccount("p", "k1") as ApiResult.Failure).error)
    }

    @Test fun exportReturnsTheRawJson() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"exportedAt":"2026-10-21T11:30:00Z","user":{"id":"u1"}}"""))
        val body = (repo.exportJson() as ApiResult.Success).value
        assertTrue(body.startsWith("""{"exportedAt""""))
        assertEquals("/api/v1/me/export", server.takeRequest().url.encodedPath)
    }
}
