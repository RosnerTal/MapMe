package com.talapp.mapme.services

import android.util.Log
import androidx.car.app.navigation.model.Maneuver
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Data structures for Android Auto in-car turn-by-turn navigation.
 */
data class NavSearchResult(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

data class NavStep(
    val maneuverType: Int,
    val cue: String,
    val road: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val latitude: Double,
    val longitude: Double
)

data class NavRoute(
    val destinationTitle: String,
    val destinationLat: Double,
    val destinationLon: Double,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val waypoints: List<Pair<Double, Double>>, // (lat, lon) path coordinates
    val steps: List<NavStep>
)

/**
 * 100% Free, Zero-API-Key Navigation Engine:
 * - Search destinations using the Photon Geocoding API (OpenStreetMap-powered, multilingual).
 * - Driving route calculation using the OSRM Driving Engine (turn maneuvers, steps, distances, GeoJSON).
 */
object NavigationEngine {

    private const val TAG = "NavigationEngine"
    private const val USER_AGENT = "MapMe-AndroidAuto/4.7 (talapp.com)"

    /**
     * Search destinations worldwide or locally using Photon Geocoding API.
     * Biased towards the current vehicle location when available.
     */
    suspend fun searchDestinations(
        query: String,
        biasLat: Double? = null,
        biasLon: Double? = null,
        limit: Int = 8
    ): List<NavSearchResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext emptyList()

        try {
            var urlStr = "https://photon.komoot.io/api/?q=${URLEncoder.encode(trimmed, "UTF-8")}&limit=$limit"
            if (biasLat != null && biasLon != null) {
                urlStr += String.format(Locale.US, "&lat=%.5f&lon=%.5f", biasLat, biasLon)
            }

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 6000
            conn.readTimeout = 6000

            if (conn.responseCode != 200) {
                Log.w(TAG, "Photon HTTP error: ${conn.responseCode}")
                return@withContext emptyList()
            }

            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JsonParser.parseString(responseBody).asJsonObject
            val features = root.getAsJsonArray("features") ?: return@withContext emptyList()

            val results = mutableListOf<NavSearchResult>()
            for (elem in features) {
                val obj = elem.asJsonObject
                val geom = obj.getAsJsonObject("geometry") ?: continue
                val coords = geom.getAsJsonArray("coordinates") ?: continue
                if (coords.size() < 2) continue

                val lon = coords[0].asDouble
                val lat = coords[1].asDouble

                val props = obj.getAsJsonObject("properties") ?: continue
                val name = props.get("name")?.asString
                val street = props.get("street")?.asString
                val housenumber = props.get("housenumber")?.asString
                val city = props.get("city")?.asString
                val country = props.get("country")?.asString

                val title = when {
                    !name.isNullOrBlank() -> name
                    !street.isNullOrBlank() -> if (!housenumber.isNullOrBlank()) "$street $housenumber" else street
                    !city.isNullOrBlank() -> city
                    else -> "Destination"
                }

                val subtitleParts = mutableListOf<String>()
                if (!street.isNullOrBlank() && title != street) {
                    if (!housenumber.isNullOrBlank()) subtitleParts.add("$street $housenumber") else subtitleParts.add(street)
                }
                if (!city.isNullOrBlank() && title != city) subtitleParts.add(city)
                if (!country.isNullOrBlank()) subtitleParts.add(country)

                val subtitle = if (subtitleParts.isNotEmpty()) {
                    subtitleParts.joinToString(", ")
                } else {
                    "Location"
                }

                results.add(NavSearchResult(title, subtitle, lat, lon))
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Calculate optimal driving route from starting coordinate to destination coordinate using OSRM.
     * Returns full polyline points and turn-by-turn maneuvers mapped to Android Auto Maneuver types.
     */
    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        destLat: Double,
        destLon: Double,
        destinationTitle: String
    ): NavRoute? = withContext(Dispatchers.IO) {
        try {
            val urlStr = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%%20%.6f,%.6f?overview=full&geometries=geojson&steps=true",
                startLon, startLat, destLon, destLat
            ).replace("%20", "") // router accepts {lon1},{lat1};{lon2},{lat2}

            val conn = URL(
                String.format(
                    Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson&steps=true",
                    startLon, startLat, destLon, destLat
                )
            ).openConnection() as HttpURLConnection

            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8000
            conn.readTimeout = 8000

            if (conn.responseCode != 200) {
                Log.w(TAG, "OSRM HTTP error: ${conn.responseCode}")
                return@withContext null
            }

            val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val root = JsonParser.parseString(responseBody).asJsonObject
            val routes = root.getAsJsonArray("routes") ?: return@withContext null
            if (routes.size() == 0) return@withContext null

            val routeObj = routes[0].asJsonObject
            val totalDistance = routeObj.get("distance")?.asDouble ?: 0.0
            val totalDuration = routeObj.get("duration")?.asDouble ?: 0.0

            // Parse polyline waypoints: [[lon, lat], ...]
            val waypoints = mutableListOf<Pair<Double, Double>>()
            val geom = routeObj.getAsJsonObject("geometry")
            if (geom != null) {
                val coords = geom.getAsJsonArray("coordinates")
                if (coords != null) {
                    for (c in coords) {
                        val pt = c.asJsonArray
                        if (pt.size() >= 2) {
                            waypoints.add(Pair(pt[1].asDouble, pt[0].asDouble)) // (lat, lon)
                        }
                    }
                }
            }

            // Parse turn-by-turn steps
            val navSteps = mutableListOf<NavStep>()
            val legs = routeObj.getAsJsonArray("legs")
            if (legs != null && legs.size() > 0) {
                val steps = legs[0].asJsonObject.getAsJsonArray("steps")
                if (steps != null) {
                    for (s in steps) {
                        val sObj = s.asJsonObject
                        val stepDist = sObj.get("distance")?.asDouble ?: 0.0
                        val stepDur = sObj.get("duration")?.asDouble ?: 0.0
                        val roadName = sObj.get("name")?.asString ?: ""

                        val manObj = sObj.getAsJsonObject("maneuver")
                        val manTypeStr = manObj?.get("type")?.asString ?: "straight"
                        val manModifierStr = manObj?.get("modifier")?.asString
                        val exitNumber = manObj?.get("exit")?.asInt ?: 0

                        var stepLat = destLat
                        var stepLon = destLon
                        val locArr = manObj?.getAsJsonArray("location")
                        if (locArr != null && locArr.size() >= 2) {
                            stepLon = locArr[0].asDouble
                            stepLat = locArr[1].asDouble
                        }

                        val maneuverCode = mapManeuver(manTypeStr, manModifierStr)
                        val cueText = generateCue(manTypeStr, manModifierStr, roadName, destinationTitle, exitNumber)
                        val roadDisplay = if (roadName.isNotBlank()) roadName else destinationTitle

                        navSteps.add(
                            NavStep(
                                maneuverType = maneuverCode,
                                cue = cueText,
                                road = roadDisplay,
                                distanceMeters = stepDist,
                                durationSeconds = stepDur,
                                latitude = stepLat,
                                longitude = stepLon
                            )
                        )
                    }
                }
            }

            NavRoute(
                destinationTitle = destinationTitle,
                destinationLat = destLat,
                destinationLon = destLon,
                totalDistanceMeters = totalDistance,
                totalDurationSeconds = totalDuration,
                waypoints = waypoints,
                steps = navSteps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Route calculation failed: ${e.message}", e)
            null
        }
    }

    private fun mapManeuver(type: String, modifier: String?): Int {
        val t = type.lowercase()
        val m = modifier?.lowercase() ?: ""
        return when {
            t == "arrive" -> Maneuver.TYPE_DESTINATION
            t == "depart" -> Maneuver.TYPE_DEPART
            t == "roundabout" || t == "rotary" -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            t.contains("on ramp") || t == "ramp" -> when {
                m.contains("left") -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
                else -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            }
            t.contains("off ramp") -> when {
                m.contains("left") -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
                else -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
            }
            t == "fork" -> when {
                m.contains("left") -> Maneuver.TYPE_FORK_LEFT
                else -> Maneuver.TYPE_FORK_RIGHT
            }
            t == "merge" -> when {
                m.contains("left") -> Maneuver.TYPE_MERGE_LEFT
                else -> Maneuver.TYPE_MERGE_RIGHT
            }
            t == "turn" -> when {
                m == "sharp left" -> Maneuver.TYPE_TURN_SHARP_LEFT
                m == "sharp right" -> Maneuver.TYPE_TURN_SHARP_RIGHT
                m == "slight left" -> Maneuver.TYPE_TURN_SLIGHT_LEFT
                m == "slight right" -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
                m == "left" -> Maneuver.TYPE_TURN_NORMAL_LEFT
                m == "right" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
                m == "uturn" -> Maneuver.TYPE_U_TURN_LEFT
                m == "straight" -> Maneuver.TYPE_STRAIGHT
                else -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            }
            m == "uturn" -> Maneuver.TYPE_U_TURN_LEFT
            m.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT
            m.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
    }

    private fun generateCue(
        type: String,
        modifier: String?,
        roadName: String,
        destinationTitle: String,
        exitNumber: Int
    ): String {
        val t = type.lowercase()
        val m = modifier?.lowercase()
        val target = if (roadName.isNotBlank()) roadName else destinationTitle

        return when {
            t == "arrive" -> "Arrive at $destinationTitle"
            t == "depart" -> "Drive toward $target"
            t == "roundabout" || t == "rotary" -> {
                if (exitNumber > 0) "At roundabout, take exit $exitNumber onto $target"
                else "Enter roundabout toward $target"
            }
            t == "fork" -> "Take the ${m ?: "turn"} fork toward $target"
            t.contains("on ramp") -> "Take ramp ${m ?: ""} onto $target"
            t.contains("off ramp") -> "Take exit onto $target"
            t == "merge" -> "Merge ${m ?: ""} onto $target"
            t == "turn" -> {
                val modStr = when (m) {
                    "sharp left" -> "sharp left"
                    "sharp right" -> "sharp right"
                    "slight left" -> "slight left"
                    "slight right" -> "slight right"
                    "left" -> "left"
                    "right" -> "right"
                    "uturn" -> "U-turn"
                    else -> "turn"
                }
                if (modStr == "U-turn") "Make a U-turn onto $target"
                else "Turn $modStr onto $target"
            }
            else -> "Continue onto $target"
        }
    }
}
