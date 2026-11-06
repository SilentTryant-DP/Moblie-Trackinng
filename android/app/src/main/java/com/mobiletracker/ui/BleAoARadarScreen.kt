package com.mobiletracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiletracker.ui.theme.*
import com.mobiletracker.viewmodel.TrackerViewModel
import kotlin.math.*

@Composable
fun BleAoARadarScreen(viewModel: TrackerViewModel) {
    val azimuth by viewModel.aoaAzimuth.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("BLE 5.1 DIRECTION FINDING", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Angle of Arrival (AoA) Radar", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text("Tracking Constant Tone Extension (CTE) @ 2.44 GHz", color = TextSecondary, fontSize = 12.sp)
        }

        // Radar Canvas Sweep
        Box(
            modifier = Modifier
                .size(280.dp)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.width / 2

                // Concentric range circles
                drawCircle(color = SurfaceDark, radius = radius, center = center)
                drawCircle(color = Color(0xFF334155), radius = radius * 0.75f, center = center, style = Stroke(width = 2f))
                drawCircle(color = Color(0xFF334155), radius = radius * 0.50f, center = center, style = Stroke(width = 2f))
                drawCircle(color = Color(0xFF334155), radius = radius * 0.25f, center = center, style = Stroke(width = 2f))

                // Crosshair lines
                drawLine(color = Color(0xFF334155), start = Offset(center.x, 0f), end = Offset(center.x, size.height), strokeWidth = 1.5f)
                drawLine(color = Color(0xFF334155), start = Offset(0f, center.y), end = Offset(size.width, center.y), strokeWidth = 1.5f)

                // Azimuth Vector (Angle of Arrival)
                azimuth?.let { az ->
                    val angleRad = Math.toRadians(az - 90.0)
                    val targetX = center.x + (radius * 0.7f * cos(angleRad)).toFloat()
                    val targetY = center.y + (radius * 0.7f * sin(angleRad)).toFloat()

                    // Line to target
                    drawLine(
                        color = AccentGreen,
                        start = center,
                        end = Offset(targetX, targetY),
                        strokeWidth = 4f
                    )

                    // Target Blip
                    drawCircle(color = AccentGreen, radius = 10f, center = Offset(targetX, targetY))
                    drawCircle(color = Color.White, radius = 4f, center = Offset(targetX, targetY))
                }
            }
        }

        // Metrics Footer Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("AZIMUTH", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("${String.format("%.1f", azimuth ?: 0.0)}°", color = AccentGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DISTANCE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("1.8 m", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("RSSI", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("-58 dBm", color = PrimaryBlue, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
