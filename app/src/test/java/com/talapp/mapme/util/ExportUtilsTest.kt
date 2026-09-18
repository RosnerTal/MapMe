package com.talapp.mapme.util

import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.data.WalkPoint
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportUtilsTest {

    @Test
    fun testGenerateGpxXml_structureAndEscaping() {
        val walk = Walk(
            id = 1L,
            title = "Morning Walk & Jog <Special>",
            startTime = 1700000000000L,
            endTime = 1700001000000L,
            totalDistanceMeters = 1200.0,
            totalDurationMillis = 1000000L,
            pointsJson = "[]"
        )
        val points = listOf(
            WalkPoint(latitude = 32.0853, longitude = 34.7818, speed = 1.4f, timestamp = 1700000010000L),
            WalkPoint(latitude = 32.0860, longitude = 34.7825, speed = 1.6f, timestamp = 1700000020000L)
        )
        val pois = listOf(
            WalkPoi(latitude = 32.0855, longitude = 34.7820, text = "Park Bench & Tree <3", timestamp = 1700000015000L)
        )

        val gpx = ExportUtils.generateGpxXml(walk, points, pois)

        // Verify XML header and root element
        assertTrue(gpx.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(gpx.contains("<gpx version=\"1.1\" creator=\"MapMe\""))

        // Verify XML escaping of title and POI text
        assertTrue(gpx.contains("Morning Walk &amp; Jog &lt;Special&gt;"))
        assertTrue(gpx.contains("Park Bench &amp; Tree &lt;3"))

        // Verify Waypoints
        assertTrue(gpx.contains("<wpt lat=\"32.0855\" lon=\"34.782\">"))

        // Verify Trackpoints
        assertTrue(gpx.contains("<trkpt lat=\"32.0853\" lon=\"34.7818\">"))
        assertTrue(gpx.contains("<speed>1.4</speed>"))
        assertTrue(gpx.contains("<trkpt lat=\"32.086\" lon=\"34.7825\">"))

        // Verify Closing tag
        assertTrue(gpx.endsWith("</gpx>\n"))
    }

    @Test
    fun testGenerateKmlXml_structureAndCoordinates() {
        val walk = Walk(
            id = 2L,
            title = "Sunset Drive <Fast>",
            startTime = 1700000000000L,
            endTime = 1700001000000L,
            totalDistanceMeters = 8500.0,
            totalDurationMillis = 600000L,
            pointsJson = "[]"
        )
        val points = listOf(
            WalkPoint(latitude = 32.1000, longitude = 34.8000, speed = 12.0f, timestamp = 1700000010000L),
            WalkPoint(latitude = 32.1050, longitude = 34.8050, speed = 15.0f, timestamp = 1700000020000L)
        )
        val pois = listOf(
            WalkPoi(latitude = 32.1010, longitude = 34.8010, text = "Gas Station", timestamp = 1700000012000L)
        )

        val kml = ExportUtils.generateKmlXml(walk, points, pois)

        // Verify XML header and KML structure
        assertTrue(kml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(kml.contains("<kml xmlns=\"http://www.opengis.net/kml/2.2\">"))

        // Verify Title and style
        assertTrue(kml.contains("Sunset Drive &lt;Fast&gt;"))
        assertTrue(kml.contains("<LineString>"))

        // Verify Coordinate formatting: lon,lat,altitude
        assertTrue(kml.contains("34.8,32.1,0 34.805,32.105,0"))

        // Verify Placemark for POI
        assertTrue(kml.contains("<Placemark>"))
        assertTrue(kml.contains("<name>Gas Station</name>"))
        assertTrue(kml.contains("<coordinates>34.801,32.101,0</coordinates>"))

        // Verify Closing tag
        assertTrue(kml.endsWith("</kml>\n"))
    }
}
