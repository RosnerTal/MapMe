package com.talapp.mapme.data

import kotlin.math.*

/**
 * Display-only consolidated polyline representation.
 */
data class ConsolidatedPolyline(
    val points: List<WalkPoint>,
    val isDrive: Boolean,
    val sourceWalk: Walk? = null
)

/**
 * Spatial corridor deduplication engine.
 * Consolidates close/repeated tracks of the same travel mode into a single clean line
 * without altering or deleting any raw database records.
 */
object RouteConsolidator {
    private const val CELL_SIZE = 0.001 // ~100 meters spatial grid
    const val DEFAULT_CORRIDOR_METERS = 35.0
    const val DEFAULT_OVERLAP_THRESHOLD = 0.75

    private class Segment(
        val lat1: Double,
        val lon1: Double,
        val lat2: Double,
        val lon2: Double
    ) {
        val midLat = (lat1 + lat2) / 2.0
        val midLon = (lon1 + lon2) / 2.0
    }

    private class SpatialIndex {
        private val grid = mutableMapOf<Long, MutableList<Segment>>()

        private fun cellKey(lat: Double, lon: Double): Long {
            val cx = floor(lon / CELL_SIZE).toLong()
            val cy = floor(lat / CELL_SIZE).toLong()
            return (cx shl 32) xor (cy and 0xFFFFFFFFL)
        }

        fun insert(seg: Segment) {
            val k = cellKey(seg.midLat, seg.midLon)
            grid.getOrPut(k) { mutableListOf() }.add(seg)
        }

        fun isPointNearAny(lat: Double, lon: Double, thresholdMeters: Double): Boolean {
            val cx = floor(lon / CELL_SIZE).toInt()
            val cy = floor(lat / CELL_SIZE).toInt()

            for (dx in -1..1) {
                for (dy in -1..1) {
                    val k = ((cx + dx).toLong() shl 32) xor ((cy + dy).toLong() and 0xFFFFFFFFL)
                    val list = grid[k] ?: continue
                    for (seg in list) {
                        val d = pointToSegmentDistanceMeters(lat, lon, seg.lat1, seg.lon1, seg.lat2, seg.lon2)
                        if (d <= thresholdMeters) {
                            return true
                        }
                    }
                }
            }
            return false
        }
    }

    /**
     * Consolidate a list of parsed walks into clean non-redundant polylines.
     * Walks and Drives are processed independently so they never merge together.
     * All output polylines are full continuous lines (never chopped into dots).
     */
    fun consolidate(
        routes: List<Triple<Walk, List<WalkPoint>, Boolean>>,
        overlapThreshold: Double = DEFAULT_OVERLAP_THRESHOLD,
        corridorMeters: Double = DEFAULT_CORRIDOR_METERS
    ): List<ConsolidatedPolyline> {
        val driveRoutes = routes.filter { it.third }
        val walkRoutes = routes.filter { !it.third }

        val result = mutableListOf<ConsolidatedPolyline>()
        result.addAll(consolidateGroup(driveRoutes, isDrive = true, overlapThreshold, corridorMeters))
        result.addAll(consolidateGroup(walkRoutes, isDrive = false, overlapThreshold, corridorMeters))
        return result
    }

    private fun consolidateGroup(
        routes: List<Triple<Walk, List<WalkPoint>, Boolean>>,
        isDrive: Boolean,
        overlapThreshold: Double,
        corridorMeters: Double
    ): List<ConsolidatedPolyline> {
        // Sort routes by number of points descending so the longest/most complete routes become the baseline representatives
        val validRoutes = routes.filter { it.second.size >= 2 }.sortedByDescending { it.second.size }
        val index = SpatialIndex()
        val polylines = mutableListOf<ConsolidatedPolyline>()

        for ((walk, pts, _) in validRoutes) {
            if (polylines.isEmpty()) {
                // First route is always accepted as a primary representative
                polylines.add(ConsolidatedPolyline(pts, isDrive, walk))
                for (i in 0 until pts.size - 1) {
                    index.insert(Segment(pts[i].latitude, pts[i].longitude, pts[i + 1].latitude, pts[i + 1].longitude))
                }
                continue
            }

            // Check how much of pts is covered by already-accepted routes
            val sampleStep = (pts.size / 100).coerceAtLeast(1)
            var totalSamples = 0
            var coveredSamples = 0

            for (i in pts.indices step sampleStep) {
                val pt = pts[i]
                totalSamples++
                if (index.isPointNearAny(pt.latitude, pt.longitude, corridorMeters)) {
                    coveredSamples++
                }
            }

            val coverageRatio = if (totalSamples > 0) coveredSamples.toDouble() / totalSamples else 0.0

            // If this route is largely covered by existing displayed routes (>= overlapThreshold),
            // it is a "close route" along the same location/corridor. Suppress it in the background view
            // to show only one clean route instead of overlapping duplicates.
            if (coverageRatio >= overlapThreshold) {
                continue
            }

            // This route introduces a distinct path or new location!
            // Add the FULL, UNCUT, CONTINUOUS polyline so it renders as a smooth line, never dots.
            polylines.add(ConsolidatedPolyline(pts, isDrive, walk))
            for (i in 0 until pts.size - 1) {
                index.insert(Segment(pts[i].latitude, pts[i].longitude, pts[i + 1].latitude, pts[i + 1].longitude))
            }
        }

        return polylines
    }

    /**
     * Fast perpendicular Euclidean distance in meters from point P to segment AB.
     */
    fun pointToSegmentDistanceMeters(
        latP: Double, lonP: Double,
        latA: Double, lonA: Double,
        latB: Double, lonB: Double
    ): Double {
        val latMid = (latA + latB) / 2.0
        val mPerDegLat = 110852.0
        val mPerDegLon = 111320.0 * cos(Math.toRadians(latMid))

        val xA = 0.0
        val yA = 0.0
        val xB = (lonB - lonA) * mPerDegLon
        val yB = (latB - latA) * mPerDegLat
        val xP = (lonP - lonA) * mPerDegLon
        val yP = (latP - latA) * mPerDegLat

        val dx = xB - xA
        val dy = yB - yA
        val segLenSq = dx * dx + dy * dy

        if (segLenSq < 1e-6) {
            val distSq = xP * xP + yP * yP
            return sqrt(distSq)
        }

        val t = ((xP - xA) * dx + (yP - yA) * dy) / segLenSq
        val clampedT = t.coerceIn(0.0, 1.0)

        val projX = xA + clampedT * dx
        val projY = yA + clampedT * dy

        val distX = xP - projX
        val distY = yP - projY
        return sqrt(distX * distX + distY * distY)
    }
}
