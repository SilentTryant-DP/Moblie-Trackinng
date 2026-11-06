package com.mobiletracker.core.engine

import kotlin.math.*

/**
 * The 4 Fundamental Location Modalities supported in the Hybrid Ecosystem
 */
enum class LocationSourceType(val priorityRank: Int, val description: String) {
    BLE_AOA(1, "BLE 5.1 Angle of Arrival (Indoor Micro-Location)"),
    GPS_GNSS(2, "GPS/GNSS Satellite Fix (Outdoor High-Precision)"),
    CELLULAR_RTREE(3, "Cell Tower Triangulation (Offline OpenCelliD R-Tree)"),
    P2P_MESH_ANCHOR(4, "P2P Mesh Peer Anchor (Cooperative Wi-Fi Direct/BLE)")
}

data class LocationFixCandidate(
    val sourceType: LocationSourceType,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,     // 1-sigma standard deviation in meters
    val timestampMs: Long,
    val metadata: Map<String, Any> = emptyMap()
)

data class SourceAvailabilityReport(
    val sourceType: LocationSourceType,
    val isHardwareEnabled: Boolean,
    val hasValidSignal: Boolean,
    val candidate: LocationFixCandidate?,
    val diagnosticReason: String
)

data class FusedLocationResult(
    val latitude: Double,
    val longitude: Double,
    val fusedAccuracyMeters: Float,
    val confidenceScore: Int,      // 0 to 100%
    val primarySource: LocationSourceType,
    val participatingSources: List<LocationSourceType>,
    val availabilityBreakdown: List<SourceAvailabilityReport>,
    val timestampMs: Long
)

/**
 * Optimal Multi-Source Sensor Fusion & Availability Arbiter
 *
 * Responsibilities:
 * 1. Probes which of the 4 tracking options (GPS, BLE 5.1 AoA, Cell Tower R-Tree, P2P Mesh) are available.
 * 2. Cross-validates signals to reject multipath glitches, spoofing, and stale fixes.
 * 3. Fuses all viable sources using Inverse-Variance Covariance Weighting to compute
 *    the most accurate and resilient geographic coordinate fix possible.
 */
class LocationFusionArbiter {

    companion object {
        private const val MAX_STALE_AGE_MS = 60_000L // 1 minute staleness window
        private const val EARTH_RADIUS_METERS = 6371000.0
    }

    /**
     * Evaluates all 4 tracking options and produces the optimal fused location.
     */
    fun evaluateAndFuse(
        gpsCandidate: LocationFixCandidate?,
        isGpsHardwareOn: Boolean,
        bleCandidate: LocationFixCandidate?,
        isBleHardwareOn: Boolean,
        cellCandidate: LocationFixCandidate?,
        isCellModemOn: Boolean,
        meshCandidate: LocationFixCandidate?,
        isMeshActive: Boolean,
        currentTimeMs: Long = System.currentTimeMillis()
    ): FusedLocationResult {

        // 1. Audit Availability of all 4 Options
        val reports = mutableListOf<SourceAvailabilityReport>()

        // Option 1: GPS / GNSS
        reports.add(
            auditCandidate(
                type = LocationSourceType.GPS_GNSS,
                isHardwareOn = isGpsHardwareOn,
                candidate = gpsCandidate,
                currentTimeMs = currentTimeMs,
                maxAllowedAccuracy = 150.0f
            )
        )

        // Option 2: BLE 5.1 AoA / Micro-Location
        reports.add(
            auditCandidate(
                type = LocationSourceType.BLE_AOA,
                isHardwareOn = isBleHardwareOn,
                candidate = bleCandidate,
                currentTimeMs = currentTimeMs,
                maxAllowedAccuracy = 35.0f
            )
        )

        // Option 3: Cellular Triangulation (Offline OpenCelliD R-Tree)
        reports.add(
            auditCandidate(
                type = LocationSourceType.CELLULAR_RTREE,
                isHardwareOn = isCellModemOn,
                candidate = cellCandidate,
                currentTimeMs = currentTimeMs,
                maxAllowedAccuracy = 2500.0f
            )
        )

        // Option 4: P2P Mesh Peer Anchor
        reports.add(
            auditCandidate(
                type = LocationSourceType.P2P_MESH_ANCHOR,
                isHardwareOn = isMeshActive,
                candidate = meshCandidate,
                currentTimeMs = currentTimeMs,
                maxAllowedAccuracy = 300.0f
            )
        )

        // 2. Filter down to valid, qualified candidates
        val viableCandidates = reports
            .filter { it.hasValidSignal && it.candidate != null }
            .map { it.candidate!! }

        if (viableCandidates.isEmpty()) {
            // Absolute blackout fallback
            return FusedLocationResult(
                latitude = 0.0,
                longitude = 0.0,
                fusedAccuracyMeters = 9999.0f,
                confidenceScore = 0,
                primarySource = LocationSourceType.CELLULAR_RTREE,
                participatingSources = emptyList(),
                availabilityBreakdown = reports,
                timestampMs = currentTimeMs
            )
        }

        // 3. Spatial Cross-Validation: Filter out geometric outliers (e.g., GPS multipath glitch)
        val sanitizedCandidates = filterSpatialOutliers(viableCandidates)

        // 4. Special Optimization: If high-precision BLE 5.1 AoA is available,
        // it provides centimeter-to-meter indoor accuracy.
        val bleFix = sanitizedCandidates.firstOrNull { it.sourceType == LocationSourceType.BLE_AOA }
        if (bleFix != null && bleFix.accuracyMeters <= 5.0f) {
            return FusedLocationResult(
                latitude = bleFix.latitude,
                longitude = bleFix.longitude,
                fusedAccuracyMeters = bleFix.accuracyMeters,
                confidenceScore = 98,
                primarySource = LocationSourceType.BLE_AOA,
                participatingSources = listOf(LocationSourceType.BLE_AOA),
                availabilityBreakdown = reports,
                timestampMs = currentTimeMs
            )
        }

        // 5. Multi-Source Inverse-Variance Covariance Fusion:
        // Weight w_i = 1 / (accuracy_i)^2
        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLon = 0.0

        for (cand in sanitizedCandidates) {
            val variance = cand.accuracyMeters * cand.accuracyMeters
            val weight = 1.0 / max(1.0, variance.toDouble())

            weightedLat += cand.latitude * weight
            weightedLon += cand.longitude * weight
            totalWeight += weight
        }

        val finalLat = weightedLat / totalWeight
        val finalLon = weightedLon / totalWeight

        // Fused 1-sigma uncertainty: sigma_fused = sqrt(1 / sum(weights))
        val fusedAccuracy = sqrt(1.0 / totalWeight).toFloat()

        // Determine Primary Contributor (lowest individual uncertainty)
        val primary = sanitizedCandidates.minByOrNull { it.accuracyMeters }?.sourceType
            ?: LocationSourceType.CELLULAR_RTREE

        // Calculate confidence score (0 to 100%)
        val confidence = calculateConfidence(fusedAccuracy, sanitizedCandidates.size)

        return FusedLocationResult(
            latitude = finalLat,
            longitude = finalLon,
            fusedAccuracyMeters = max(1.0f, fusedAccuracy),
            confidenceScore = confidence,
            primarySource = primary,
            participatingSources = sanitizedCandidates.map { it.sourceType },
            availabilityBreakdown = reports,
            timestampMs = currentTimeMs
        )
    }

    private fun auditCandidate(
        type: LocationSourceType,
        isHardwareOn: Boolean,
        candidate: LocationFixCandidate?,
        currentTimeMs: Long,
        maxAllowedAccuracy: Float
    ): SourceAvailabilityReport {
        if (!isHardwareOn) {
            return SourceAvailabilityReport(
                sourceType = type,
                isHardwareEnabled = false,
                hasValidSignal = false,
                candidate = null,
                diagnosticReason = "Hardware radio or provider is currently disabled/unpowered."
            )
        }

        if (candidate == null) {
            return SourceAvailabilityReport(
                sourceType = type,
                isHardwareEnabled = true,
                hasValidSignal = false,
                candidate = null,
                diagnosticReason = "Hardware enabled, but no signal/fix acquired (scanning...)."
            )
        }

        val age = currentTimeMs - candidate.timestampMs
        if (age > MAX_STALE_AGE_MS) {
            return SourceAvailabilityReport(
                sourceType = type,
                isHardwareEnabled = true,
                hasValidSignal = false,
                candidate = candidate,
                diagnosticReason = "Fix rejected: Stale timestamp (${age / 1000}s old > limit 60s)."
            )
        }

        if (candidate.accuracyMeters > maxAllowedAccuracy) {
            return SourceAvailabilityReport(
                sourceType = type,
                isHardwareEnabled = true,
                hasValidSignal = false,
                candidate = candidate,
                diagnosticReason = "Fix rejected: Accuracy too degraded (${candidate.accuracyMeters}m > ${maxAllowedAccuracy}m)."
            )
        }

        return SourceAvailabilityReport(
            sourceType = type,
            isHardwareEnabled = true,
            hasValidSignal = true,
            candidate = candidate,
            diagnosticReason = "Active & Verified (Accuracy: ±${candidate.accuracyMeters}m)."
        )
    }

    /**
     * Cross-checks coordinates against macro bounds to prevent multipath or spoofed fixes.
     */
    private fun filterSpatialOutliers(candidates: List<LocationFixCandidate>): List<LocationFixCandidate> {
        if (candidates.size <= 2) return candidates

        // Use cellular or mesh as coarse anchor to verify GPS isn't reporting thousands of km away
        val cellFix = candidates.firstOrNull { it.sourceType == LocationSourceType.CELLULAR_RTREE }
        if (cellFix != null) {
            return candidates.filter { c ->
                if (c.sourceType == LocationSourceType.CELLULAR_RTREE) true
                else {
                    val dist = calculateDistance(cellFix.latitude, cellFix.longitude, c.latitude, c.longitude)
                    // If GPS diverges from cellular tower by more than 25 km, flag as outlier
                    dist <= 25_000.0
                }
            }
        }
        return candidates
    }

    private fun calculateConfidence(accuracyMeters: Float, sourceCount: Int): Int {
        var base = when {
            accuracyMeters <= 5.0f -> 95
            accuracyMeters <= 15.0f -> 90
            accuracyMeters <= 50.0f -> 80
            accuracyMeters <= 150.0f -> 65
            accuracyMeters <= 500.0f -> 45
            else -> 25
        }
        if (sourceCount >= 2) base += 5
        return min(99, base)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }
}
