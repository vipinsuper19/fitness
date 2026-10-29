package com.vipin.fitness.feature.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vipin.fitness.core.data.NotificationType
import com.vipin.fitness.core.data.QuietHours
import com.vipin.fitness.core.testing.testNotificationPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationFormatTest {
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test fun validTimesMatchTheContract() {
        listOf("00:00", "07:00", "23:59", "19:30").forEach { assertTrue(it, NotificationFormat.isValidTime(it)) }
        listOf("24:00", "7:00", "07:60", "07:0", "", "ab:cd").forEach { assertFalse(it, NotificationFormat.isValidTime(it)) }
    }

    @Test fun timeIsZeroPadded() {
        assertEquals("07:05", NotificationFormat.time(7, 5))
        assertEquals("00:00", NotificationFormat.time(0, 0))
        assertEquals("23:59", NotificationFormat.time(23, 59))
        assertEquals(7 to 5, NotificationFormat.parse("07:05"))
        assertNull(NotificationFormat.parse("7:05"))
    }

    @Test(expected = IllegalArgumentException::class) fun outOfRangeTimeThrows() {
        NotificationFormat.time(24, 0)
    }

    @Test fun windowLengthHandlesMidnight() {
        assertEquals(9 * 60, NotificationFormat.windowMinutes("22:00", "07:00"))
        assertEquals(120, NotificationFormat.windowMinutes("13:00", "15:00"))
        assertEquals(1, NotificationFormat.windowMinutes("23:59", "00:00"))
        assertEquals(0, NotificationFormat.windowMinutes("07:00", "07:00"))
        assertTrue(NotificationFormat.crossesMidnight("22:00", "07:00"))
        assertFalse(NotificationFormat.crossesMidnight("13:00", "15:00"))
    }

    @Test fun durationsUsePlurals() {
        assertEquals("1 hour", NotificationFormat.duration(res, 60))
        assertEquals("9 hours", NotificationFormat.duration(res, 540))
        assertEquals("1 minute", NotificationFormat.duration(res, 1))
        assertEquals("1 hour 30 minutes", NotificationFormat.duration(res, 90))
        assertEquals("2 hours 1 minute", NotificationFormat.duration(res, 121))
    }

    @Test fun windowText() {
        assertEquals("22:00 – 07:00 next day (9 hours)", NotificationFormat.window(res, QuietHours(true, "22:00", "07:00")))
        assertEquals("13:00 – 15:00 (2 hours)", NotificationFormat.window(res, QuietHours(true, "13:00", "15:00")))
    }

    @Test fun typesOnCountsKnownTypes() {
        assertEquals("5 of 7 on", NotificationFormat.typesOn(res, testNotificationPreferences()))
        val one = testNotificationPreferences(types = mapOf(NotificationType.BADGE_EARNED to true, NotificationType.WATER_REMINDER to false))
        assertEquals("1 of 2 on", NotificationFormat.typesOn(res, one))
    }

    @Test fun everyTypeAndErrorHasALabel() {
        NotificationType.entries.forEach { assertTrue(NotificationFormat.label(res, it).isNotBlank()) }
        NotificationError.entries.forEach { assertTrue(NotificationFormat.error(res, it).isNotBlank()) }
        assertEquals("Water reminders", NotificationFormat.label(res, NotificationType.WATER_REMINDER))
    }
}
