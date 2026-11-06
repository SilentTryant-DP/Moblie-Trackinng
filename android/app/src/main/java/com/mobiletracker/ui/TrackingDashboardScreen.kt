package com.mobiletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobiletracker.core.engine.LocationSourceType
import com.mobiletracker.ui.theme.*
import com.mobiletracker.viewmodel.TrackerViewModel

@Composable
fun TrackingDashboardScreen(viewModel: TrackerViewModel) {
    val fusedLocation by viewModel.fusedLocation.collectAsState()
    val powerState by viewModel.powerState.collectAsState()
    val incomingCall by viewModel.incomingCall.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mirrored Call Banner (if active)
        incomingCall?.let { call ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B4B)),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("MIRRORED INCOMING CALL", color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(call.callerName, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(call.callerNumber, color = TextSecondary, fontSize = 13.sp)
                    }
                    Button(
                        onClick = { viewModel.dismissIncomingCall() },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed)
                    ) {
                        Text("Dismiss")
                    }
                }
            }
        }

        // Fused Location Hero Card
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("OPTIMAL FUSED LOCATION", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    fusedLocation?.let { fix ->
                        Surface(
                            color = AccentGreen.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "${fix.confidenceScore}% Confidence",
                                color = AccentGreen,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                fusedLocation?.let { fix ->
                    Text(
                        String.format("%.6f° N, %.6f° W", fix.latitude, kotlin.math.abs(fix.longitude)),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Estimated Accuracy: ±${String.format("%.1f", fix.fusedAccuracyMeters)} meters",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Primary Source: ${fix.primarySource.name}",
                        color = PrimaryBlue,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                } ?: run {
                    Text("Calculating location fix...", color = TextSecondary)
                }
            }
        }

        // 4-Way Tracking Modality Arbiter Status
        Text("4-WAY TRACKING MODALITY ARBITER", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        ModalityToggleCard(
            title = "1. GPS / GNSS Satellite Fix",
            subtitle = "Outdoor high-precision (±8.5m)",
            icon = Icons.Default.LocationOn,
            isEnabled = viewModel.isGpsHardwareOn,
            onToggle = { viewModel.toggleSource(LocationSourceType.GPS_GNSS) }
        )

        ModalityToggleCard(
            title = "2. BLE 5.1 Angle of Arrival (AoA)",
            subtitle = "Micro-proximity & direction finding (±1.8m)",
            icon = Icons.Default.Bluetooth,
            isEnabled = viewModel.isBleHardwareOn,
            onToggle = { viewModel.toggleSource(LocationSourceType.BLE_AOA) }
        )

        ModalityToggleCard(
            title = "3. Cell Tower Triangulation (R-Tree)",
            subtitle = "Offline OpenCelliD passive baseband (±140m)",
            icon = Icons.Default.CellTower,
            isEnabled = viewModel.isCellModemOn,
            onToggle = { viewModel.toggleSource(LocationSourceType.CELLULAR_RTREE) }
        )

        ModalityToggleCard(
            title = "4. P2P Mesh Peer Anchor",
            subtitle = "Cooperative Wi-Fi Direct peer fix (±35m)",
            icon = Icons.Default.Share,
            isEnabled = viewModel.isMeshActive,
            onToggle = { viewModel.toggleSource(LocationSourceType.P2P_MESH_ANCHOR) }
        )

        // Power State Machine Status
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("POWER STATE MACHINE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Current Mode: $powerState", color = AccentGreen, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Modem: 3GPP eDRX Paging (40.96s) | Safe Zone: Inactive", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ModalityToggleCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isEnabled) PrimaryBlue else TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = TextSecondary, fontSize = 11.sp)
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = PrimaryBlue
                )
            )
        }
    }
}
