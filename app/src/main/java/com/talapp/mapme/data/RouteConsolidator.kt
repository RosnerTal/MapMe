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
    private const val CELL_SIZE = 0.0005 // ~50 meters spatial grid
    const val DEFAULT_THRESHOLD_METERS = 22.0

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
     */
    fun consolidate(
        routes: List<Triple<Walk, List<WalkPoint>, Boolean>>,
        thresholdMeters: Double = DEFAULT_THRESHOLD_METERS
    ): List<ConsolidatedPolyline> {
        val driveRoutes = routes.filter { it.third }
        val walkRoutes = routes.filter { !it.third }

        val result = mutableListOf<ConsolidatedPolyline>()
        result.addAll(consolidateGroup(driveRoutes, isDrive = true, thresholdMeters))
        result.addAll(consolidateGroup(walkRoutes, isDrive = false, thresholdMeters))
        return result
    }

    private fun consolidateGroup(
        routes: List<Triple<Walk, List<WalkPoint>, Boolean>>,
        isDrive: Boolean,
        thresholdMeters: Double
    ): List<ConsolidatedPolyline> {
        val index = SpatialIndex()
        val polylines = mutableListOf<ConsolidatedPolyline>()

        for ((walk, pts, _) in routes) {
            if (pts.size < 2) continue

            var currentSegmentList = mutableListOf<WalkPoint>()

            for (i in 0 until pts.size - 1) {
                val p1 = pts[i]
                val p2 = pts[i + 1]
                val midLat = (p1.latitude + p2.latitude) / 2.0
                val midLon = (p1.longitude + p2.longitude) / 2.0

                val isCovered = index.isPointNearAny(midLat, midLon, thresholdMeters)

                if (!isCovered) {
                    val seg = Segment(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
                    index.insert(seg)

                    if (currentSegmentList.isEmpty()) {
                        currentSegmentList.add(p1)
                    }
                    currentSegmentList.add(p2)
                } else {
                    if (currentSegmentList.size >= 2) {
                        polylines.add(ConsolidatedPolyline(currentSegmentList, isDrive, walk))
                    }
                    currentSegmentList = mutableListOf()
                }
            }

            if (currentSegmentList.size >= 2) {
                polylines.add(ConsolidatedPolyline(currentSegmentList, isDrive, walk))
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
