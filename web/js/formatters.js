// ==========================================================================
// MapMe Web Dashboard - Formatting Utilities
// ==========================================================================

/**
 * Formats distance in meters to a clean human-readable string.
 * e.g., 450 -> "450 m", 5240 -> "5.24 km"
 */
export function formatDistance(meters) {
    if (!meters || meters <= 0) return "0 m";
    if (meters < 1000) {
        return `${Math.round(meters)} m`;
    }
    return `${(meters / 1000).toFixed(2)} km`;
}

/**
 * Formats duration milliseconds into digital clock format (matching Android FormatUtils):
 * - "hh:mm:ss" for >= 1 hour
 * - "mm:ss" for < 1 hour
 */
export function formatTime(millis) {
    if (!millis || millis <= 0) return "00:00";
    const totalSeconds = Math.floor(millis / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    const pad = (n) => n.toString().padStart(2, "0");

    if (hours > 0) {
        return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;
    }
    return `${pad(minutes)}:${pad(seconds)}`;
}

/**
 * Formats duration in milliseconds into short readable badges (e.g. "1h 24m" or "4m 12s").
 */
export function formatDurationBadge(millis) {
    if (!millis || millis <= 0) return "0s";
    const totalSeconds = Math.floor(millis / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;

    if (hours > 0) {
        return `${hours}h ${minutes}m`;
    }
    if (minutes > 0) {
        return `${minutes}m ${seconds}s`;
    }
    return `${seconds}s`;
}

/**
 * Converts meters per second to km/h string.
 * e.g., 10.0 -> "36.0 km/h"
 */
export function formatSpeed(metersPerSec) {
    if (!metersPerSec || metersPerSec <= 0) return "0.0 km/h";
    const kmh = metersPerSec * 3.6;
    return `${kmh.toFixed(1)} km/h`;
}

/**
 * Formats timestamp into readable date (e.g. "Friday, Sep 18, 2026 • 8:49 PM").
 */
export function formatDate(timestamp) {
    if (!timestamp) return "Unknown Date";
    const date = new Date(timestamp);
    return date.toLocaleDateString(undefined, {
        weekday: "short",
        month: "short",
        day: "numeric",
        year: "numeric",
        hour: "numeric",
        minute: "2-digit"
    });
}

/**
 * Formats timestamp into relative or compact date.
 */
export function formatCompactDate(timestamp) {
    if (!timestamp) return "";
    const date = new Date(timestamp);
    const now = new Date();
    const isToday = date.toDateString() === now.toDateString();
    
    const timeStr = date.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
    if (isToday) {
        return `Today • ${timeStr}`;
    }
    return `${date.toLocaleDateString(undefined, { month: "short", day: "numeric" })} • ${timeStr}`;
}
