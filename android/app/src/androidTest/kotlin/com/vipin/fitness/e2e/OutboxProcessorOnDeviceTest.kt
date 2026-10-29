package com.vipin.fitness.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** ART uses ICU regex, which is stricter than the JVM one Robolectric tests run on. */
@RunWith(AndroidJUnit4::class)
class OutboxProcessorOnDeviceTest {
    @Test fun outboxProcessorClassInitialisesOnArt() {
        val c = Class.forName("com.vipin.fitness.core.data.sync.OutboxProcessor")
        val companion = c.getDeclaredField("Companion").get(null)
        val ref = companion.javaClass.getMethod("sessionRef", String::class.java).invoke(companion, "abc")
        assertEquals("{session:abc}", ref)
    }

    @Test fun sessionRefPatternCompilesOnArt() {
        val m = Regex("""\{session:([^}]+)}""").find("workout-sessions/{session:abc}/sets")
        assertEquals("abc", m?.groupValues?.get(1))
    }
}
