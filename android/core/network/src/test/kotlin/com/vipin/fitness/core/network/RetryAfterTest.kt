package com.vipin.fitness.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RetryAfterTest {
    private val now = Instant.parse("2026-10-29T09:00:00Z").toEpochMilli()

    @Test fun secondsAndHttpDates() {
        assertEquals(30_000L, parseRetryAfter("30", now))
        assertEquals(0L, parseRetryAfter("0", now))
        assertEquals(90_000L, parseRetryAfter("Thu, 29 Oct 2026 09:01:30 GMT", now))
        assertEquals(0L, parseRetryAfter("Thu, 29 Oct 2026 08:00:00 GMT", now)) // already passed
    }

    @Test fun missingOrBadIsNull() {
        assertNull(parseRetryAfter(null, now))
        assertNull(parseRetryAfter(" ", now))
        assertNull(parseRetryAfter("-5", now))
        assertNull(parseRetryAfter("soon", now))
    }

    @Test fun rateLimitsAndServerErrorsAreRetryableValidationIsNot() {
        assertTrue(ApiError.Http(429, "RATE_LIMITED", "x", null).isRetryable)
        assertTrue(ApiError.Http(503, "UNAVAILABLE", "x", null).isRetryable)
        assertTrue(ApiError.Network.isRetryable)
        assertFalse(ApiError.Http(400, "VALIDATION_FAILED", "x", null).isRetryable)
        assertFalse(ApiError.Http(409, "IDEMPOTENCY_KEY_REUSED", "x", null).isRetryable)
    }
}

class RetryAfterDefaultTest {
    @Test fun a429WithoutAUsableHeaderWaits30sOthersStayNull() {
        assertEquals(30_000L, DEFAULT_RETRY_AFTER_MS)
        assertEquals(30_000L, retryAfterFor(429, null, 0))
        assertEquals(30_000L, retryAfterFor(429, "soon", 0))
        assertEquals(7_000L, retryAfterFor(429, "7", 0))
        assertEquals(0L, retryAfterFor(429, "0", 0))
        assertNull(retryAfterFor(503, null, 0))
        assertEquals(2_000L, retryAfterFor(503, "2", 0))
    }
}
