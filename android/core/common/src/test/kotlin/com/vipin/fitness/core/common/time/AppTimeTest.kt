package com.vipin.fitness.core.common.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class AppTimeTest {
    @Test fun utcEveningIsNextDayInIndia() {
        val clock = Clock.fixed(Instant.parse("2026-10-20T20:00:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 21), clock.today())
    }

    @Test fun justBeforeIstMidnight() {
        val clock = Clock.fixed(Instant.parse("2026-10-20T18:29:59Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 20), clock.today())
    }
}
