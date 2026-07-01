package com.talapp.mapme.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.theme.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.osmdroid.config.Configuration
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

import android.net.Uri
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import com.talapp.mapme.data.WalkPoi
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.graphics.asImageBitmap

// Formatting Utilities
fun formatTime(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

fun formatDistance(meters: Double): String {
    return if (meters < 1000) {
        String.format("%.0f m", meters)
    } else {
        String.format("%.2f km", meters / 1000.0)
    }
}

fun formatSpeed(metersPerSec: Float): String {
    val kmh = metersPerSec * 3.6
    return String.format("%.1f km/h", kmh)
}

fun uriToBase64(context: Context, uri: Uri): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()
        
        if (originalBitmap == null) return null
        
        val maxDimension = 640
        val width = originalBitmap.width
        val height = originalBitmap.height
        val (newWidth, newHeight) = if (width > height) {
            val ratio = height.toFloat() / width.toFloat()
            maxDimension to (maxDimension * ratio).toInt()
        } else {
            val ratio = width.toFloat() / height.toFloat()
            (maxDimension * ratio).toInt() to maxDimension
        }
        
        val resizedBitmap = Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
        val outputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val bytes = outputStream.toByteArray()
        Base64.encodeToString(bytes, Base64.DEFAULT)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// ----------------------------------------------------
// Reusable OsmMapView Component
// ----------------------------------------------------
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
    activePois: List<WalkPoi> = emptyList(),
    onPoiClick: ((WalkPoi) -> Unit)? = null
) {
    val context = LocalContext.current
    val gson = remember { Gson() }
    
    // Remember the MapView across recompositions
    val mapView = remember {
        MapView(context).apply {
            Configuration.getInstance().userAgentValue = context.packageName
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.5)
        }
    }

    // Toggle map tiles when isDarkMap changes
    LaunchedEffect(isDarkMap) {
        val tileSource = org.osmdroid.tileprovider.tilesource.XYTileSource(
            if (isDarkMap) "CartoDBDarkMatter" else "CartoDBPositron",
            0, 20, 256, ".png",
            arrayOf(if (isDarkMap) "https://a.basemaps.cartocdn.com/dark_all/" else "https://a.basemaps.cartocdn.com/light_all/")
        )
        mapView.setTileSource(tileSource)
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
    LaunchedEffect(points, currentLocation, pastWalks, showPastWalksRadiusMeters, selectedWalkId, activePois, showWalks, showDrives) {
        mapView.overlays.clear()

        // 1. Draw past walks in semi-transparent Electric Violet (or vibrant Green if selected)
        val allPointsForCentering = mutableListOf<GeoPoint>()
        
        for (walk in pastWalks) {
            val walkPoints = try {
                val listType = object : TypeToken<List<WalkPoint>>() {}.type
                gson.fromJson<List<WalkPoint>>(walk.pointsJson, listType)
            } catch (e: Exception) {
                emptyList()
            }
            if (walkPoints.isEmpty()) continue
            
            // Check radius filter if active
            if (showPastWalksRadiusMeters > 0.0 && currentLocation != null) {
                val startPt = walkPoints.firstOrNull()
                if (startPt == null) {
                    continue
                }
                val results = FloatArray(1)
                try {
                    android.location.Location.distanceBetween(
                        currentLocation.latitude, currentLocation.longitude,
                        startPt.latitude, startPt.longitude,
                        results
                    )
                    if (results[0] > showPastWalksRadiusMeters) {
                        continue // Outside radius, skip
                    }
                } catch (e: Exception) {
                    // Fail-safe: don't skip if location calculation fails
                }
            }

            val geoPoints = walkPoints.map { GeoPoint(it.latitude, it.longitude) }
            allPointsForCentering.addAll(geoPoints)

            // Draw past walk line segmented by speed to distinguish walking vs driving
            // 7 km/h = 7 / 3.6 = 1.944 m/s
            val isSelected = walk.id == selectedWalkId
            val baseWalkColor = if (isSelected) "#FF10B981" else "#8B5CF6" // Vibrant Green or Electric Violet
            val driveColor = "#FFEE5859" // Vibrant Coral/Red for Driving >= 7km/h
            val strokeWidth = if (isSelected) 14f else 8f

            var i = 0
            while (i < walkPoints.size - 1) {
                val pt1 = walkPoints[i]
                val pt2 = walkPoints[i + 1]
                
                // Speed threshold check (average speed or point speed)
                val isDriving = pt2.speed * 3.6f >= 7.0f || pt1.speed * 3.6f >= 7.0f
                
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
                val finalColorStr = if (isDriving) driveColor else baseWalkColor
                val hexColor = if (!isSelected) {
                    // Prepend 80 (50% opacity) to color code. Color code is "#AARRGGBB" or "#RRGGBB".
                    // If color code is already 8 hex digits after '#', strip AA and replace with 80.
                    // If it is 6 hex digits after '#', prepend 80.
                    if (finalColorStr.length == 9) {
                        "#80" + finalColorStr.substring(3)
                    } else {
                        "#80" + finalColorStr.substring(1)
                    }
                } else {
                    finalColorStr
                }

                val pastPolyline = Polyline().apply {
                    outlinePaint.color = android.graphics.Color.parseColor(hexColor)
                    outlinePaint.strokeWidth = strokeWidth
                    outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                    outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    setPoints(segmentGeo)
                    
                    // Click listener to select walk
                    setOnClickListener { _, _, _ ->
                        onWalkClick?.invoke(walk)
                        true
                    }
                }
                mapView.overlays.add(pastPolyline)
                i++
            }

            // Draw past walk POIs
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

        // 2. Draw walked active path (neon cyan glow/solid for walk, neon coral/orange for drive)
        if (points.isNotEmpty()) {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            allPointsForCentering.addAll(geoPoints)

            var i = 0
            while (i < points.size - 1) {
                val pt1 = points[i]
                val pt2 = points[i + 1]
                
                val isDriving = pt2.speed * 3.6f >= 7.0f || pt1.speed * 3.6f >= 7.0f
                
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

        // 3. Dynamic camera centering and zooming
        if (currentLocation != null) {
            // Tracking mode: Animate camera to follow current location
            val latestPoint = GeoPoint(currentLocation.latitude, currentLocation.longitude)
            mapView.controller.animateTo(latestPoint)
        } else if (allPointsForCentering.size >= 2) {
            // Historical view: Fit path inside map screen bounds
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

            // Pulse outer halo
            val glowCircle = Polygon().apply {
                setPoints(Polygon.pointsAsCircle(userGeoPoint, 15.0)) // 15m radius
                fillPaint.color = android.graphics.Color.parseColor("#228B5CF6") // 13% alpha Violet
                outlinePaint.color = android.graphics.Color.parseColor("#8B5CF6") // Violet border
                outlinePaint.strokeWidth = 1.5f
            }
            mapView.overlays.add(glowCircle)

            // Center location dot marker
            val locationMarker = Marker(mapView).apply {
                position = userGeoPoint
                icon = ContextCompat.getDrawable(context, android.R.drawable.presence_online)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = "Current Location"
            }
            mapView.overlays.add(locationMarker)
        }

        mapView.invalidate() // Trigger redraw
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView }
    )
}

// ----------------------------------------------------
// Screen 1: Dashboard / Home Screen
// ----------------------------------------------------
@Composable
fun DashboardScreen(
    viewModel: WalkViewModel,
    onStartWalkClick: () -> Unit,
    onWalkClick: (Long) -> Unit,
    onViewMapClick: () -> Unit,
    onGoogleSignInClick: () -> Unit = {}
) {
    val walks by viewModel.allWalks.collectAsState()
    val totalWalksCount by viewModel.totalWalks.collectAsState()
    val totalDistance by viewModel.totalDistanceMeters.collectAsState()
    val totalDuration by viewModel.totalDurationMillis.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900)
    ) {
        // Glowing cosmic background gradients
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(NeonCyan.copy(alpha = 0.08f), Color.Transparent),
                        radius = 1400f,
                        center = androidx.compose.ui.geometry.Offset(0f, 0f)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(ElectricViolet.copy(alpha = 0.08f), Color.Transparent),
                        radius = 1400f,
                        center = androidx.compose.ui.geometry.Offset(1000f, 2000f)
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
            // Elegant Header
            Spacer(modifier = Modifier.height(48.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "MapMe",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Color every street in your city",
                        color = TextGray,
                        fontSize = 14.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Slate800)
                        .border(1.dp, GlassBorder, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Profile",
                        tint = NeonCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cloud Sync Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Slate800),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.5.dp,
                    Brush.linearGradient(listOf(NeonCyan.copy(alpha = 0.35f), ElectricViolet.copy(alpha = 0.35f)))
                )
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (currentUser == null) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = "Cloud Sync Viewer",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Sign in to view walks online",
                                color = TextGray,
                                fontSize = 11.sp
                            )
                        }
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onGoogleSignInClick,
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Google", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            Button(
                                onClick = { viewModel.signInAnonymously() },
                                colors = ButtonDefaults.buttonColors(containerColor = ElectricViolet),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Guest", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        val isGuest = currentUser?.isAnonymous == true
                        val displayName = if (isGuest) "Guest Explorer" else (currentUser?.displayName ?: "User")
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Synced as $displayName",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (isGuest) "Temporary Guest Profile" else "Google Cloud Account Active",
                                color = if (isGuest) ElectricViolet else NeonCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { viewModel.syncWalks() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Sync Now",
                                    tint = NeonCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.signOut() }) {
                                Icon(
                                    imageVector = Icons.Default.ExitToApp,
                                    contentDescription = "Sign Out",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Lifetime Stats Container
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Walks",
                    value = totalWalksCount.toString(),
                    icon = Icons.Default.DirectionsWalk,
                    color = NeonCyan
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Distance",
                    value = formatDistance(totalDistance),
                    icon = Icons.Default.LocationOn,
                    color = ElectricViolet
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Time",
                    value = formatTime(totalDuration),
                    icon = Icons.Default.Timer,
                    color = EmeraldGreen
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            WeeklyActivityChart(walks = walks)

            Spacer(modifier = Modifier.height(16.dp))

            // Walks History list header
            Text(
                text = "Walk History",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Empty state or History list
            if (walks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Map Placeholder",
                            tint = Slate600,
                            modifier = Modifier.size(72.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No walks recorded yet",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the start button below to record your first walking exploration!",
                            color = TextGray,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(walks, key = { it.id }) { walk ->
                        WalkHistoryItem(
                            walk = walk,
                            onClick = { onWalkClick(walk.id) },
                            onDelete = { viewModel.deleteWalk(walk.id) }
                        )
                    }
                }
            }
        }

        // Bottom Actions (Row)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Start Walk Button
            Button(
                onClick = onStartWalkClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .width(160.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .border(
                        BorderStroke(1.dp, Brush.linearGradient(listOf(NeonCyanGlow, ElectricViolet))),
                        RoundedCornerShape(30.dp)
                    )
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                NeonCyan.copy(alpha = 0.85f),
                                ElectricViolet.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Start",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Start Walk",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Explore Map Button (All Walks Map)
            Button(
                onClick = onViewMapClick,
                colors = ButtonDefaults.buttonColors(containerColor = Slate800.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .width(160.dp)
                    .height(54.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .border(
                        BorderStroke(1.dp, GlassBorder),
                        RoundedCornerShape(30.dp)
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "All Walks Map",
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Explore Map",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: ImageVector,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = value,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = title,
                color = TextGray,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun WalkHistoryItem(
    walk: Walk,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(0.5.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = walk.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Distance",
                            tint = NeonCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatDistance(walk.totalDistanceMeters),
                            color = TextGray,
                            fontSize = 12.sp
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = "Duration",
                            tint = ElectricViolet,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = formatTime(walk.totalDurationMillis),
                            color = TextGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Slate900.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFEF4444), // red accent
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

// ----------------------------------------------------
// Screen 2: Active Walk Recording Screen
// ----------------------------------------------------
@Composable
fun RecordScreen(
    viewModel: WalkViewModel,
    onBackClick: () -> Unit
) {
    val isTracking by viewModel.isTracking.collectAsState(initial = false)
    val points by viewModel.activePoints.collectAsState(initial = emptyList())
    val activePois by viewModel.activePois.collectAsState(initial = emptyList())
    val distance by viewModel.activeDistanceMeters.collectAsState(initial = 0.0)
    val durationSeconds by viewModel.activeDurationSeconds.collectAsState(initial = 0L)
    val walks by viewModel.allWalks.collectAsState()
    val isDarkMap by viewModel.isDarkMap.collectAsState()
    
    var showAddPoiDialog by remember { mutableStateOf(false) }
    var selectedPoi by remember { mutableStateOf<WalkPoi?>(null) }
    
    val showWalks by viewModel.showWalks.collectAsState()
    val showDrives by viewModel.showDrives.collectAsState()
    val currentLoc = points.lastOrNull()

    Box(modifier = Modifier.fillMaxSize()) {
        // Map Background (shows past walks within 5km, and active points)
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            points = points,
            currentLocation = currentLoc,
            pastWalks = walks,
            showPastWalksRadiusMeters = 5000.0,
            isDarkMap = isDarkMap,
            showWalks = showWalks,
            showDrives = showDrives,
            activePois = activePois,
            onPoiClick = { selectedPoi = it }
        )

        // Floating Back Button (Glassmorphic)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(GlassBackground)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { onBackClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Floating Map Style Toggle Button (Glassmorphic)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 16.dp, end = 16.dp)
                .align(Alignment.TopEnd)
                .size(44.dp)
                .clip(CircleShape)
                .background(GlassBackground)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { viewModel.toggleMapStyle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkMap) Icons.Default.WbSunny else Icons.Default.NightsStay,
                contentDescription = "Toggle Map Style",
                tint = if (isDarkMap) NeonCyan else ElectricViolet,
                modifier = Modifier.size(20.dp)
            )
        }

        // Floating Speed Filters Panel (Glassmorphic)
        Card(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 76.dp, end = 16.dp)
                .align(Alignment.TopEnd)
                .width(130.dp),
            colors = CardDefaults.cardColors(containerColor = GlassBackground),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FILTERS",
                    color = TextGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { viewModel.toggleShowWalks() }
                ) {
                    Checkbox(
                        checked = showWalks,
                        onCheckedChange = { viewModel.toggleShowWalks() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = NeonCyan,
                            uncheckedColor = TextGray,
                            checkmarkColor = Color.Black
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Walks (<7)", color = Color.White, fontSize = 11.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { viewModel.toggleShowDrives() }
                ) {
                    Checkbox(
                        checked = showDrives,
                        onCheckedChange = { viewModel.toggleShowDrives() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFEF4444),
                            uncheckedColor = TextGray,
                            checkmarkColor = Color.White
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Drives (7+)", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // Floating Add POI Button (Glassmorphic, visible when tracking has points)
        if (isTracking && points.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 186.dp, end = 16.dp)
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable { showAddPoiDialog = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AddLocation,
                    contentDescription = "Add Point of Interest",
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (showAddPoiDialog) {
            AddPoiDialog(
                onDismiss = { showAddPoiDialog = false },
                onSave = { note, img ->
                    viewModel.addActivePoi(note, img)
                    showAddPoiDialog = false
                }
            )
        }

        PoiDetailsPanel(
            poi = selectedPoi,
            onDismiss = { selectedPoi = null }
        )

        // Glassmorphic Stats Overlay (Top Center)
        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
                .widthIn(max = 320.dp),
            colors = CardDefaults.cardColors(containerColor = GlassBackground),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Time
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DURATION", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = formatTime(durationSeconds * 1000),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Divider
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(GlassBorder))

                // Distance
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DISTANCE", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = formatDistance(distance),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Divider
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(GlassBorder))

                // Speed
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SPEED", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    val speed = currentLoc?.speed ?: 0f
                    Text(
                        text = formatSpeed(speed),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Controls overlay card (Bottom Center)
        AnimatedVisibility(
            visible = selectedPoi == null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassBackground),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Tracking Status Bar
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(bottom = 16.dp)
                    ) {
                        // Pulsing green/cyan indicator
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isTracking) NeonCyan else Color.Gray)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isTracking) "ACTIVE RECORDING" else "PAUSED",
                            color = if (isTracking) NeonCyan else TextGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    // Buttons Panel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Toggle tracking Pause / Resume / Start
                        if (isTracking) {
                            // Pause Button
                            IconButtonControl(
                                onClick = { viewModel.pauseWalk() },
                                icon = Icons.Default.Pause,
                                contentDescription = "Pause",
                                backgroundColor = ElectricViolet
                            )
                        } else {
                            // Resume/Start Button
                            IconButtonControl(
                                onClick = { viewModel.startWalk() },
                                icon = Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                backgroundColor = NeonCyan
                            )
                        }

                        // 2. Stop Button (Red, only if walk has points or tracking timer has run)
                        IconButtonControl(
                            onClick = {
                                viewModel.stopWalk()
                                onBackClick() // navigate back to Dashboard
                            },
                            icon = Icons.Default.Stop,
                            contentDescription = "Stop",
                            backgroundColor = Color(0xFFEF4444)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun IconButtonControl(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

// ----------------------------------------------------
// Screen 3: Historical Walk Detail Screen
// ----------------------------------------------------
@Composable
fun DetailScreen(
    walkId: Long,
    viewModel: WalkViewModel,
    onBackClick: () -> Unit
) {
    // Load specific walk details reactively
    var walk by remember { mutableStateOf<Walk?>(null) }
    
    LaunchedEffect(walkId) {
        viewModel.getWalkFlow(walkId).collect {
            walk = it
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (walk == null) {
            // Loading Spinner
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Slate900),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NeonCyan)
            }
        } else {
            val currentWalk = walk!!
            val isDarkMap by viewModel.isDarkMap.collectAsState()
            var selectedPoi by remember { mutableStateOf<WalkPoi?>(null) }
            
            // Deserialize path points
            val pointsListType = object : TypeToken<List<WalkPoint>>() {}.type
            val points: List<WalkPoint> = Gson().fromJson(currentWalk.pointsJson, pointsListType)

            val poisListType = object : TypeToken<List<WalkPoi>>() {}.type
            val walkPois: List<WalkPoi> = try {
                Gson().fromJson(currentWalk.poisJson, poisListType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            // Route playback simulator state
            var playbackIndex by remember { mutableStateOf<Int?>(null) }
            var isPlaybackPlaying by remember { mutableStateOf(false) }

            LaunchedEffect(playbackIndex, isPlaybackPlaying) {
                if (isPlaybackPlaying && playbackIndex != null) {
                    kotlinx.coroutines.delay(200) // move point along path every 200ms
                    val nextIndex = playbackIndex!! + 1
                    if (nextIndex < points.size) {
                        playbackIndex = nextIndex
                    } else {
                        isPlaybackPlaying = false
                        playbackIndex = null
                    }
                }
            }

            val simulatedLoc = if (playbackIndex != null && playbackIndex!! < points.size) points[playbackIndex!!] else null

            val showWalks by viewModel.showWalks.collectAsState()
            val showDrives by viewModel.showDrives.collectAsState()

            // Map View
            OsmMapView(
                modifier = Modifier.fillMaxSize(),
                points = points,
                currentLocation = simulatedLoc,
                isDarkMap = isDarkMap,
                showWalks = showWalks,
                showDrives = showDrives,
                activePois = walkPois,
                onPoiClick = { selectedPoi = it }
            )

            // Back Button (Glassmorphic)
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 16.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable { onBackClick() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Floating Map Style Toggle Button (Glassmorphic)
            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(top = 16.dp, end = 16.dp)
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable { viewModel.toggleMapStyle() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isDarkMap) Icons.Default.WbSunny else Icons.Default.NightsStay,
                    contentDescription = "Toggle Map Style",
                    tint = if (isDarkMap) NeonCyan else ElectricViolet,
                    modifier = Modifier.size(20.dp)
                )
            }


            // Stats info overlay (Top Center)
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp, start = 20.dp, end = 20.dp)
                    .fillMaxWidth(0.9f),
                colors = CardDefaults.cardColors(containerColor = GlassBackground),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentWalk.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                if (isPlaybackPlaying) {
                                    isPlaybackPlaying = false
                                    playbackIndex = null
                                } else {
                                    playbackIndex = 0
                                    isPlaybackPlaying = true
                                }
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .background(NeonCyan.copy(alpha = 0.15f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaybackPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Simulate Route Playback",
                                tint = NeonCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("DISTANCE", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = formatDistance(currentWalk.totalDistanceMeters),
                                color = NeonCyan,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("DURATION", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = formatTime(currentWalk.totalDurationMillis),
                                color = ElectricViolet,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column {
                            Text("AVG SPEED", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            val durationSec = currentWalk.totalDurationMillis / 1000f
                            val avgMps = if (durationSec > 0) (currentWalk.totalDistanceMeters / durationSec).toFloat() else 0f
                            Text(
                                text = formatSpeed(avgMps),
                                color = EmeraldGreen,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Action delete button (Bottom Center)
            AnimatedVisibility(
                visible = selectedPoi == null,
                enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.deleteWalk(currentWalk.id)
                        onBackClick()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .width(160.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Walk",
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete Walk", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            PoiDetailsPanel(
                poi = selectedPoi,
                onDismiss = { selectedPoi = null }
            )
        }
    }
}

@Composable
fun LoginScreen(
    viewModel: WalkViewModel,
    onGoogleSignInClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(ElectricViolet.copy(alpha = 0.15f), Color.Transparent),
                        radius = 1200f
                    )
                )
        )

        Card(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate800),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(NeonCyan, ElectricViolet)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "MapMe Logo",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "MapMe",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Log in to track your walks and sync your progress to the cloud",
                    color = TextGray,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(36.dp))

                Button(
                    onClick = onGoogleSignInClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(NeonCyan, ElectricViolet)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AccountCircle,
                                contentDescription = "Google Sign In",
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Sign in with Google",
                                color = Color.Black,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(GlassBorder))
                    Text(
                        text = "OR",
                        color = TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Box(modifier = Modifier.weight(1f).height(1.dp).background(GlassBorder))
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { viewModel.signInAnonymously() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Slate900.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Text(
                        text = "Continue as Guest",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AllWalksMapScreen(
    viewModel: WalkViewModel,
    onBackClick: () -> Unit
) {
    val walks by viewModel.allWalks.collectAsState()
    val isDarkMap by viewModel.isDarkMap.collectAsState()
    val showWalks by viewModel.showWalks.collectAsState()
    val showDrives by viewModel.showDrives.collectAsState()
    var selectedPoi by remember { mutableStateOf<WalkPoi?>(null) }
    var selectedWalk by remember { mutableStateOf<Walk?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map showing all past walks (without currentLocation or active points)
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            pastWalks = walks,
            selectedWalkId = selectedWalk?.id,
            onWalkClick = { selectedWalk = it },
            isDarkMap = isDarkMap,
            showWalks = showWalks,
            showDrives = showDrives,
            onPoiClick = { selectedPoi = it }
        )

        // Floating Back Button (Glassmorphic)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(GlassBackground)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { onBackClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        // Floating Map Style Toggle Button (Glassmorphic)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 16.dp, end = 16.dp)
                .align(Alignment.TopEnd)
                .size(44.dp)
                .clip(CircleShape)
                .background(GlassBackground)
                .border(1.dp, GlassBorder, CircleShape)
                .clickable { viewModel.toggleMapStyle() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkMap) Icons.Default.WbSunny else Icons.Default.NightsStay,
                contentDescription = "Toggle Map Style",
                tint = if (isDarkMap) NeonCyan else ElectricViolet,
                modifier = Modifier.size(20.dp)
            )
        }

        // Floating Speed Filters Panel (Glassmorphic)
        Card(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 76.dp, end = 16.dp)
                .align(Alignment.TopEnd)
                .width(130.dp),
            colors = CardDefaults.cardColors(containerColor = GlassBackground),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, GlassBorder)
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "FILTERS",
                    color = TextGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { viewModel.toggleShowWalks() }
                ) {
                    Checkbox(
                        checked = showWalks,
                        onCheckedChange = { viewModel.toggleShowWalks() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = NeonCyan,
                            uncheckedColor = TextGray,
                            checkmarkColor = Color.Black
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Walks (<7)", color = Color.White, fontSize = 11.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { viewModel.toggleShowDrives() }
                ) {
                    Checkbox(
                        checked = showDrives,
                        onCheckedChange = { viewModel.toggleShowDrives() },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFFEF4444),
                            uncheckedColor = TextGray,
                            checkmarkColor = Color.White
                        ),
                        modifier = Modifier.size(18.dp)
                    )
                    Text("Drives (7+)", color = Color.White, fontSize = 11.sp)
                }
            }
        }

        // Title / Summary Card (Bottom Center)
        AnimatedVisibility(
            visible = selectedPoi == null && selectedWalk == null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassBackground),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Explore Your Walks",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Displaying ${walks.size} historical routes on the map. Tap any route to highlight it.",
                        color = TextGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // Selected Walk Summary Card (Bottom Center)
        AnimatedVisibility(
            visible = selectedWalk != null && selectedPoi == null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth()
        ) {
            if (selectedWalk != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GlassBackground),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, GlassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedWalk!!.title,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { selectedWalk = null },
                                modifier = Modifier
                                    .size(28.dp)
                                    .background(Slate900.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("DISTANCE", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = formatDistance(selectedWalk!!.totalDistanceMeters),
                                    color = NeonCyan,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column {
                                Text("DURATION", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = formatTime(selectedWalk!!.totalDurationMillis),
                                    color = ElectricViolet,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column {
                                Text("AVG SPEED", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                val durationSec = selectedWalk!!.totalDurationMillis / 1000f
                                val avgMps = if (durationSec > 0) (selectedWalk!!.totalDistanceMeters / durationSec).toFloat() else 0f
                                Text(
                                    text = formatSpeed(avgMps),
                                    color = EmeraldGreen,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        PoiDetailsPanel(
            poi = selectedPoi,
            onDismiss = { selectedPoi = null }
        )
    }
}

// ----------------------------------------------------
// Custom Composable: WeeklyActivityChart
// ----------------------------------------------------
@Composable
fun WeeklyActivityChart(walks: List<Walk>) {
    val currentMillis = System.currentTimeMillis()
    val dayMillis = 24 * 60 * 60 * 1000L
    
    val dailyDistances = remember(walks) {
        val distances = FloatArray(7)
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        
        for (i in 0..6) {
            val dateStr = sdf.format(java.util.Date(currentMillis - (6 - i) * dayMillis))
            var distSum = 0f
            for (walk in walks) {
                val walkDateStr = sdf.format(java.util.Date(walk.startTime))
                if (walkDateStr == dateStr) {
                    distSum += walk.totalDistanceMeters.toFloat()
                }
            }
            distances[i] = distSum
        }
        distances
    }
    
    val maxVal = remember(dailyDistances) {
        val max = dailyDistances.maxOrNull() ?: 0f
        if (max < 100f) 1000f else max
    }
    
    val daysLabels = remember {
        val labels = ArrayList<String>()
        val sdf = java.text.SimpleDateFormat("E", java.util.Locale.US)
        for (i in 0..6) {
            labels.add(sdf.format(java.util.Date(currentMillis - (6 - i) * dayMillis)).take(1))
        }
        labels
    }

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Weekly Activity Summary",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))
            
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            ) {
                val width = size.width
                val height = size.height
                val barSpacing = width / 7f
                val maxBarHeight = height - 10.dp.toPx()
                
                // Grid baseline
                drawLine(
                    color = GlassBorder,
                    start = androidx.compose.ui.geometry.Offset(0f, maxBarHeight),
                    end = androidx.compose.ui.geometry.Offset(width, maxBarHeight),
                    strokeWidth = 1f
                )
                
                for (i in 0..6) {
                    val dist = dailyDistances[i]
                    val pct = dist / maxVal
                    val barHeight = pct * maxBarHeight
                    val x = i * barSpacing + barSpacing / 2f
                    val y = maxBarHeight - barHeight
                    
                    if (barHeight > 2f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(NeonCyan, ElectricViolet)
                            ),
                            topLeft = androidx.compose.ui.geometry.Offset(x - 8.dp.toPx(), y),
                            size = androidx.compose.ui.geometry.Size(16.dp.toPx(), barHeight),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
                        )
                    } else {
                        drawCircle(
                            color = Slate600.copy(alpha = 0.5f),
                            radius = 3.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(x, maxBarHeight)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                for (label in daysLabels) {
                    Text(
                        text = label,
                        color = TextGray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun BoxScope.PoiDetailsPanel(
    poi: WalkPoi?,
    onDismiss: () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = poi != null,
        enter = androidx.compose.animation.slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
            .fillMaxWidth()
    ) {
        if (poi != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassBackground),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Premium drag indicator
                    Box(
                        modifier = Modifier
                            .padding(bottom = 10.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(TextGray.copy(alpha = 0.4f))
                    )
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Point of Interest",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(28.dp)
                                .background(Slate900.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val dateStr = remember(poi.timestamp) {
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.MEDIUM,
                                    java.text.DateFormat.SHORT
                                ).format(java.util.Date(poi.timestamp))
                            }
                            Text(
                                text = "Logged: $dateStr",
                                color = TextGray,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                            
                            val textNote = if (!poi.text.isNullOrBlank()) poi.text else "No notes added for this location."
                            val textColor = if (!poi.text.isNullOrBlank()) Color.White else TextGray
                            Text(
                                text = textNote,
                                color = textColor,
                                fontSize = 14.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        if (!poi.imageBase64.isNullOrBlank()) {
                            val imageBitmap = remember(poi.imageBase64) {
                                try {
                                    val decodedBytes = Base64.decode(poi.imageBase64, Base64.DEFAULT)
                                    val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                    bmp?.asImageBitmap()
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            
                            if (imageBitmap != null) {
                                var showFullScreenImage by remember { mutableStateOf(false) }
                                
                                androidx.compose.foundation.Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "POI Photo Preview",
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.5.dp, NeonCyan.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                                        .clickable { showFullScreenImage = true },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                )
                                
                                if (showFullScreenImage) {
                                    androidx.compose.ui.window.Dialog(
                                        onDismissRequest = { showFullScreenImage = false }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable { showFullScreenImage = false },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            androidx.compose.foundation.Image(
                                                bitmap = imageBitmap,
                                                contentDescription = "POI Photo Full",
                                                modifier = Modifier
                                                    .fillMaxWidth(0.95f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .border(1.5.dp, GlassBorder, RoundedCornerShape(16.dp)),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiDetailsDialog(
    poi: WalkPoi,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .background(Slate800, RoundedCornerShape(24.dp)),
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = NeonCyan)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        title = {
            Text(
                text = "Point of Interest",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val dateStr = remember(poi.timestamp) {
                    java.text.DateFormat.getDateTimeInstance(
                        java.text.DateFormat.MEDIUM,
                        java.text.DateFormat.SHORT
                    ).format(java.util.Date(poi.timestamp))
                }
                Text(
                    text = "Logged: $dateStr",
                    color = TextGray,
                    fontSize = 12.sp
                )

                if (!poi.text.isNullOrBlank()) {
                    Text(
                        text = poi.text,
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Slate900.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .border(0.5.dp, GlassBorder, RoundedCornerShape(12.dp))
                            .padding(14.dp)
                    )
                }

                if (!poi.imageBase64.isNullOrBlank()) {
                    val imageBitmap = remember(poi.imageBase64) {
                        try {
                            val decodedBytes = Base64.decode(poi.imageBase64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                            bmp?.asImageBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (imageBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = imageBitmap,
                            contentDescription = "POI Photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Text("Error loading photo", color = Color.Red, fontSize = 12.sp)
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPoiDialog(
    onDismiss: () -> Unit,
    onSave: (note: String?, imageBase64: String?) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }
    var tempPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            attachedImageBase64 = uriToBase64(context, uri)
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempPhotoUri != null) {
            attachedImageBase64 = uriToBase64(context, tempPhotoUri!!)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
            .background(Slate800, RoundedCornerShape(24.dp)),
        confirmButton = {
            Button(
                onClick = { onSave(noteText.ifBlank { null }, attachedImageBase64) },
                enabled = noteText.isNotBlank() || attachedImageBase64 != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    disabledContainerColor = Slate600
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Save",
                    color = if (noteText.isNotBlank() || attachedImageBase64 != null) Color.Black else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = TextGray)
            ) {
                Text("Cancel")
            }
        },
        title = {
            Text(
                text = "Add Point of Interest",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Attach a photo or note to document this location.",
                    color = TextGray,
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note / Description", color = TextGray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = GlassBorder,
                        focusedLabelColor = NeonCyan,
                        unfocusedLabelColor = TextGray
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3
                )

                if (attachedImageBase64 != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                    ) {
                        val imageBitmap = remember(attachedImageBase64) {
                            try {
                                val decodedBytes = Base64.decode(attachedImageBase64, Base64.DEFAULT)
                                val bmp = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                bmp?.asImageBitmap()
                            } catch (e: Exception) {
                                null
                            }
                        }
                        
                        if (imageBitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap = imageBitmap,
                                contentDescription = "Attached Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        }

                        Box(
                            modifier = Modifier
                                .padding(8.dp)
                                .align(Alignment.TopEnd)
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { attachedImageBase64 = null },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove Photo",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val tempFile = java.io.File(context.cacheDir, "temp_poi_${System.currentTimeMillis()}.jpg")
                                val authority = "${context.packageName}.fileprovider"
                                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, tempFile)
                                tempPhotoUri = uri
                                takePictureLauncher.launch(uri)
                            },
                            border = BorderStroke(1.dp, GlassBorder),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = "Take Photo",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Camera", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                pickImageLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            border = BorderStroke(1.dp, GlassBorder),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan),
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "Gallery",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Gallery", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    )
}


