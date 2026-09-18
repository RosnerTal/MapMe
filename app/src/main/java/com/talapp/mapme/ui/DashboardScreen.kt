package com.talapp.mapme.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talapp.mapme.data.Walk
import com.talapp.mapme.theme.*
import com.talapp.mapme.ui.components.StatCard
import com.talapp.mapme.ui.components.WalkHistoryItem
import com.talapp.mapme.ui.components.WeeklyActivityChart
import com.talapp.mapme.util.formatDistance
import com.talapp.mapme.util.formatTime

@Composable
fun DashboardScreen(
    viewModel: WalkViewModel,
    onStartWalkClick: (Boolean) -> Unit,
    onWalkClick: (Long) -> Unit,
    onViewMapClick: () -> Unit,
    onGoogleSignInClick: () -> Unit = {}
) {
    val walks by viewModel.allWalks.collectAsState()
    val totalWalksCount by viewModel.totalWalks.collectAsState()
    val totalDistance by viewModel.totalDistanceMeters.collectAsState()
    val totalDuration by viewModel.totalDurationMillis.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val timeframe by viewModel.selectedTimeframe.collectAsState()
    val filteredWalks by viewModel.filteredWalks.collectAsState()
    val lastSyncedTime by viewModel.lastSyncedTime.collectAsState()

    var showSyncPanel by remember { mutableStateOf(false) }

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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 200.dp)
        ) {
            // Elegant Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.Image(
                                painter = androidx.compose.ui.res.painterResource(id = com.talapp.mapme.R.mipmap.ic_launcher),
                                contentDescription = "MapMe Icon",
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "MapMe",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NeonCyan.copy(alpha = 0.15f))
                                    .border(1.dp, NeonCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "v${com.talapp.mapme.BuildConfig.VERSION_NAME}",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
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
                            .border(1.dp, GlassBorder, CircleShape)
                            .clickable { showSyncPanel = !showSyncPanel },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Profile",
                            tint = if (showSyncPanel) ElectricViolet else NeonCyan
                        )
                    }
                }
            }

            // Cloud Sync Panel (toggled via profile avatar button click)
            item {
                AnimatedVisibility(
                    visible = showSyncPanel
                ) {
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
                                        text = "Cloud Sync Dashboard",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Sign in to backup & view tracks online",
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
                                        text = lastSyncedTime ?: "Sync active",
                                        color = NeonCyan,
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
                                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                                            contentDescription = "Sign Out",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Timeframe Selector Pills
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate800.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val timeframeOptions = listOf(
                        WalkViewModel.Timeframe.WEEK to "1 Week",
                        WalkViewModel.Timeframe.MONTH to "1 Month",
                        WalkViewModel.Timeframe.THREE_MONTHS to "3 Months",
                        WalkViewModel.Timeframe.LIFETIME to "Lifetime"
                    )

                    timeframeOptions.forEach { (option, label) ->
                        val isSelected = timeframe == option
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonCyan else Color.Transparent)
                                .clickable { viewModel.setTimeframe(option) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color.Black else TextGray,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Stats Container
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatCard(
                        modifier = Modifier.weight(1f),
                        title = "Tracks Count",
                        value = totalWalksCount.toString(),
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
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
            }

            item {
                WeeklyActivityChart(walks = filteredWalks)
            }

            // Walks History list header
            item {
                Text(
                    text = "Travel History",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Empty state or History list items
            if (filteredWalks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.Map,
                                contentDescription = "Map Placeholder",
                                tint = Slate600,
                                modifier = Modifier.size(72.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "No records logged yet",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap the start button below to record your first exploration track!",
                                color = TextGray,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                items(filteredWalks, key = { it.id }) { walk ->
                    WalkHistoryItem(
                        walk = walk,
                        onClick = { onWalkClick(walk.id) },
                        onDelete = { viewModel.deleteWalk(walk.id) }
                    )
                }
            }
        }

        // Bottom Actions (Column layout to hold Walk/Drive side-by-side and Explore Map centered underneath)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Start Walk Button
                Button(
                    onClick = { onStartWalkClick(false) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .weight(1f)
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
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = "Walk",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Walk",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Start Drive Button
                Button(
                    onClick = { onStartWalkClick(true) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues(),
                    shape = RoundedCornerShape(30.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(30.dp))
                        .border(
                            BorderStroke(1.dp, Brush.linearGradient(listOf(Color(0xFFEF4444), ElectricViolet))),
                            RoundedCornerShape(30.dp)
                        )
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFEF4444).copy(alpha = 0.85f),
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
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = "Drive",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Drive",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Explore Map Button (All Walks Map)
            Button(
                onClick = onViewMapClick,
                colors = ButtonDefaults.buttonColors(containerColor = Slate800.copy(alpha = 0.6f)),
                contentPadding = PaddingValues(),
                shape = RoundedCornerShape(30.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
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
                        text = "Explore Combined Map",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
