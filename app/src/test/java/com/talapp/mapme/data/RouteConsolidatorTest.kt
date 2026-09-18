package com.talapp.mapme.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteConsolidatorTest {

    private fun createWalk(id: Long, title: String): Walk {
        return Walk(
            id = id,
            title = title,
            startTime = 1700000000000L + id * 1000,
            endTime = 1700001000000L + id * 1000,
            totalDistanceMeters = 500.0,
            totalDurationMillis = 500000L,
            pointsJson = "[]"
        )
    }

    @Test
    fun testConsolidate_empty() {
        val result = RouteConsolidator.consolidate(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun testConsolidate_modesAreSeparated() {
        // Same route path, but one is a Walk (false) and one is a Drive (true)
        val walk = createWalk(1L, "Walk on Main St")
        val drive = createWalk(2L, "Drive on Main St")

        val path = listOf(
            WalkPoint(latitude = 32.0800, longitude = 34.7800, timestamp = 1000L, speed = 1.4f),
            WalkPoint(latitude = 32.0810, longitude = 34.7810, timestamp = 2000L, speed = 1.4f),
            WalkPoint(latitude = 32.0820, longitude = 34.7820, timestamp = 3000L, speed = 1.4f),
            WalkPoint(latitude = 32.0830, longitude = 34.7830, timestamp = 4000L, speed = 1.4f)
        )

        val routes = listOf(
            Triple(walk, path, false),
            Triple(drive, path, true)
        )

        val consolidated = RouteConsolidator.consolidate(routes)

        // Both walk and drive should be present independently
        assertEquals(2, consolidated.size)
        assertTrue(consolidated.any { it.isDrive })
        assertTrue(consolidated.any { !it.isDrive })
    }

    @Test
    fun testConsolidate_duplicateRouteMerged() {
        val walk1 = createWalk(1L, "First Walk")
        val walk2 = createWalk(2L, "Repeat Walk")

        val path1 = listOf(
            WalkPoint(latitude = 32.0800, longitude = 34.7800, timestamp = 1000L, speed = 1.4f),
            WalkPoint(latitude = 32.0810, longitude = 34.7810, timestamp = 2000L, speed = 1.4f),
            WalkPoint(latitude = 32.0820, longitude = 34.7820, timestamp = 3000L, speed = 1.4f),
            WalkPoint(latitude = 32.0830, longitude = 34.7830, timestamp = 4000L, speed = 1.4f)
        )

        // Walk 2 has almost identical coordinates (within 5 meters)
        val path2 = listOf(
            WalkPoint(latitude = 32.08001, longitude = 34.78001, timestamp = 5000L, speed = 1.4f),
            WalkPoint(latitude = 32.08101, longitude = 34.78101, timestamp = 6000L, speed = 1.4f),
            WalkPoint(latitude = 32.08201, longitude = 34.78201, timestamp = 7000L, speed = 1.4f),
            WalkPoint(latitude = 32.08301, longitude = 34.78301, timestamp = 8000L, speed = 1.4f)
        )

        val routes = listOf(
            Triple(walk1, path1, false),
            Triple(walk2, path2, false)
        )

        val consolidated = RouteConsolidator.consolidate(routes)

        // Duplicate walk along same corridor should consolidate into 1 representative
        assertEquals(1, consolidated.size)
    }

    @Test
    fun testConsolidate_distinctRoutesBothPreserved() {
        val walk1 = createWalk(1L, "Downtown Walk")
        val walk2 = createWalk(2L, "Uptown Walk")

        // Downtown
        val path1 = listOf(
            WalkPoint(latitude = 32.0800, longitude = 34.7800, timestamp = 1000L, speed = 1.4f),
            WalkPoint(latitude = 32.0810, longitude = 34.7810, timestamp = 2000L, speed = 1.4f)
        )

        // Uptown (far away, ~10km north)
        val path2 = listOf(
            WalkPoint(latitude = 32.1800, longitude = 34.8800, timestamp = 3000L, speed = 1.4f),
            WalkPoint(latitude = 32.1810, longitude = 34.8810, timestamp = 4000L, speed = 1.4f)
        )

        val routes = listOf(
            Triple(walk1, path1, false),
            Triple(walk2, path2, false)
        )

        val consolidated = RouteConsolidator.consolidate(routes)

        // Both distinct routes must be preserved
        assertEquals(2, consolidated.size)
    }
}
