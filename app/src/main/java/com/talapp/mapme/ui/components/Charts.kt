package com.talapp.mapme.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.talapp.mapme.data.Walk
import com.talapp.mapme.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Custom Canvas 7-day activity bar chart for distance tracking.
 */
@Composable
fun WeeklyActivityChart(walks: List<Walk>) {
    val currentMillis = System.currentTimeMillis()
    val dayMillis = 24 * 60 * 60 * 1000L

    val dailyDistances = remember(walks) {
        val distances = FloatArray(7)
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)

        for (i in 0..6) {
            val dateStr = sdf.format(Date(currentMillis - (6 - i) * dayMillis))
            var distSum = 0f
            for (walk in walks) {
                val walkDateStr = sdf.format(Date(walk.startTime))
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
        val sdf = SimpleDateFormat("E", Locale.US)
        for (i in 0..6) {
            labels.add(sdf.format(Date(currentMillis - (6 - i) * dayMillis)).take(1))
        }
        labels
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Slate800),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, GlassBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
