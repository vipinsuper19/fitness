package com.vipin.fitness.core.common.time

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * The app's calendar zone (India first). The injected [Clock] is UTC; anything that asks "what
 * day is it" must go through [today], or between 00:00 and 05:30 IST it gets yesterday.
 */
val APP_ZONE: ZoneId = ZoneId.of("Asia/Kolkata")

fun Clock.today(): LocalDate = LocalDate.now(this.withZone(APP_ZONE))
