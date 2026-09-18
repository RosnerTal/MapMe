package com.talapp.mapme.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.data.WalkPoi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Utilities for serializing and exporting recorded walks to standard GPX 1.1 and KML 2.2 formats.
 */
object ExportUtils {

    /**
     * Generates a valid GPX 1.1 XML string from a recorded walk, path points, and waypoints.
     */
    fun generateGpxXml(walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val safeWalkTitle = walk.title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"MapMe\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>$safeWalkTitle</name>\n")
        sb.append("    <time>${isoFormat.format(Date(walk.startTime))}</time>\n")
        sb.append("  </metadata>\n")

        for (poi in pois) {
            val name = (poi.text ?: "Waypoint").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("  <wpt lat=\"${poi.latitude}\" lon=\"${poi.longitude}\">\n")
            sb.append("    <name>$name</name>\n")
            sb.append("    <time>${isoFormat.format(Date(poi.timestamp))}</time>\n")
            sb.append("  </wpt>\n")
        }

        sb.append("  <trk>\n")
        sb.append("    <name>$safeWalkTitle</name>\n")
        sb.append("    <trkseg>\n")
        for (pt in points) {
            sb.append("      <trkpt lat=\"${pt.latitude}\" lon=\"${pt.longitude}\">\n")
            sb.append("        <time>${isoFormat.format(Date(pt.timestamp))}</time>\n")
            sb.append("        <speed>${pt.speed}</speed>\n")
            sb.append("      </trkpt>\n")
        }
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>\n")

        return sb.toString()
    }

    /**
     * Generates a valid KML 2.2 XML string from a recorded walk, path points, and waypoints.
     */
    fun generateKmlXml(walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>): String {
        val safeWalkTitle = walk.title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n")
        sb.append("  <Document>\n")
        sb.append("    <name>$safeWalkTitle</name>\n")
        sb.append("    <Style id=\"routeLine\">\n")
        sb.append("      <LineStyle>\n")
        sb.append("        <color>ff00ffff</color>\n") // AABBGGRR: cyan
        sb.append("        <width>5</width>\n")
        sb.append("      </LineStyle>\n")
        sb.append("    </Style>\n")

        // Waypoints (Placemarks)
        for ((idx, poi) in pois.withIndex()) {
            val name = (poi.text ?: "POI #${idx + 1}").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            sb.append("    <Placemark>\n")
            sb.append("      <name>$name</name>\n")
            sb.append("      <Point>\n")
            sb.append("        <coordinates>${poi.longitude},${poi.latitude},0</coordinates>\n")
            sb.append("      </Point>\n")
            sb.append("    </Placemark>\n")
        }

        // Track Polyline
        if (points.isNotEmpty()) {
            sb.append("    <Placemark>\n")
            sb.append("      <name>Track: $safeWalkTitle</name>\n")
            sb.append("      <styleUrl>#routeLine</styleUrl>\n")
            sb.append("      <LineString>\n")
            sb.append("        <tessellate>1</tessellate>\n")
            sb.append("        <coordinates>\n")
            val coordStr = points.joinToString(" ") { "${it.longitude},${it.latitude},0" }
            sb.append("          $coordStr\n")
            sb.append("        </coordinates>\n")
            sb.append("      </LineString>\n")
            sb.append("    </Placemark>\n")
        }

        sb.append("  </Document>\n")
        sb.append("</kml>\n")

        return sb.toString()
    }

    /**
     * Writes GPX XML to temporary cache file and launches Android share sheet.
     */
    fun exportGpx(context: Context, walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>) {
        try {
            val xml = generateGpxXml(walk, points, pois)
            val safeTitle = walk.title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val file = File(context.cacheDir, "${safeTitle}_${walk.id}.gpx")
            file.writeText(xml)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/gpx+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MapMe Route: ${walk.title}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export GPX Route"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Export error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Writes KML XML to temporary cache file and launches Android share sheet.
     */
    fun exportKml(context: Context, walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>) {
        try {
            val xml = generateKmlXml(walk, points, pois)
            val safeTitle = walk.title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val file = File(context.cacheDir, "${safeTitle}_${walk.id}.kml")
            file.writeText(xml)

            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.google-earth.kml+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "MapMe Route: ${walk.title}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Export KML Route"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(context, "Export error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// Top-level delegating functions for convenient imports
fun exportGpx(context: Context, walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>) {
    ExportUtils.exportGpx(context, walk, points, pois)
}

fun exportKml(context: Context, walk: Walk, points: List<WalkPoint>, pois: List<WalkPoi>) {
    ExportUtils.exportKml(context, walk, points, pois)
}
