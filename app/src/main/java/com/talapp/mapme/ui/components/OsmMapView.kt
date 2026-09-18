package com.talapp.mapme.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talapp.mapme.data.RouteConsolidator
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.theme.GlassBackground
import com.talapp.mapme.theme.GlassBorder
import com.talapp.mapme.theme.NeonCyan
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Reusable OpenStreetMap MapView composable for MapMe.
 * Supports active tracking, historical walks, POI markers, route playback, and dark mode.
 */
@Composable
fun OsmMapView(
    modifier: Modifier = Modifier,
    points: List<WalkPoint> = emptyList(),
    currentLocation: WalkPoint? = null,
    pastWalks: List<Walk> = emptyList(),
    showPastWalksRadiusMeters: Double = -1.0,
    selectedWalkId: Long? = null,
    onWalkClick: ((Walk) -> Unit)? = null,
    isDarkMap: Boolean = true,
    showWalks: Boolean = true,
    showDrives: Boolean = true,
    showPois: Boolean = true,
    activePois: List<WalkPoi> = emptyList(),
    onPoiClick: ((WalkPoi) -> Unit)? = null,
    isDriveRecording: Boolean = false
) {
    val context = LocalContext.current
    val gson = remember { Gson() }
    var hasSetInitialTrackingZoom by remember { mutableStateOf(false) }

    // Remember the MapView across recompositions
    val mapView = remember {
        MapView(context).apply {
            Configuration.getInstance().userAgentValue = context.packageName
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.5)
        }
    }

    // Recenter mode: true = follow user current location, false = user manually scrolled/zoomed
    var autoRecenterEnabled by remember { mutableStateOf(true) }
    var lastUserInteractionTime by remember { mutableStateOf(0L) }

    // Touch listener wrapper to catch when user drags/scrolls/touches the map
    LaunchedEffect(mapView) {
        val listener = object : org.osmdroid.events.MapListener {
            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                if (System.currentTimeMillis() - lastUserInteractionTime > 300) {
                    autoRecenterEnabled = false
                }
                lastUserInteractionTime = System.currentTimeMillis()
                return true
            }
            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean {
                if (System.currentTimeMillis() - lastUserInteractionTime > 300) {
                    autoRecenterEnabled = false
                }
                lastUserInteractionTime = System.currentTimeMillis()
                return true
            }
        }
        mapView.addMapListener(listener)
    }

    // Auto-recenter timer: check every second if 5 seconds have passed since the last interaction
    LaunchedEffect(currentLocation) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            if (!autoRecenterEnabled && lastUserInteractionTime > 0L) {
                if (System.currentTimeMillis() - lastUserInteractionTime >= 5000L) {
                    autoRecenterEnabled = true
                }
            }
        }
    }

    // Toggle map tiles and theme when isDarkMap changes
    LaunchedEffect(isDarkMap) {
        mapView.setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
        val darkFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    -0.7f, 0f, 0f, 0f, 220f,
                    0f, -0.7f, 0f, 0f, 230f,
                    0f, 0f, -0.7f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        mapView.overlayManager.tilesOverlay.setColorFilter(if (isDarkMap) darkFilter else null)
        mapView.invalidate()
    }

    // Bind map lifecycle to Compose lifecycle
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapView.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Redraw paths and markers when data changes
    LaunchedEffect(points, currentLocation, pastWalks, showPastWalksRadiusMeters, selectedWalkId, activePois, showWalks, showDrives, showPois, isDriveRecording) {
        mapView.overlays.clear()

        // 1. Parse past walks into (Walk, List<WalkPoint>, Boolean (isDrive))
        val parsedPastWalks = pastWalks.mapNotNull { walk ->
            val walkPoints = try {
                val listType = object : TypeToken<List<WalkPoint>>() {}.type
                gson.fromJson<List<WalkPoint>>(walk.pointsJson, listType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            if (walkPoints.isEmpty()) return@mapNotNull null

            // Radius filter if active
            if (showPastWalksRadiusMeters > 0.0 && currentLocation != null) {
                val startPt = walkPoints.first()
                val results = FloatArray(1)
                try {
                    android.location.Location.distanceBetween(
                        currentLocation.latitude, currentLocation.longitude,
                        startPt.latitude, startPt.longitude,
                        results
                    )
                    if (results[0] > showPastWalksRadiusMeters) {
                        return@mapNotNull null
                    }
                } catch (_: Exception) {}
            }

            val titleLower = walk.title.lowercase()
            val isDriveModeByTitle = titleLower.startsWith("drive on") || titleLower.startsWith("drive at")
            val isWalkModeByTitle = titleLower.startsWith("walk on") || titleLower.startsWith("walk at")

            val isDrive = if (isDriveModeByTitle) {
                true
            } else if (isWalkModeByTitle) {
                false
            } else {
                val avgSpeedKmh = (walkPoints.map { it.speed }.average() * 3.6f).toFloat()
                avgSpeedKmh >= 7.0f
            }

            Triple(walk, walkPoints, isDrive)
        }

        val allPointsForCentering = mutableListOf<GeoPoint>()
        parsedPastWalks.forEach { (_, pts, _) ->
            allPointsForCentering.addAll(pts.map { GeoPoint(it.latitude, it.longitude) })
        }

        // 2. Separate selected walk vs background walks
        val selectedTriple = parsedPastWalks.find { it.first.id == selectedWalkId }
        val backgroundWalks = if (selectedTriple != null) {
            parsedPastWalks.filter { it.first.id != selectedWalkId }
        } else {
            parsedPastWalks
        }

        // 3. Consolidate close/repeated routes into single clean lines (Walk & Drive kept separate)
        val consolidatedPolylines = RouteConsolidator.consolidate(backgroundWalks)

        for (poly in consolidatedPolylines) {
            if (poly.isDrive && !showDrives) continue
            if (!poly.isDrive && !showWalks) continue

            val colorHex = if (poly.isDrive) "#D9EE5859" else "#D98B5CF6"
            val geoPts = poly.points.map { GeoPoint(it.latitude, it.longitude) }

            val pastPolyline = Polyline().apply {
                outlinePaint.color = android.graphics.Color.parseColor(colorHex)
                outlinePaint.strokeWidth = 7f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(geoPts)

                if (poly.sourceWalk != null) {
                    setOnClickListener { _, _, _ ->
                        onWalkClick?.invoke(poly.sourceWalk)
                        true
                    }
                }
            }
            mapView.overlays.add(pastPolyline)
        }

        // 4. If a specific walk/drive is selected, render its COMPLETE un-merged path highlighted on top
        if (selectedTriple != null) {
            val (selectedWalk, selPts, isDrive) = selectedTriple
            val selColorHex = if (isDrive) "#FFFF3B30" else "#FF10B981"
            val geoPts = selPts.map { GeoPoint(it.latitude, it.longitude) }

            // Outer glow
            val glowPolyline = Polyline().apply {
                outlinePaint.color = android.graphics.Color.parseColor(if (isDrive) "#44FF3B30" else "#4410B981")
                outlinePaint.strokeWidth = 18f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(geoPts)
            }
            mapView.overlays.add(glowPolyline)

            // Core sharp line
            val corePolyline = Polyline().apply {
                outlinePaint.color = android.graphics.Color.parseColor(selColorHex)
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                setPoints(geoPts)
            }
            mapView.overlays.add(corePolyline)

            // Start & End markers for the selected walk
            if (geoPts.size >= 2) {
                val startMarker = Marker(mapView).apply {
                    position = geoPts.first()
                    icon = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "Start: ${selectedWalk.title}"
                }
                mapView.overlays.add(startMarker)

                val endMarker = Marker(mapView).apply {
                    position = geoPts.last()
                    icon = ContextCompat.getDrawable(context, android.R.drawable.presence_busy)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "End: ${selectedWalk.title}"
                }
                mapView.overlays.add(endMarker)
            }
        }

        for ((walk, _) in parsedPastWalks) {
            // Draw past walk POIs
            if (showPois) {
                val walkPois = try {
                    val listType = object : TypeToken<List<WalkPoi>>() {}.type
                    gson.fromJson<List<WalkPoi>>(walk.poisJson, listType) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                for (poi in walkPois) {
                    val poiGeoPoint = GeoPoint(poi.latitude, poi.longitude)
                    val marker = Marker(mapView).apply {
                        position = poiGeoPoint
                        val pinDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)?.mutate()?.apply {
                            androidx.core.graphics.drawable.DrawableCompat.setTint(this, android.graphics.Color.parseColor("#8B5CF6"))
                        }
                        icon = pinDrawable
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        title = poi.text ?: "POI"
                        setOnMarkerClickListener { _, _ ->
                            onPoiClick?.invoke(poi)
                            true
                        }
                    }
                    mapView.overlays.add(marker)
                }
            }
        }

        // 2. Draw walked active path (neon cyan glow/solid for walk, neon coral/orange for drive)
        if (points.isNotEmpty()) {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            allPointsForCentering.addAll(geoPoints)

            var i = 0
            while (i < points.size - 1) {
                val pt1 = points[i]
                val pt2 = points[i + 1]

                // Smart speed threshold: Compute the average speed of the local window
                val pointsWindow = mutableListOf<WalkPoint>()
                if (i > 0) pointsWindow.add(points[i - 1])
                pointsWindow.add(pt1)
                pointsWindow.add(pt2)
                if (i < points.size - 2) pointsWindow.add(points[i + 2])

                val avgSpeedKmh = (pointsWindow.map { it.speed }.average() * 3.6f).toFloat()
                val isDriving = isDriveRecording

                // Skip rendering if filtered out
                if (isDriving && !showDrives) {
                    i++
                    continue
                }
                if (!isDriving && !showWalks) {
                    i++
                    continue
                }

                val segmentGeo = listOf(GeoPoint(pt1.latitude, pt1.longitude), GeoPoint(pt2.latitude, pt2.longitude))
                val solidColor = if (isDriving) "#FF3B30" else "#06B6D4" // Coral Red vs Cyan
                val glowColor = if (isDriving) "#33FF3B30" else "#3306B6D4" // 20% Alpha

                // Thicker background glow line
                val glowPolyline = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor(glowColor)
                    outlinePaint.strokeWidth = 28f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    setPoints(segmentGeo)
                }
                mapView.overlays.add(glowPolyline)

                // Neon solid core line
                val corePolyline = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor(solidColor)
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    setPoints(segmentGeo)
                }
                mapView.overlays.add(corePolyline)
                i++
            }
        }

        // 2.5 Draw active walk POIs
        if (showPois) {
            for (poi in activePois) {
                val poiGeoPoint = GeoPoint(poi.latitude, poi.longitude)
                val marker = Marker(mapView).apply {
                    position = poiGeoPoint
                    val pinDrawable = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_myplaces)?.mutate()?.apply {
                        androidx.core.graphics.drawable.DrawableCompat.setTint(this, android.graphics.Color.parseColor("#06B6D4"))
                    }
                    icon = pinDrawable
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = poi.text ?: "POI"
                    setOnMarkerClickListener { _, _ ->
                        onPoiClick?.invoke(poi)
                        true
                    }
                }
                mapView.overlays.add(marker)
            }
        }

        // 3. Dynamic camera centering and zooming
        if (currentLocation == null) {
            hasSetInitialTrackingZoom = false
        }

        if (currentLocation != null) {
            val latestPoint = GeoPoint(currentLocation.latitude, currentLocation.longitude)

            if (autoRecenterEnabled) {
                if (!hasSetInitialTrackingZoom) {
                    val latDelta = 0.0027
                    val lonDelta = 0.0027
                    val north = currentLocation.latitude + latDelta
                    val south = currentLocation.latitude - latDelta
                    val east = currentLocation.longitude + lonDelta
                    val west = currentLocation.longitude - lonDelta

                    mapView.post {
                        try {
                            val bbox = BoundingBox(north, east, south, west)
                            mapView.zoomToBoundingBox(bbox, true, 0)
                            mapView.controller.setCenter(latestPoint)
                        } catch (e: Exception) {
                            mapView.controller.setCenter(latestPoint)
                        }
                    }
                    hasSetInitialTrackingZoom = true
                } else {
                    mapView.controller.animateTo(latestPoint)
                }
            }
        } else if (allPointsForCentering.size >= 2) {
            mapView.post {
                try {
                    val boundingBox = BoundingBox.fromGeoPoints(allPointsForCentering)
                    mapView.zoomToBoundingBox(boundingBox, true, 120)
                } catch (e: Exception) {
                    mapView.controller.setCenter(allPointsForCentering.last())
                }
            }
        } else if (allPointsForCentering.isNotEmpty()) {
            mapView.controller.setCenter(allPointsForCentering.last())
        }

        // 4. Draw user current location blue dot
        currentLocation?.let { loc ->
            val userGeoPoint = GeoPoint(loc.latitude, loc.longitude)

            val glowCircle = Polygon().apply {
                setPoints(Polygon.pointsAsCircle(userGeoPoint, 15.0))
                fillPaint.color = android.graphics.Color.parseColor("#228B5CF6")
                outlinePaint.color = android.graphics.Color.parseColor("#8B5CF6")
                outlinePaint.strokeWidth = 1.5f
            }
            mapView.overlays.add(glowCircle)

            val locationMarker = Marker(mapView).apply {
                position = userGeoPoint
                icon = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Current Location"
            }
            mapView.overlays.add(locationMarker)
        }

        mapView.invalidate()
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView }
        )

        // Floating Recenter button
        if (currentLocation != null && !autoRecenterEnabled) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 16.dp, end = 16.dp)
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable {
                        autoRecenterEnabled = true
                        val latestPoint = GeoPoint(currentLocation.latitude, currentLocation.longitude)
                        mapView.controller.animateTo(latestPoint)
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter Map",
                    tint = NeonCyan,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
