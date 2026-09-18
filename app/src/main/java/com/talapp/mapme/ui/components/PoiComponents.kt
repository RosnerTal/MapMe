package com.talapp.mapme.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.talapp.mapme.data.WalkPoi
import com.talapp.mapme.theme.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Encodes an image Uri to a compressed Base64 JPEG string.
 */
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

/**
 * Bottom panel for viewing POI details with photo preview.
 */
@Composable
fun BoxScope.PoiDetailsPanel(
    poi: WalkPoi?,
    onDismiss: () -> Unit
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = poi != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
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
                    // Drag indicator
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
                                DateFormat.getDateTimeInstance(
                                    DateFormat.MEDIUM,
                                    DateFormat.SHORT
                                ).format(Date(poi.timestamp))
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

                                Image(
                                    bitmap = imageBitmap,
                                    contentDescription = "POI Photo Preview",
                                    modifier = Modifier
                                        .size(76.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.5.dp, NeonCyan.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                                        .clickable { showFullScreenImage = true },
                                    contentScale = ContentScale.Crop
                                )

                                if (showFullScreenImage) {
                                    Dialog(
                                        onDismissRequest = { showFullScreenImage = false }
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clickable { showFullScreenImage = false },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Image(
                                                bitmap = imageBitmap,
                                                contentDescription = "POI Photo Full",
                                                modifier = Modifier
                                                    .fillMaxWidth(0.95f)
                                                    .aspectRatio(1f)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .border(1.5.dp, GlassBorder, RoundedCornerShape(16.dp)),
                                                contentScale = ContentScale.Fit
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

/**
 * Dialog displaying full details and photo for a POI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoiDetailsDialog(
    poi: WalkPoi,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                    DateFormat.getDateTimeInstance(
                        DateFormat.MEDIUM,
                        DateFormat.SHORT
                    ).format(Date(poi.timestamp))
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
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "POI Photo",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text("Error loading photo", color = Color.Red, fontSize = 12.sp)
                    }
                }
            }
        }
    )
}

/**
 * Dialog for adding a new Point of Interest with text note and optional camera/gallery photo.
 */
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
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
                            Image(
                                bitmap = imageBitmap,
                                contentDescription = "Attached Photo",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
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
                                val tempFile = File(context.cacheDir, "temp_poi_${System.currentTimeMillis()}.jpg")
                                val authority = "${context.packageName}.fileprovider"
                                val uri = FileProvider.getUriForFile(context, authority, tempFile)
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
