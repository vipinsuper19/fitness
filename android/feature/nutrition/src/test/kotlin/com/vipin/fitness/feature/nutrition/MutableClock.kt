package com.vipin.fitness.feature.nutrition

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Test clock that can be moved forward, e.g. across midnight in India. */
class MutableClock(var instant: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = Delegate(zone)
    override fun instant(): Instant = instant

    private inner class Delegate(private val z: ZoneId) : Clock() {
        override fun getZone(): ZoneId = z
        override fun withZone(zone: ZoneId): Clock = Delegate(zone)
        override fun instant(): Instant = this@MutableClock.instant
    }
}
