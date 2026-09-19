package com.talapp.mapme.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.services.NavMode
import com.talapp.mapme.theme.*
import com.talapp.mapme.ui.components.AddPoiDialog
import com.talapp.mapme.ui.components.DestinationSearchSheet
import com.talapp.mapme.ui.components.NavigationBottomBar
import com.talapp.mapme.ui.components.NavigationTopBanner
import com.talapp.mapme.ui.components.OsmMapView
import com.talapp.mapme.ui.components.PoiDetailsPanel
import com.talapp.mapme.util.formatDistance
import com.talapp.mapme.util.formatTime

@Composable
fun RecordScreen(
    viewModel: WalkViewModel,
    isDrive: Boolean,
    onBackClick: () -> Unit
) {
    val isTracking by viewModel.isTracking.collectAsState(initial = false)
    val points by viewModel.activePoints.collectAsState(initial = emptyList())
    val activePois by viewModel.activePois.collectAsState(initial = emptyList())
    val distance by viewModel.activeDistanceMeters.collectAsState(initial = 0.0)
    val durationSeconds by viewModel.activeDurationSeconds.collectAsState(initial = 0L)
    val walks by viewModel.allWalks.collectAsState()
    val isDarkMap by viewModel.isDarkMap.collectAsState()
    val showPois by viewModel.showPois.collectAsState()

    // Navigation State
    val activeNavRoute by viewModel.activeNavRoute.collectAsState()
    val isNavigating by viewModel.isNavigating.collectAsState()
    val currentStepIndex by viewModel.currentStepIndex.collectAsState()
    val distanceToNextStepMeters by viewModel.distanceToNextStepMeters.collectAsState()
    val remainingDistanceMeters by viewModel.remainingDistanceMeters.collectAsState()
    val remainingDurationSeconds by viewModel.remainingDurationSeconds.collectAsState()
    val isVoiceMuted by viewModel.isVoiceMuted.collectAsState()

    var showSearchSheet by remember { mutableStateOf(false) }
    var showAddPoiDialog by remember { mutableStateOf(false) }
    var selectedPoi by remember { mutableStateOf<WalkPoi?>(null) }
    var showFilters by remember { mutableStateOf(false) }

    val showWalks by viewModel.showWalks.collectAsState()
    val showDrives by viewModel.showDrives.collectAsState()
    val currentLoc = points.lastOrNull()

    // Automatically trigger starting the track with the selected mode when entering this screen
    LaunchedEffect(isDrive) {
        if (!isTracking && points.isEmpty()) {
            viewModel.startWalk(isDrive)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map Background (shows past walks within 5km, active points, and activeNavRoute)
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            points = points,
            currentLocation = currentLoc,
            pastWalks = walks,
            showPastWalksRadiusMeters = 5000.0,
            isDarkMap = isDarkMap,
            showWalks = showWalks,
            showDrives = showDrives,
            showPois = showPois,
            activePois = activePois,
            onPoiClick = { selectedPoi = it },
            isDriveRecording = isDrive,
            activeNavRoute = activeNavRoute
        )

        // Top Floating Area: Turn-by-Turn Navigation Banner OR Top Floating Bar with Search
        if (isNavigating && activeNavRoute != null) {
            NavigationTopBanner(
                route = activeNavRoute!!,
                currentStepIndex = currentStepIndex,
                distanceToNextStepMeters = distanceToNextStepMeters,
                isVoiceMuted = isVoiceMuted,
                onToggleVoiceMute = { viewModel.toggleVoiceMute() },
                onStopNavigation = { viewModel.stopInAppNavigation() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Floating Back Button
                Box(
                    modifier = Modifier
                        .size(44.dp)
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

                // Floating Search Bar Pill
                Card(
                    colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, if (isDrive) GlassCardBorder else GlassCardBorderCyan),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .clickable { showSearchSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = if (isDrive) DriveCoral else NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isDrive) "Search destination (Drive)..." else "Search destination (Walk)...",
                            color = TextGray,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                // Floating Map Style Toggle Button
                Box(
                    modifier = Modifier
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
            }
        }

        // Floating Filter Toggle & Panel (Top Right, below style button)
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 64.dp, end = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(GlassBackground)
                    .border(1.dp, GlassBorder, CircleShape)
                    .clickable { showFilters = !showFilters },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Filters",
                    tint = if (showFilters) NeonCyan else TextGray,
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(
                visible = showFilters,
                enter = fadeIn() + androidx.compose.animation.slideInVertically(),
                exit = fadeOut() + androidx.compose.animation.slideOutVertically()
            ) {
                Card(
                    modifier = Modifier.width(136.dp),
                    colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.clickable { viewModel.toggleShowPois() }
                        ) {
                            Checkbox(
                                checked = showPois,
                                onCheckedChange = { viewModel.toggleShowPois() },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = ElectricViolet,
                                    uncheckedColor = TextGray,
                                    checkmarkColor = Color.White
                                ),
                                modifier = Modifier.size(18.dp)
                            )
                            Text("POIs (Markers)", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
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

        // Bottom-Left Live Telemetry HUD Card
        AnimatedVisibility(
            visible = selectedPoi == null,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally(initialOffsetX = { -it }),
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 16.dp)
                .width(185.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GlassCardBorderCyan)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(NeonCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "LIVE TELEMETRY",
                            color = NeonCyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.2.sp
                        )
                    }

                    // Speed Readout
                    val speed = currentLoc?.speed ?: 0f
                    val speedKmh = speed * 3.6f
                    Text("CURRENT SPEED", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(top = 1.dp)
                    ) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", speedKmh),
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "km/h",
                            color = TextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 10.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Brush.horizontalGradient(listOf(ElectricCyanBright, ElectricCyanBright.copy(alpha = 0.15f))))
                    )

                    // Distance Readout
                    Text("DISTANCE COVERED", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = formatDistance(distance),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp, bottom = 10.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Brush.horizontalGradient(listOf(DriveCoral, DriveCoral.copy(alpha = 0.15f))))
                    )

                    // Time Readout
                    Text("TIME ELAPSED", color = TextGray, fontSize = 8.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = formatTime(durationSeconds * 1000),
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp)
                            .height(2.5.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(Brush.horizontalGradient(listOf(AmberGold, AmberGold.copy(alpha = 0.15f))))
                    )
                }
            }
        }

        // Bottom-Right Floating Action Cluster
        AnimatedVisibility(
            visible = selectedPoi == null,
            enter = fadeIn() + androidx.compose.animation.slideInHorizontally(initialOffsetX = { it }),
            exit = fadeOut() + androidx.compose.animation.slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, end = 16.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Add POI Button (Floating Glass Circle)
                if (isTracking && points.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassCardBg)
                            .border(1.dp, GlassCardBorderCyan, CircleShape)
                            .clickable { showAddPoiDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddLocation,
                            contentDescription = "Add Point of Interest",
                            tint = ElectricCyanBright,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Waze shortcut (Visible when recording a Drive)
                if (isDrive) {
                    val context = LocalContext.current
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF33CCFF))
                            .clickable {
                                if (!isTracking) viewModel.startWalk(true)
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("waze://?navigate=yes")
                                    ).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("market://details?id=com.waze")
                                        )
                                        context.startActivity(intent)
                                    } catch (e2: Exception) {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = "Launch Waze",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34A853))
                            .clickable {
                                if (!isTracking) viewModel.startWalk(true)
                                try {
                                    val intent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("google.navigation:mode=d")
                                    ).apply {
                                        setPackage("com.google.android.apps.maps")
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        val intent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("market://details?id=com.google.android.apps.maps")
                                        )
                                        context.startActivity(intent)
                                    } catch (e2: Exception) {}
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = "Launch Google Maps",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Pause / Resume Action Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(if (isTracking) Color(0xFF2563EB) else EmeraldGreen)
                        .clickable {
                            if (isTracking) {
                                viewModel.pauseWalk()
                            } else {
                                viewModel.startWalk(isDrive)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isTracking) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isTracking) "Pause" else "Resume",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Search Destination Button (Floating Glass Circle)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(GlassCardBg)
                        .border(1.dp, if (isDrive) GlassCardBorder else GlassCardBorderCyan, CircleShape)
                        .clickable { showSearchSheet = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search Destination",
                        tint = if (isDrive) DriveCoral else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Stop & Save Action Button
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(DriveCoral)
                        .clickable {
                            viewModel.stopWalk()
                            onBackClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop & Save",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        // Navigation Bottom Progress Card (when navigating)
        if (isNavigating && activeNavRoute != null) {
            NavigationBottomBar(
                route = activeNavRoute!!,
                remainingDistanceMeters = remainingDistanceMeters,
                remainingDurationSeconds = remainingDurationSeconds,
                onStopNavigation = { viewModel.stopInAppNavigation() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 190.dp)
            )
        }

        // Destination Search & Route Preview Sheet
        if (showSearchSheet) {
            DestinationSearchSheet(
                viewModel = viewModel,
                initialMode = if (isDrive) NavMode.DRIVING else NavMode.WALKING,
                onDismiss = { showSearchSheet = false },
                onStartNavigation = { route ->
                    viewModel.startInAppNavigation(route)
                    showSearchSheet = false
                }
            )
        }
    }
}
