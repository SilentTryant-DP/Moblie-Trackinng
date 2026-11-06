package com.mobiletracker.android.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.telephony.TelephonyManager
import android.util.Log
import com.mobiletracker.core.engine.*

/**
 * Android Native Location Arbiter
 *
 * Concurrently audits and queries all 4 location mechanisms:
 * 1. GPS / GNSS (via LocationManager GPS_PROVIDER)
 * 2. BLE 5.1 Proximity / AoA (via BluetoothLeScanner)
 * 3. Passive Cell Tower R-Tree Triangulation (via TelephonyCallback & SQLite)
 * 4. P2P Mesh Peer Anchor (via WifiP2pManager & CRDT peer state)
 *
 * Dispatches observations to LocationFusionArbiter to yield the most accurate, resilient fix.
 */
class AndroidLocationArbiter(
    private val context: Context,
    private val arbiterEngine: LocationFusionArbiter = LocationFusionArbiter()
) {
    companion object {
        private const val TAG = "AndroidLocationArbiter"
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var latestGpsCandidate: LocationFixCandidate? = null
    private var latestBleCandidate: LocationFixCandidate? = null
    private var latestCellCandidate: LocationFixCandidate? = null
    private var latestMeshCandidate: LocationFixCandidate? = null

    /**
     * Updates candidate from cellular triangulation engine.
     */
    fun onCellularLocationResolved(position: TriangulatedPosition) {
        latestCellCandidate = LocationFixCandidate(
            sourceType = LocationSourceType.CELLULAR_RTREE,
            latitude = position.latitude,
            longitude = position.longitude,
            accuracyMeters = position.estimatedAccuracyMeters,
            timestampMs = System.currentTimeMillis(),
            metadata = mapOf("participatingTowers" to position.participatingTowers)
        )
    }

    /**
     * Updates candidate from BLE 5.1 AoA / Beacon engine.
     */
    fun onBleProximityResolved(lat: Double, lon: Double, accuracyMeters: Float, azimuthDeg: Double?) {
        latestBleCandidate = LocationFixCandidate(
            sourceType = LocationSourceType.BLE_AOA,
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracyMeters,
            timestampMs = System.currentTimeMillis(),
            metadata = azimuthDeg?.let { mapOf("azimuth" to it) } ?: emptyMap()
        )
    }

    /**
     * Updates candidate from nearby P2P Mesh peer anchor.
     */
    fun onMeshPeerAnchorResolved(lat: Double, lon: Double, accuracyMeters: Float, peerId: String) {
        latestMeshCandidate = LocationFixCandidate(
            sourceType = LocationSourceType.P2P_MESH_ANCHOR,
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracyMeters,
            timestampMs = System.currentTimeMillis(),
            metadata = mapOf("peerId" to peerId)
        )
    }

    /**
     * Starts listening to GPS provider if enabled.
     */
    @SuppressLint("MissingPermission")
    fun startGpsMonitoring() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    5.0f,
                    object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            latestGpsCandidate = LocationFixCandidate(
                                sourceType = LocationSourceType.GPS_GNSS,
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                accuracyMeters = loc.accuracy,
                                timestampMs = loc.time
                            )
                        }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            latestGpsCandidate = null
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    }
                )
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "GPS permissions not granted: ${e.message}")
        }
    }

    /**
     * Probes all 4 sources and executes the fusion arbiter.
     * Returns the most appropriate location.
     */
    fun resolveOptimalLocation(): FusedLocationResult {
        val isGpsEnabled = try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (e: Exception) { false }

        val isBleEnabled = bluetoothManager?.adapter?.isEnabled == true
        val isCellModemAvailable = telephonyManager.simState == TelephonyManager.SIM_STATE_READY
        val isMeshActive = wifiP2pManager != null

        val result = arbiterEngine.evaluateAndFuse(
            gpsCandidate = latestGpsCandidate,
            isGpsHardwareOn = isGpsEnabled,
            bleCandidate = latestBleCandidate,
            isBleHardwareOn = isBleEnabled,
            cellCandidate = latestCellCandidate,
            isCellModemOn = isCellModemAvailable,
            meshCandidate = latestMeshCandidate,
            isMeshActive = isMeshActive
        )

        Log.i(TAG, "Location resolved: Lat=${result.latitude}, Lon=${result.longitude}, Acc=±${result.fusedAccuracyMeters}m (Primary: ${result.primarySource})")
        return result
    }
}
