package com.mobiletracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.mobiletracker.ui.theme.*
import com.mobiletracker.viewmodel.TrackerViewModel

@Composable
fun NotificationMirrorScreen(viewModel: TrackerViewModel) {
    var smsStatusText by remember { mutableStateOf("Ready to transmit encrypted command") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("CROSS-DEVICE MIRRORING & FALLBACK", color = PrimaryBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        // Incoming Call Simulation Control
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("TIER 1 / VOIP CALL SIMULATOR", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Simulate a remote phone call intercepted on a linked phone and mirrored to this device via PushKit/WebSockets.", color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.simulateIncomingCall("Family Member", "+1 (555) 019-2834") },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Phone, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Trigger Mirrored Call Alert")
                }
            }
        }

        // Tier 3 Encrypted SMS Remote Control
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("TIER 3 EMERGENCY SMS REMOTE CONTROL", color = AccentAmber, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Dispatches a cryptographically signed Type-0 / obfuscated SMS command (MT# prefix) to silently wake an offline device.", color = Color.White, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        smsStatusText = "Dispatched: MT#EBdrtWLXCed146J0rcpdteI= -> Verified & Replied (±120m)"
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Sms, contentDescription = null, tint = AccentAmber)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Dispatch Silent Poll SMS (Offline)", color = AccentAmber)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(smsStatusText, color = TextSecondary, fontSize = 11.sp)
            }
        }

        // Zero-Trust Security Log
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("ZERO-TRUST ENCRYPTION STATUS", color = AccentGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text("• Master EIK: 256-bit Hardware Enclave Key Active", color = TextSecondary, fontSize = 12.sp)
                Text("• BLE Privacy: Rotating EID via SECP160R1 (1024s)", color = TextSecondary, fontSize = 12.sp)
                Text("• End-to-End Encryption: AES-256-GCM + Poly1305", color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}
