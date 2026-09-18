package com.talapp.mapme.util

import java.util.Locale

/**
 * Common formatting utilities for MapMe GPS data and statistics.
 */
object FormatUtils {

    /**
     * Formats milliseconds into standard digital duration display:
     * - "hh:mm:ss" for >= 1 hour
     * - "mm:ss" for < 1 hour
     */
    fun formatTime(millis: Long): String {
        if (millis <= 0) return "00:00"
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats meters into human-readable distance:
     * - "450 m" for < 1000 meters
     * - "5.25 km" for >= 1000 meters
     */
    fun formatDistance(meters: Double): String {
        if (meters <= 0.0) return "0 m"
        return if (meters < 1000.0) {
            String.format(Locale.US, "%.0f m", meters)
        } else {
            String.format(Locale.US, "%.2f km", meters / 1000.0)
        }
    }

    /**
     * Converts meters per second to kilometers per hour string:
     * - e.g. 10.0 m/s -> "36.0 km/h"
     */
    fun formatSpeed(metersPerSec: Float): String {
        val safeMps = if (metersPerSec < 0f) 0f else metersPerSec
        val kmh = safeMps * 3.6f
        return String.format(Locale.US, "%.1f km/h", kmh)
    }
}

// Top-level delegating functions for convenient imports
fun formatTime(millis: Long): String = FormatUtils.formatTime(millis)
fun formatDistance(meters: Double): String = FormatUtils.formatDistance(meters)
fun formatSpeed(metersPerSec: Float): String = FormatUtils.formatSpeed(metersPerSec)
