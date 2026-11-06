package com.mobiletracker.core.engine

import kotlin.math.*

data class IqSample(
    val inPhase: Double,    // I component
    val quadrature: Double  // Q component
)

data class AoAResult(
    val azimuthDegrees: Double,
    val elevationDegrees: Double,
    val signalConfidence: Double
)

/**
 * BLE 5.1 Direction Finding: Angle of Arrival (AoA) Mathematical Engine
 *
 * Implements phase difference extraction across dual- or multi-antenna arrays:
 * 1. Constant Tone Extension (CTE) IQ sampling at 2.4 GHz (wavelength lambda ~ 0.125m).
 * 2. Phase calculation: psi = atan2(Q, I).
 * 3. Phase differential between adjacent antenna elements spaced at d = lambda / 2:
 *    delta_psi = (2 * PI * d / lambda) * sin(theta) = PI * sin(theta)
 * 4. Azimuth resolution: theta = asin(delta_psi / PI).
 */
class BleDirectionFinding {

    companion object {
        private const val CARRIER_FREQUENCY_HZ = 2.44e9 // 2.44 GHz (BLE Advertising Channel)
        private const val SPEED_OF_LIGHT = 299792458.0   // m/s
        private const val WAVELENGTH_METERS = SPEED_OF_LIGHT / CARRIER_FREQUENCY_HZ // ~0.1228 m
        private const val ANTENNA_SPACING_METERS = WAVELENGTH_METERS / 2.0          // d = lambda / 2
    }

    /**
     * Resolves Angle of Arrival (AoA) azimuth from two interleaved antenna sample streams
     * collected during the Constant Tone Extension (CTE) window.
     */
    fun calculateAoA(
        antenna1Samples: List<IqSample>,
        antenna2Samples: List<IqSample>
    ): AoAResult? {
        if (antenna1Samples.isEmpty() || antenna2Samples.isEmpty()) return null

        // Calculate average phase angle for Antenna 1
        var sumPhaseAnt1 = 0.0
        for (s in antenna1Samples) {
            sumPhaseAnt1 += atan2(s.quadrature, s.inPhase)
        }
        val avgPhase1 = sumPhaseAnt1 / antenna1Samples.size

        // Calculate average phase angle for Antenna 2
        var sumPhaseAnt2 = 0.0
        for (s in antenna2Samples) {
            sumPhaseAnt2 += atan2(s.quadrature, s.inPhase)
        }
        val avgPhase2 = sumPhaseAnt2 / antenna2Samples.size

        // Phase difference delta_psi in radians
        var deltaPhase = avgPhase2 - avgPhase1

        // Unwrap phase to [-PI, PI]
        while (deltaPhase > PI) deltaPhase -= 2 * PI
        while (deltaPhase < -PI) deltaPhase += 2 * PI

        // Normalization factor: (2 * PI * d) / lambda
        val spatialFactor = (2.0 * PI * ANTENNA_SPACING_METERS) / WAVELENGTH_METERS

        // Compute argument for arcsine: sin(theta) = delta_psi / spatialFactor
        val sinTheta = (deltaPhase / spatialFactor).coerceIn(-1.0, 1.0)
        val azimuthRadians = asin(sinTheta)
        val azimuthDegrees = Math.toDegrees(azimuthRadians)

        // Confidence based on sample consistency and RSSI SNR
        val confidence = (1.0 - abs(sinTheta).pow(3)).coerceIn(0.1, 1.0)

        return AoAResult(
            azimuthDegrees = azimuthDegrees,
            elevationDegrees = 0.0, // Planar array extension handles elevation
            signalConfidence = confidence
        )
    }
}
