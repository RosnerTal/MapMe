package com.talapp.mapme.ui

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talapp.mapme.data.Walk
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.theme.*
import com.talapp.mapme.ui.components.OsmMapView
import com.talapp.mapme.ui.components.PoiDetailsPanel
import com.talapp.mapme.util.formatDistance
import com.talapp.mapme.util.formatSpeed
import com.talapp.mapme.util.formatTime

@Composable
fun AllWalksMapScreen(
    viewModel: WalkViewModel,
    onBackClick: () -> Unit,
    onWalkClick: ((Long) -> Unit)? = null
) {
    val walks by viewModel.allWalks.collectAsState()
    val isDarkMap by viewModel.isDarkMap.collectAsState()
    val showWalks by viewModel.showWalks.collectAsState()
    val showDrives by viewModel.showDrives.collectAsState()
    val showPois by viewModel.showPois.collectAsState()
    var selectedPoi by remember { mutableStateOf<WalkPoi?>(null) }
    var selectedWalk by remember { mutableStateOf<Walk?>(null) }
    val totalDistanceMeters = remember(walks) { walks.sumOf { it.totalDistanceMeters } }

    Box(modifier = Modifier.fillMaxSize()) {
        // Map showing all past walks
        OsmMapView(
            modifier = Modifier.fillMaxSize(),
            pastWalks = walks,
            selectedWalkId = selectedWalk?.id,
            onWalkClick = { selectedWalk = it },
            isDarkMap = isDarkMap,
            showWalks = showWalks,
            showDrives = showDrives,
            showPois = showPois,
            onPoiClick = { selectedPoi = it }
        )

        // Top Floating Control Bar: Back Button + Branded All-Time Activity Card + Map Style Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Back Button
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

            // Branded Stats Card (Center)
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, GlassCardBorderCyan)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = null,
                            tint = ElectricCyanBright,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "MapMe",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "• All-Time Activity",
                            color = TextGray,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Lifetime Travel: ${formatDistance(totalDistanceMeters)} • ${walks.size} Trips",
                        color = ElectricCyanBright,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Map Style Toggle Button
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

        // Floating Speed Filters Panel (Glassmorphic, below top bar)
        Card(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 68.dp, end = 16.dp)
                .align(Alignment.TopEnd)
                .width(136.dp),
            colors = CardDefaults.cardColors(containerColor = GlassCardBg),
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

        // Bottom "Consolidated Corridors" Showcase Card
        val context = LocalContext.current
        AnimatedVisibility(
            visible = selectedPoi == null && selectedWalk == null,
            enter = fadeIn() + androidx.compose.animation.slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + androidx.compose.animation.slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, GlassCardBorderCyan),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NeonCyan)
                        )
                        Text(
                            text = "CONSOLIDATED CORRIDORS",
                            color = NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Displaying ${walks.size} combined historical paths. Overlapping routes merge into master corridors.",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // View Trips Button (Outlined Cyan)
                        OutlinedButton(
                            onClick = { onBackClick() },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.8f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonCyan)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("VIEW TRIPS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        // Share Map Stats Button (Filled Cyan)
                        Button(
                            onClick = {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Explore my MapMe territory map! 🗺️ Logged ${formatDistance(totalDistanceMeters)} across ${walks.size} trips with consolidated corridors."
                                    )
                                    putExtra(Intent.EXTRA_SUBJECT, "My MapMe Territory Map")
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Share Map Stats"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = Slate900
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("SHARE MAP", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                .padding(bottom = 20.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
        ) {
            if (selectedWalk != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = GlassCardBg),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(1.dp, GlassCardBorderCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedWalk!!.title,
                                color = Color.White,
                                fontSize = 16.sp,
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

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("DISTANCE", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = formatDistance(selectedWalk!!.totalDistanceMeters),
                                    color = NeonCyan,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Column {
                                Text("DURATION", color = TextGray, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = formatTime(selectedWalk!!.totalDurationMillis),
                                    color = ElectricViolet,
                                    fontSize = 15.sp,
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
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (onWalkClick != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { onWalkClick(selectedWalk!!.id) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(40.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Slate900)
                            ) {
                                Text("OPEN FULL ANALYTICS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
