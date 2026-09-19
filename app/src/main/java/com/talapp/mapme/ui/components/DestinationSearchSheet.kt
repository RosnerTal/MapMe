package com.talapp.mapme.ui.components

import android.location.Location
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.talapp.mapme.services.NavMode
import com.talapp.mapme.services.NavRoute
import com.talapp.mapme.services.NavSearchResult
import com.talapp.mapme.theme.*
import com.talapp.mapme.ui.WalkViewModel
import com.talapp.mapme.util.formatDistance
import kotlin.math.roundToInt

@Composable
fun DestinationSearchSheet(
    viewModel: WalkViewModel,
    initialMode: NavMode = NavMode.WALKING,
    onDismiss: () -> Unit,
    onStartNavigation: (NavRoute) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val selectedDestination by viewModel.selectedDestination.collectAsState()
    val previewRoute by viewModel.previewNavRoute.collectAsState()
    val isCalculatingRoute by viewModel.isCalculatingRoute.collectAsState()
    val navMode by viewModel.navMode.collectAsState()
    val userLocation by viewModel.lastKnownLocation.collectAsState()

    LaunchedEffect(initialMode) {
        viewModel.setNavMode(initialMode)
    }

    val quickCategories = remember(navMode) {
        val base = mutableListOf(
            "Coffee ☕" to "coffee",
            "Parking 🅿️" to "parking",
            "Supermarket 🛒" to "supermarket",
            "Park 🌳" to "park",
            "Pharmacy 🏥" to "pharmacy"
        )
        if (navMode == NavMode.DRIVING) {
            base.add(1, "Gas Station ⛽" to "gas station")
        }
        base
    }

    Dialog(
        onDismissRequest = {
            viewModel.clearSearch()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f))
                .clickable {
                    viewModel.clearSearch()
                    onDismiss()
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .clickable(enabled = false) {} // Prevent click-through to background
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                border = BorderStroke(1.dp, GlassBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Top Drag Indicator & Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Choose Destination",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Search any place for turn-by-turn routing",
                                color = TextGray,
                                fontSize = 12.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Slate800)
                                .border(1.dp, GlassBorder, CircleShape)
                                .clickable {
                                    viewModel.clearSearch()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TextGray,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Mode Toggle: Walk vs Drive
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Slate800)
                            .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Walking Tab
                        val isWalk = (navMode == NavMode.WALKING)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isWalk) NeonCyan.copy(alpha = 0.2f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isWalk) NeonCyan else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.setNavMode(NavMode.WALKING) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                    contentDescription = "Walk",
                                    tint = if (isWalk) NeonCyan else TextGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Walking",
                                    color = if (isWalk) Color.White else TextGray,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Driving Tab
                        val isDrive = (navMode == NavMode.DRIVING)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDrive) DriveCoral.copy(alpha = 0.2f) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isDrive) DriveCoral else Color.Transparent,
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { viewModel.setNavMode(NavMode.DRIVING) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = "Drive",
                                    tint = if (isDrive) DriveCoral else TextGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Driving",
                                    color = if (isDrive) Color.White else TextGray,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Search Input Box
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.searchDestinations(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        placeholder = {
                            Text(
                                text = "Search street, restaurant, city...",
                                color = TextGray,
                                fontSize = 14.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = NeonCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.clearSearch()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = TextGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Slate800,
                            unfocusedContainerColor = Slate800.copy(alpha = 0.7f),
                            focusedBorderColor = if (navMode == NavMode.WALKING) NeonCyan else DriveCoral,
                            unfocusedBorderColor = GlassBorder,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Quick Category Shortcut Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for ((label, queryTag) in quickCategories) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Slate800)
                                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
                                    .clickable {
                                        searchQuery = queryTag
                                        viewModel.searchDestinations(queryTag)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Route Preview Card (when destination selected and route calculated)
                    AnimatedVisibility(
                        visible = selectedDestination != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = Slate800),
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(
                                1.dp,
                                if (navMode == NavMode.WALKING) NeonCyan.copy(alpha = 0.5f) else DriveCoral.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedDestination?.title ?: "Destination",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = selectedDestination?.subtitle ?: "",
                                            color = TextGray,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (isCalculatingRoute) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(22.dp),
                                            color = NeonCyan,
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }

                                if (previewRoute != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    val r = previewRoute!!
                                    val durationMinutes = (r.totalDurationSeconds / 60.0).roundToInt()
                                    val durationText = if (durationMinutes >= 60) {
                                        "${durationMinutes / 60}h ${durationMinutes % 60}m"
                                    } else {
                                        "$durationMinutes min"
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Text(
                                                text = formatDistance(r.totalDistanceMeters),
                                                color = if (navMode == NavMode.WALKING) NeonCyan else DriveCoral,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Text(
                                                text = "•",
                                                color = TextGray,
                                                fontSize = 16.sp
                                            )
                                            Text(
                                                text = durationText,
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        Button(
                                            onClick = {
                                                onStartNavigation(r)
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (navMode == NavMode.WALKING) NeonCyan else DriveCoral
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.Navigation,
                                                    contentDescription = "Start",
                                                    tint = Color.Black,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = "Start",
                                                    color = Color.Black,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Search Results List
                    if (isSearching) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = NeonCyan,
                                modifier = Modifier.size(32.dp),
                                strokeWidth = 3.dp
                            )
                        }
                    } else if (searchResults.isEmpty() && searchQuery.trim().length >= 2) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No places found for '$searchQuery'",
                                color = TextGray,
                                fontSize = 14.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(searchResults) { result ->
                                val distanceText = if (userLocation != null) {
                                    val destLoc = Location("Dest").apply {
                                        latitude = result.latitude
                                        longitude = result.longitude
                                    }
                                    val dist = userLocation!!.distanceTo(destLoc)
                                    formatDistance(dist.toDouble())
                                } else null

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Slate800.copy(alpha = 0.6f))
                                        .border(1.dp, GlassBorder, RoundedCornerShape(14.dp))
                                        .clickable {
                                            viewModel.selectDestinationAndPreviewRoute(result, navMode)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (navMode == NavMode.WALKING) NeonCyan.copy(alpha = 0.15f)
                                                else DriveCoral.copy(alpha = 0.15f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Place,
                                            contentDescription = "Place",
                                            tint = if (navMode == NavMode.WALKING) NeonCyan else DriveCoral,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = result.title,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = result.subtitle,
                                            color = TextGray,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (distanceText != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = distanceText,
                                            color = NeonCyan,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
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
