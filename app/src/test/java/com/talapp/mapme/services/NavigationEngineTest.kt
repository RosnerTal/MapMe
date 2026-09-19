package com.talapp.mapme.services

import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.*
import org.junit.Test

class NavigationEngineTest {

    @Test
    fun testPointToSegmentDistance_pointOnSegment() {
        // Line segment from (32.0, 34.0) to (32.0, 34.02)
        // Midpoint at (32.0, 34.01)
        val dist = NavigationEngine.pointToSegmentDistanceMeters(
            32.0, 34.01,
            32.0, 34.0,
            32.0, 34.02
        )
        // Should be approximately 0 meters
        assertEquals(0.0, dist, 1.0)
    }

    @Test
    fun testPointToSegmentDistance_pointOffSegment() {
        // Segment running along latitude 32.0, longitude from 34.0 to 34.01
        // Point slightly north (~111 meters is 0.001 deg lat)
        val dist = NavigationEngine.pointToSegmentDistanceMeters(
            32.001, 34.005,
            32.0, 34.0,
            32.0, 34.01
        )
        // 0.001 degree of latitude is roughly 111 meters
        assertTrue("Distance should be around 111m but was $dist", dist in 100.0..125.0)
    }

    @Test
    fun testIsOffRoute_onRoute() {
        val waypoints = listOf(
            Pair(32.0800, 34.7800),
            Pair(32.0850, 34.7800),
            Pair(32.0900, 34.7850)
        )

        // Point is right on the first segment
        val offRoute = NavigationEngine.isOffRoute(
            currentLat = 32.0820,
            currentLon = 34.7800,
            waypoints = waypoints,
            thresholdMeters = 35.0
        )
        assertFalse("Should not be off route when right on path", offRoute)
    }

    @Test
    fun testIsOffRoute_withinCorridor() {
        val waypoints = listOf(
            Pair(32.0800, 34.7800),
            Pair(32.0850, 34.7800)
        )

        // Point is ~10-15m to the side (0.0001 deg longitude at lat 32 is ~9.4m)
        val offRoute = NavigationEngine.isOffRoute(
            currentLat = 32.0825,
            currentLon = 34.7801,
            waypoints = waypoints,
            thresholdMeters = 35.0
        )
        assertFalse("Should be within the 35m corridor", offRoute)
    }

    @Test
    fun testIsOffRoute_outsideThreshold() {
        val waypoints = listOf(
            Pair(32.0800, 34.7800),
            Pair(32.0850, 34.7800)
        )

        // Point is far away (0.005 deg is ~500m)
        val offRoute = NavigationEngine.isOffRoute(
            currentLat = 32.0825,
            currentLon = 34.7850,
            waypoints = waypoints,
            thresholdMeters = 35.0
        )
        assertTrue("Should be detected as off route", offRoute)
    }

    @Test
    fun testIsOffRoute_insufficientWaypoints() {
        val waypoints = listOf(Pair(32.0800, 34.7800))
        val offRoute = NavigationEngine.isOffRoute(
            currentLat = 32.0820,
            currentLon = 34.7800,
            waypoints = waypoints,
            thresholdMeters = 35.0
        )
        assertFalse("Insufficient waypoints cannot be considered off route", offRoute)
    }

    @Test
    fun testMapManeuver() {
        assertEquals(Maneuver.TYPE_DESTINATION, NavigationEngine.mapManeuver("arrive", null))
        assertEquals(Maneuver.TYPE_DEPART, NavigationEngine.mapManeuver("depart", null))
        assertEquals(Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, NavigationEngine.mapManeuver("roundabout", null))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, NavigationEngine.mapManeuver("turn", "left"))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, NavigationEngine.mapManeuver("turn", "right"))
        assertEquals(Maneuver.TYPE_TURN_SHARP_LEFT, NavigationEngine.mapManeuver("turn", "sharp left"))
        assertEquals(Maneuver.TYPE_TURN_SHARP_RIGHT, NavigationEngine.mapManeuver("turn", "sharp right"))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_LEFT, NavigationEngine.mapManeuver("turn", "slight left"))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_RIGHT, NavigationEngine.mapManeuver("turn", "slight right"))
        assertEquals(Maneuver.TYPE_U_TURN_LEFT, NavigationEngine.mapManeuver("turn", "uturn"))
    }

    @Test
    fun testGenerateCue_walkingVsDriving() {
        val walkCue = NavigationEngine.generateCue(
            type = "depart",
            modifier = null,
            roadName = "Dizengoff St",
            destinationTitle = "Beach",
            exitNumber = 0,
            mode = NavMode.WALKING
        )
        assertTrue("Walk cue should start with 'Walk'", walkCue.startsWith("Walk toward"))

        val driveCue = NavigationEngine.generateCue(
            type = "depart",
            modifier = null,
            roadName = "Ayalon Highway",
            destinationTitle = "Office",
            exitNumber = 0,
            mode = NavMode.DRIVING
        )
        assertTrue("Drive cue should start with 'Drive'", driveCue.startsWith("Drive toward"))
    }

    @Test
    fun testGenerateCue_arrive() {
        val cue = NavigationEngine.generateCue(
            type = "arrive",
            modifier = null,
            roadName = "",
            destinationTitle = "Eiffel Tower",
            exitNumber = 0,
            mode = NavMode.WALKING
        )
        assertEquals("Arrive at Eiffel Tower", cue)
    }

    @Test
    fun testGenerateCue_turns() {
        val leftCue = NavigationEngine.generateCue(
            type = "turn",
            modifier = "left",
            roadName = "Main St",
            destinationTitle = "Park",
            exitNumber = 0
        )
        assertEquals("Turn left onto Main St", leftCue)

        val uTurnCue = NavigationEngine.generateCue(
            type = "turn",
            modifier = "uturn",
            roadName = "Broadway",
            destinationTitle = "Park",
            exitNumber = 0
        )
        assertEquals("Make a U-turn onto Broadway", uTurnCue)
    }

    @Test
    fun testGenerateCue_roundabout() {
        val cueWithExit = NavigationEngine.generateCue(
            type = "roundabout",
            modifier = null,
            roadName = "Rotary Ave",
            destinationTitle = "",
            exitNumber = 3
        )
        assertEquals("At roundabout, take exit 3 onto Rotary Ave", cueWithExit)
    }
}
