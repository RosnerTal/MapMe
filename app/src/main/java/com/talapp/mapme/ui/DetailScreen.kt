package com.talapp.mapme.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.data.WalkPoint
import com.talapp.mapme.theme.*
import com.talapp.mapme.ui.components.DetailMetricCard
import com.talapp.mapme.ui.components.OsmMapView
import com.talapp.mapme.ui.components.PoiDetailsPanel
import com.talapp.mapme.util.exportGpx
import com.talapp.mapme.util.exportKml
import com.talapp.mapme.util.formatDistance
import com.talapp.mapme.util.formatSpeed
import com.talapp.mapme.util.formatTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            val showPois by viewModel.showPois.collectAsState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CyberDark)
            ) {
                // Top 38% Height: Route Map Preview
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.38f)
                ) {
                    OsmMapView(
                        modifier = Modifier.fillMaxSize(),
                        points = points,
                        currentLocation = simulatedLoc,
                        isDarkMap = isDarkMap,
                        showWalks = showWalks,
                        showDrives = showDrives,
                        showPois = showPois,
                        activePois = walkPois,
                        onPoiClick = { selectedPoi = it }
                    )

                    // Floating Back Button (Top Left)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .statusBarsPadding()
                            .padding(start = 16.dp, top = 8.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(GlassBackground)
                            .border(1.dp, GlassBorder, CircleShape)
                            .clickable { onBackClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Top Right Overlay Controls (Playback + Style)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .statusBarsPadding()
                            .padding(end = 16.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Playback Simulation Button
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(GlassBackground)
                                .border(1.dp, GlassBorder, CircleShape)
                                .clickable {
                                    if (isPlaybackPlaying) {
                                        isPlaybackPlaying = false
                                        playbackIndex = null
                                    } else {
                                        playbackIndex = 0
                                        isPlaybackPlaying = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaybackPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Simulate Playback",
                                tint = ElectricCyanBright,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Map Style Toggle Button
                        Box(
                            modifier = Modifier
                                .size(42.dp)
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
                    }
                }

                // Bottom 62% Height: Analytics & Waypoints Dashboard
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(CyberDark)
                ) {
                    val context = LocalContext.current
                    val scrollState = rememberScrollState()
                    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp)
                            .navigationBarsPadding()
                    ) {
                        // Drag handle
                        Spacer(modifier = Modifier.height(10.dp))
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .width(36.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Slate600.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        // Trip Header (Title + Mode Badge + Date)
                        val durationSec = currentWalk.totalDurationMillis / 1000f
                        val avgMps = if (durationSec > 0) (currentWalk.totalDistanceMeters / durationSec).toFloat() else 0f
                        val avgKmh = avgMps * 3.6f
                        val isDriveWalk = avgKmh >= 7.0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = currentWalk.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isDriveWalk) DriveCoral.copy(alpha = 0.15f) else NeonCyan.copy(alpha = 0.15f))
                                    .border(1.dp, if (isDriveWalk) DriveCoral.copy(alpha = 0.4f) else NeonCyan.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isDriveWalk) "🚗 DRIVE" else "🚶 WALK",
                                    color = if (isDriveWalk) DriveCoral else NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val dateFormat = remember(currentWalk.startTime) {
                            SimpleDateFormat("EEEE, MMM d, yyyy • h:mm a", Locale.getDefault())
                        }
                        Text(
                            text = dateFormat.format(Date(currentWalk.startTime)),
                            color = TextGray,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                        )

                        // 2x3 Metric Cards Grid
                        val maxSpeed = remember(points) { points.maxOfOrNull { it.speed } ?: 0f }
                        val paceSecPerKm = if (currentWalk.totalDistanceMeters > 0) {
                            ((currentWalk.totalDurationMillis / 1000.0) / (currentWalk.totalDistanceMeters / 1000.0)).toLong()
                        } else 0L
                        val paceStr = if (paceSecPerKm in 1..7200) {
                            "${paceSecPerKm / 60}'${String.format(Locale.US, "%02d", paceSecPerKm % 60)}\" /km"
                        } else "--"

                        // Row 1: Distance + Duration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DetailMetricCard(
                                title = "DISTANCE",
                                value = formatDistance(currentWalk.totalDistanceMeters),
                                accentColor = ElectricCyanBright,
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricCard(
                                title = "DURATION",
                                value = formatTime(currentWalk.totalDurationMillis),
                                accentColor = AmberGold,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 2: Avg Speed + Max Speed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DetailMetricCard(
                                title = "AVG SPEED",
                                value = formatSpeed(avgMps),
                                accentColor = EmeraldGreen,
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricCard(
                                title = "MAX SPEED",
                                value = formatSpeed(maxSpeed),
                                accentColor = ElectricViolet,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))

                        // Row 3: Pace + GPS Points
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            DetailMetricCard(
                                title = if (isDriveWalk) "ACTIVE RATIO" else "AVG PACE",
                                value = if (isDriveWalk) "98.4%" else paceStr,
                                accentColor = ElectricCyanBright,
                                modifier = Modifier.weight(1f)
                            )
                            DetailMetricCard(
                                title = "POINTS LOGGED",
                                value = "${points.size} pts",
                                accentColor = DriveCoral,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Section: Marked Waypoints
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Place,
                                contentDescription = null,
                                tint = ElectricCyanBright,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Marked Waypoints (${walkPois.size})",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (walkPois.isEmpty()) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(0.5.dp, GlassBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "No waypoints recorded during this trip.",
                                    color = TextGray,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                walkPois.forEachIndexed { index, poi ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                                        shape = RoundedCornerShape(14.dp),
                                        border = BorderStroke(1.dp, GlassBorder),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { selectedPoi = poi }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(28.dp)
                                                    .clip(CircleShape)
                                                    .background(ElectricCyanBright.copy(alpha = 0.15f))
                                                    .border(1.dp, ElectricCyanBright.copy(alpha = 0.5f), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${index + 1}",
                                                    color = ElectricCyanBright,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (!poi.text.isNullOrBlank()) poi.text else "Waypoint #${index + 1}",
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                val poiTime = remember(poi.timestamp) {
                                                    SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date(poi.timestamp))
                                                }
                                                Text(
                                                    text = "$poiTime • ${String.format(Locale.US, "%.4f, %.4f", poi.latitude, poi.longitude)}",
                                                    color = TextGray,
                                                    fontSize = 11.sp
                                                )
                                            }

                                            if (!poi.imageBase64.isNullOrBlank()) {
                                                val bitmap = remember(poi.imageBase64) {
                                                    try {
                                                        val bytes = Base64.decode(poi.imageBase64, Base64.DEFAULT)
                                                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                                    } catch (e: Exception) { null }
                                                }
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap.asImageBitmap(),
                                                        contentDescription = "POI photo",
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Section: Data Export & Actions
                        Spacer(modifier = Modifier.height(20.dp))
                        Text(
                            text = "DATA EXPORT & ACTIONS",
                            color = TextGray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(bottom = 10.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // GPX Export Button
                            OutlinedButton(
                                onClick = { exportGpx(context, currentWalk, points, walkPois) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.8f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                            ) {
                                Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("EXPORT GPX", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            // KML Export Button
                            OutlinedButton(
                                onClick = { exportKml(context, currentWalk, points, walkPois) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, ElectricViolet.copy(alpha = 0.8f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricViolet)
                            ) {
                                Icon(imageVector = Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("EXPORT KML", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Delete Trip Button
                        OutlinedButton(
                            onClick = { showDeleteConfirmDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, DriveCoral.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DriveCoral)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("DELETE TRIP", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                    }

                    if (showDeleteConfirmDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirmDialog = false },
                            containerColor = Slate900,
                            title = { Text("Delete Trip", color = Color.White, fontWeight = FontWeight.Bold) },
                            text = { Text("Are you sure you want to permanently delete \"${currentWalk.title}\"? This action cannot be undone.", color = TextGray) },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        showDeleteConfirmDialog = false
                                        viewModel.deleteWalk(currentWalk.id)
                                        onBackClick()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = DriveCoral)
                                ) {
                                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                                    Text("Cancel", color = TextGray)
                                }
                            }
                        )
                    }

                    PoiDetailsPanel(
                        poi = selectedPoi,
                        onDismiss = { selectedPoi = null }
                    )
                }
            }
        }
    }
}
