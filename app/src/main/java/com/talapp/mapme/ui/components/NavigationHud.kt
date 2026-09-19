package com.talapp.mapme.ui.components

import androidx.car.app.navigation.model.Maneuver
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talapp.mapme.services.NavMode
import com.talapp.mapme.services.NavRoute
import com.talapp.mapme.services.NavStep
import com.talapp.mapme.theme.*
import com.talapp.mapme.util.formatDistance
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * Top turn-by-turn instruction banner displayed on the phone during active navigation.
 */
@Composable
fun NavigationTopBanner(
    route: NavRoute,
    currentStepIndex: Int,
    distanceToNextStepMeters: Double,
    isVoiceMuted: Boolean,
    onToggleVoiceMute: () -> Unit,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = route.steps
    val currentStep = steps.getOrNull(currentStepIndex) ?: steps.lastOrNull()
    val nextStep = steps.getOrNull(currentStepIndex + 1)

    val isWalk = (route.mode == NavMode.WALKING)
    val accentColor = if (isWalk) NeonCyan else DriveCoral
    val bannerBorder = BorderStroke(1.dp, if (isWalk) GlassCardBorderCyan else DriveCoral.copy(alpha = 0.5f))

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(20.dp),
        border = bannerBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Main instruction row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Large Turn Maneuver Icon
                val maneuverIcon = getManeuverIcon(currentStep?.maneuverType ?: Maneuver.TYPE_STRAIGHT)
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.18f))
                        .border(1.5.dp, accentColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = maneuverIcon,
                        contentDescription = "Maneuver",
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Distance countdown + cue instruction
                Column(modifier = Modifier.weight(1f)) {
                    val distText = if (distanceToNextStepMeters > 0) {
                        "In ${formatDistance(distanceToNextStepMeters)}"
                    } else {
                        "Ahead"
                    }
                    Text(
                        text = distText,
                        color = accentColor,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp
                    )
                    Text(
                        text = currentStep?.cue ?: "Follow highlighted route",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Action buttons: Mute + Close
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Audio toggle
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Slate800)
                            .border(1.dp, GlassBorder, CircleShape)
                            .clickable { onToggleVoiceMute() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isVoiceMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Toggle Audio",
                            tint = if (isVoiceMuted) TextGray else NeonCyan,
                            modifier = Modifier.size(17.dp)
                        )
                    }

                    // Exit Navigation
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(Color(0x33EF4444))
                            .border(1.dp, Color(0x66EF4444), CircleShape)
                            .clickable { onStopNavigation() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "End Navigation",
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            // Next step preview teaser
            if (nextStep != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = GlassBorder, thickness = 0.8.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "THEN:",
                        color = TextGray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = nextStep.cue,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Bottom trip progress bar showing remaining distance, estimated time, and ETA.
 */
@Composable
fun NavigationBottomBar(
    route: NavRoute,
    remainingDistanceMeters: Double,
    remainingDurationSeconds: Double,
    onStopNavigation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val durationMinutes = (remainingDurationSeconds / 60.0).roundToInt()
    val durationText = if (durationMinutes >= 60) {
        "${durationMinutes / 60}h ${durationMinutes % 60}m"
    } else {
        "$durationMinutes min"
    }

    val etaText = remember(remainingDurationSeconds) {
        val etaMillis = System.currentTimeMillis() + (remainingDurationSeconds * 1000).toLong()
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        sdf.format(Date(etaMillis))
    }

    val isWalk = (route.mode == NavMode.WALKING)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = durationText,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "•",
                        color = TextGray,
                        fontSize = 14.sp
                    )
                    Text(
                        text = formatDistance(remainingDistanceMeters),
                        color = if (isWalk) NeonCyan else DriveCoral,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "ETA $etaText to ${route.destinationTitle}",
                    color = TextGray,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Button(
                onClick = onStopNavigation,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0x26EF4444)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0x66EF4444)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "End",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * Maps Maneuver integer code to an appropriate Compose ImageVector icon.
 */
fun getManeuverIcon(maneuverType: Int): ImageVector {
    return when (maneuverType) {
        Maneuver.TYPE_DESTINATION -> Icons.Default.Place
        Maneuver.TYPE_DEPART -> Icons.Default.Navigation
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW -> Icons.AutoMirrored.Filled.RotateRight
        Maneuver.TYPE_TURN_NORMAL_LEFT, Maneuver.TYPE_TURN_SHARP_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
        Maneuver.TYPE_TURN_NORMAL_RIGHT, Maneuver.TYPE_TURN_SHARP_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
        Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> Icons.Default.Refresh
        Maneuver.TYPE_FORK_LEFT -> Icons.AutoMirrored.Filled.ArrowBack
        Maneuver.TYPE_FORK_RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
        else -> Icons.Default.ArrowUpward
    }
}
