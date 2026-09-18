package com.talapp.mapme.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun testFormatTime_zero() {
        val formatted = formatTime(0L)
        assertEquals("00:00", formatted)
    }

    @Test
    fun testFormatTime_underOneHour() {
        // 5 minutes, 32 seconds = 332 seconds = 332,000 ms
        val millis = (5 * 60 + 32) * 1000L
        assertEquals("05:32", formatTime(millis))
    }

    @Test
    fun testFormatTime_overOneHour() {
        // 2 hours, 14 minutes, 5 seconds
        val millis = (2 * 3600 + 14 * 60 + 5) * 1000L
        assertEquals("02:14:05", formatTime(millis))
    }

    @Test
    fun testFormatDistance_meters() {
        assertEquals("0 m", formatDistance(0.0))
        assertEquals("450 m", formatDistance(450.0))
        assertEquals("999 m", formatDistance(999.0))
    }

    @Test
    fun testFormatDistance_kilometers() {
        assertEquals("1.00 km", formatDistance(1000.0))
        assertEquals("5.25 km", formatDistance(5250.0))
        assertEquals("12.35 km", formatDistance(12345.0))
    }

    @Test
    fun testFormatSpeed() {
        // 0 m/s = 0.0 km/h
        assertEquals("0.0 km/h", formatSpeed(0f))
        // 10 m/s = 36.0 km/h
        assertEquals("36.0 km/h", formatSpeed(10f))
        // 1.5 m/s = 5.4 km/h (walking speed)
        assertEquals("5.4 km/h", formatSpeed(1.5f))
    }
}
