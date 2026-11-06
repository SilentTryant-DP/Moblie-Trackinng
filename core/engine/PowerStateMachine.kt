package com.mobiletracker.core.engine

import kotlin.math.sqrt

enum class PowerState {
    STATIONARY_SAFE,       // Zero cellular RF, BLE advertising only, deep modem PSM
    CELL_MAINTENANCE,      // eDRX mode, passive cell tower listener, GPS radio powered down
    ACTIVE_TRANSIT,        // Fused GPS + Cell triangulation, high-frequency updates
    BLE_DIRECTION_FINDING  // High-precision BLE 5.1 AoA continuous tone extension active
}

data class SafeZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double
)

interface ModemPowerController {
    fun requestModemPsm(enabled: Boolean) // Power Saving Mode (PSM)
    fun requestEdrxCycle(periodSeconds: Int) // Extended Discontinuous Reception (eDRX)
    fun setGpsHardwareEnabled(enabled: Boolean)
    fun setBleAdvertisingEnabled(enabled: Boolean)
}

/**
 * Intelligent Multi-Tier Power State Machine
 * Dynamically switches hardware radios based on inertial measurement (accelerometer)
 * and geofenced safe-zone proximity to achieve near-zero battery drain.
 */
class PowerStateMachine(
    private val modemController: ModemPowerController,
    private val safeZones: MutableList<SafeZone> = mutableListOf()
) {
    var currentState: PowerState = PowerState.CELL_MAINTENANCE
        private set

    private var stationaryStartTimeMs: Long = 0L
    private val stationaryThresholdMs: Long = 300_000L // 5 minutes of stillness required
    private val accelStillVarianceThreshold = 0.15f // Gravity normalized m/s^2 variance

    fun addSafeZone(zone: SafeZone) {
        safeZones.add(zone)
    }

    /**
     * Processes raw 3-axis accelerometer samples to detect movement or stillness.
     */
    fun onAccelerometerSample(
        ax: Float, ay: Float, az: Float,
        currentLat: Double?, currentLon: Double?,
        timestampMs: Long = System.currentTimeMillis()
    ): PowerState {
        // Compute total acceleration magnitude: ||a|| = sqrt(ax^2 + ay^2 + az^2)
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        val deviationFromGravity = kotlin.math.abs(magnitude - 9.80665f)

        val isStationary = deviationFromGravity < accelStillVarianceThreshold
        val inSafeZone = isWithinAnySafeZone(currentLat, currentLon)

        if (isStationary) {
            if (stationaryStartTimeMs == 0L) {
                stationaryStartTimeMs = timestampMs
            }
            val stationaryDuration = timestampMs - stationaryStartTimeMs

            if (stationaryDuration >= stationaryThresholdMs && inSafeZone) {
                transitionTo(PowerState.STATIONARY_SAFE)
            } else if (stationaryDuration >= stationaryThresholdMs) {
                transitionTo(PowerState.CELL_MAINTENANCE)
            }
        } else {
            // Significant motion detected: reset stillness timer
            stationaryStartTimeMs = 0L
            transitionTo(PowerState.ACTIVE_TRANSIT)
        }

        return currentState
    }

    /**
     * Invoked when user activates micro-location indoor search UI.
     */
    fun enterBleDirectionFindingMode() {
        transitionTo(PowerState.BLE_DIRECTION_FINDING)
    }

    fun exitBleDirectionFindingMode() {
        stationaryStartTimeMs = 0L
        transitionTo(PowerState.CELL_MAINTENANCE)
    }

    private fun transitionTo(newState: PowerState) {
        if (currentState == newState) return
        val previous = currentState
        currentState = newState

        when (newState) {
            PowerState.STATIONARY_SAFE -> {
                // Maximum energy conservation: deactivate GPS and put modem into deep PSM
                modemController.setGpsHardwareEnabled(false)
                modemController.requestModemPsm(true)
                modemController.setBleAdvertisingEnabled(true)
            }
            PowerState.CELL_MAINTENANCE -> {
                // Modem operates in 3GPP eDRX cycle (e.g., wake every 40.96s for paging)
                modemController.setGpsHardwareEnabled(false)
                modemController.requestModemPsm(false)
                modemController.requestEdrxCycle(41)
                modemController.setBleAdvertisingEnabled(true)
            }
            PowerState.ACTIVE_TRANSIT -> {
                // High-velocity displacement: activate GPS receiver and normal cellular data
                modemController.setGpsHardwareEnabled(true)
                modemController.requestModemPsm(false)
                modemController.requestEdrxCycle(0) // Default continuous reception
                modemController.setBleAdvertisingEnabled(true)
            }
            PowerState.BLE_DIRECTION_FINDING -> {
                // Ultra-low latency BLE 5.1 AoA radio scanning with Constant Tone Extension
                modemController.setGpsHardwareEnabled(false)
                modemController.setBleAdvertisingEnabled(true)
            }
        }
    }

    private fun isWithinAnySafeZone(lat: Double?, lon: Double?): Boolean {
        if (lat == null || lon == null) return false
        return safeZones.any { zone ->
            calculateHaversineDistance(lat, lon, zone.latitude, zone.longitude) <= zone.radiusMeters
        }
    }

    private fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).pow(2) +
                kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
                kotlin.math.sin(dLon / 2).pow(2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }
}

private fun Double.pow(exp: Int): Double = Math.pow(this, exp.toDouble())
