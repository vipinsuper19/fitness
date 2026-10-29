package com.vipin.fitness.core.data.xp

import com.vipin.fitness.core.network.ApiError
import com.vipin.fitness.core.network.ApiResult
import com.vipin.fitness.core.network.XpLedgerApi
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

class XpLedgerRepositoryTest {
    private val server = MockWebServer()
    private val json = fitnessJson()
    private lateinit var repo: DefaultXpLedgerRepository

    private fun tx(id: String, type: String, amount: Int) =
        """{"id":"$id","sourceType":"$type","sourceId":"s-$id","amount":$amount,"reason":"r $id","createdAt":"2026-10-20T06:30:00Z"}"""

    @Before fun setUp() {
        server.start()
        repo = DefaultXpLedgerRepository(retrofit(server.url("/api/v1/").toString(), OkHttpClient(), json).create(XpLedgerApi::class.java), json)
    }

    @After fun tearDown() = server.close()

    @Test fun decodesEveryKnownTypeAndPages() = runTest {
        val items = listOf(
            tx("1", "workout_session", 50), tx("2", "adjustment", -50), tx("3", "badge", 25),
            tx("4", "challenge", 100), tx("5", "session_correction", -12),
        ).joinToString(",")
        server.enqueue(MockResponse(code = 200, body = """{"items":[$items],"nextCursor":"c2"}"""))
        val page = (repo.ledger("c1") as ApiResult.Success).value
        assertEquals(
            listOf(XpSourceType.WORKOUT_SESSION, XpSourceType.ADJUSTMENT, XpSourceType.BADGE, XpSourceType.CHALLENGE, XpSourceType.SESSION_CORRECTION),
            page.items.map { it.sourceType },
        )
        val corr = page.items.last()
        assertEquals(-12, corr.amount)
        assertEquals("s-5", corr.sourceId)
        assertEquals(Instant.parse("2026-10-20T06:30:00Z"), corr.createdAt)
        assertEquals("c2", page.nextCursor)
        val url = server.takeRequest().url
        assertEquals("/api/v1/xp/ledger", url.encodedPath)
        assertEquals("c1", url.queryParameter("cursor"))
        assertEquals("20", url.queryParameter("limit"))
    }

    @Test fun unknownSourceTypeAndExtraFieldsDecodeAsUnknown() = runTest {
        server.enqueue(MockResponse(code = 200, body = """{"items":[{"id":"9","sourceType":"streak_bonus","sourceId":"x","amount":5,"reason":"New","createdAt":"2026-10-20T06:30:00Z","extra":{"a":1}}],"nextCursor":null}"""))
        val e = (repo.ledger() as ApiResult.Success).value.items.single()
        assertEquals(XpSourceType.UNKNOWN, e.sourceType)
        assertEquals("streak_bonus", e.rawSourceType)
        assertEquals(5, e.amount)
        assertNull(server.takeRequest().url.queryParameter("cursor"))
    }

    @Test fun unparseableTimeIsNullNotACrash() {
        val e = com.vipin.fitness.core.network.model.XpTransactionDto("1", "badge", "b", 1, "", "yesterday").toDomain()
        assertNull(e.createdAt)
    }

    @Test fun httpErrorIsAFailure() = runTest {
        server.enqueue(MockResponse(code = 500, body = "{}"))
        val r = repo.ledger()
        assertTrue(r is ApiResult.Failure && r.error is ApiError.Http)
    }
}
