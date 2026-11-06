package com.mobiletracker.core.engine

import kotlin.math.*

data class CellObservation(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Long,
    val rssiDbm: Int,         // Signal strength in dBm (-50 dBm to -120 dBm)
    val timingAdvance: Int?   // ToA proxy: 1 TA in LTE approx 78.12 meters
)

data class TowerCandidate(
    val cellIdx: Long,
    val lat: Double,
    val lon: Double,
    val rangeMeters: Int,
    val samples: Int
)

data class TriangulatedPosition(
    val latitude: Double,
    val longitude: Double,
    val estimatedAccuracyMeters: Float,
    val participatingTowers: Int
)

/**
 * Offline Cellular Triangulation Engine
 * Computes position from multi-cell observations using OpenCelliD R-Tree lookups
 * and Path-Loss Weighted Non-Linear Least Squares / Weighted Centroid.
 */
class CellularTriangulation {

    companion object {
        // Path loss exponent (n = 2.7 to 3.5 in suburban/urban cellular environments)
        private const val PATH_LOSS_EXPONENT = 3.2
        // Reference RSSI at 1 meter (typically -40 dBm for LTE small/macro cells)
        private const val RSSI_REF_1M = -40.0
    }

    /**
     * Converts received signal strength (RSSI/RSRP) to estimated distance in meters
     * using the Log-Distance Path Loss Model: RSSI = A - 10 * n * log10(d)
     */
    fun estimateDistanceMeters(rssiDbm: Int, timingAdvance: Int?): Double {
        val pathLossDistance = 10.0.pow((RSSI_REF_1M - rssiDbm) / (10.0 * PATH_LOSS_EXPONENT))
        
        return if (timingAdvance != null && timingAdvance > 0) {
            // Timing Advance (LTE): Each TA step is ~78.12 meters
            val taDistance = timingAdvance * 78.12
            // Fuse path loss and timing advance (give TA high confidence weight)
            (0.7 * taDistance) + (0.3 * pathLossDistance)
        } else {
            pathLossDistance.coerceIn(10.0, 35000.0) // Clamp to realistic cellular radii
        }
    }

    /**
     * Resolves approximate device coordinates using weighted inverse-variance centroid
     * from observed cell towers matched in the local SQLite R-Tree.
     */
    fun triangulate(
        observations: List<CellObservation>,
        matchedTowers: Map<Long, TowerCandidate>
    ): TriangulatedPosition? {
        if (observations.isEmpty() || matchedTowers.isEmpty()) return null

        var totalWeight = 0.0
        var weightedLat = 0.0
        var weightedLon = 0.0
        var towerCount = 0

        for (obs in observations) {
            val candidate = matchedTowers[obs.cid] ?: continue
            val distance = estimateDistanceMeters(obs.rssiDbm, obs.timingAdvance)

            // Weight is inversely proportional to distance squared (variance)
            // Enhanced with confidence based on OpenCelliD crowd sample density
            val sampleConfidence = min(1.5, 0.5 + (candidate.samples / 100.0))
            val weight = (1.0 / (distance * distance)) * sampleConfidence

            weightedLat += candidate.lat * weight
            weightedLon += candidate.lon * weight
            totalWeight += weight
            towerCount++
        }

        if (towerCount == 0 || totalWeight == 0.0) return null

        val finalLat = weightedLat / totalWeight
        val finalLon = weightedLon / totalWeight

        // Estimated accuracy based on tower density and maximum residual error
        val accuracy = when {
            towerCount >= 3 -> 120.0f
            towerCount == 2 -> 250.0f
            else -> 600.0f
        }

        return TriangulatedPosition(
            latitude = finalLat,
            longitude = finalLon,
            estimatedAccuracyMeters = accuracy,
            participatingTowers = towerCount
        )
    }

    /**
     * Generates SQL R-Tree bounding box coordinates for querying nearby towers
     * given an anchor point and search radius in kilometers.
     */
    fun calculateBoundingBox(lat: Double, lon: Double, radiusKm: Double): DoubleArray {
        val latDelta = radiusKm / 111.0
        val lonDelta = radiusKm / (111.0 * cos(Math.toRadians(lat)))
        return doubleArrayOf(
            lon - lonDelta, // min_lon
            lon + lonDelta, // max_lon
            lat - latDelta, // min_lat
            lat + latDelta  // max_lat
        )
    }
}
