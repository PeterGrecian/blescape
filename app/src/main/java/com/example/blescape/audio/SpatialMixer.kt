package com.example.blescape.audio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spatial audio mixer implementing constant-power stereo panning.
 *
 * Key concepts:
 * - Constant-power panning: L² + R² = constant (prevents volume dip in center)
 *   See: https://en.wikipedia.org/wiki/Panning_(audio)
 * - Stereo imaging: Uses square-root panning law for smooth spatial placement
 *   See: https://www.cs.cmu.edu/~music/icm-online/readings/panlaws/
 * - Angular symmetry: Mirrors angles > 90° to reduce calculations (left/right symmetry)
 *
 * References:
 * - "The Theory and Technique of Electronic Music" by Miller Puckette
 *   http://msp.ucsd.edu/techniques/latest/book-html/
 * - "Introduction to Computer Music" - Stereo Panning
 *   https://cmtext.indiana.edu/synthesis/chapter4_panning.php
 */
class SpatialMixer {

    /**
     * Calculate relative angle between listener (phone) and source (device).
     *
     * Returns angle in range -180° to +180°:
     * - 0° = source in front
     * - +90° = source to the right
     * - -90° = source to the left
     * - ±180° = source behind
     *
     * Uses atan2 for proper angle wrapping around 360°/0° boundary.
     * See: https://en.wikipedia.org/wiki/Atan2
     */
    fun calculateRelativeAngle(phoneAzimuth: Float, deviceWorldAzimuth: Float): Float {
        // Convert to radians for proper angle wrapping
        val phoneRad = Math.toRadians(phoneAzimuth.toDouble())
        val deviceRad = Math.toRadians(deviceWorldAzimuth.toDouble())

        // Calculate difference and normalize using atan2 (handles wrapping correctly)
        val deltaRad = deviceRad - phoneRad
        val normalizedRad = atan2(sin(deltaRad), cos(deltaRad))

        // Convert back to degrees (-180 to +180)
        return Math.toDegrees(normalizedRad).toFloat()
    }

    /**
     * Calculate stereo pan (L/R gains) from relative angle using constant-power panning.
     *
     * Algorithm:
     * 1. Apply front/back symmetry: map ±180° → ±90°
     *    - Front hemisphere (|angle| ≤ 90°): use angle directly
     *    - Back hemisphere (|angle| > 90°): mirror around 180°
     *      e.g., 120° becomes 60°, -150° becomes -30°
     *
     * 2. Calculate pan position: p = |d'| / 90° (0.0 = center, 1.0 = hard L/R)
     *
     * 3. Apply square-root panning law (constant-power):
     *    - rightGain = √((p + 1) / 2)
     *    - leftGain = √((1 - p) / 2)
     *    This ensures L² + R² ≈ constant, preventing center volume dip
     *
     * 4. Swap L/R for negative angles (left side)
     *
     * 5. Apply behind attenuation if enabled
     *
     * Test cases:
     * - angle = 0° → L=0.71, R=0.71 (center, -3dB each)
     * - angle = 90° → L=0.00, R=1.00 (hard right)
     * - angle = -90° → L=1.00, R=0.00 (hard left)
     * - angle = 180° → L=0.71, R=0.71 (center behind, with attenuation)
     *
     * References:
     * - Constant-power panning: https://www.cs.cmu.edu/~music/icm-online/readings/panlaws/
     * - Square-root law: https://dsp.stackexchange.com/questions/21691/
     */
    fun calculateStereoPan(relativeAngle: Float, behindAttenuation: Float): StereoPan {
        // Apply symmetry: for angles > 90°, mirror around 180°
        // Maps: 91°→89°, 120°→60°, 150°→30°, -150°→-30°, etc.
        var dPrime = relativeAngle
        while (dPrime > 90f) {
            dPrime = 180f - dPrime
        }
        while (dPrime < -90f) {
            dPrime = -180f - dPrime
        }

        // Calculate pan position (0.0 = center, 1.0 = hard L/R)
        val absDPrime = abs(dPrime)
        val panPosition = absDPrime / 90f

        // Constant-power panning law (square-root)
        // Ensures L² + R² ≈ 1.0 for constant perceived loudness
        var rightGain = sqrt((panPosition + 1f) / 2f)
        var leftGain = sqrt((1f - panPosition) / 2f)

        // Swap L and R if d' < 0 (left side)
        if (dPrime < 0f) {
            val temp = leftGain
            leftGain = rightGain
            rightGain = temp
        }

        // Apply attenuation only for back hemisphere (|relativeAngle| > 90°)
        val attenuation = if (abs(relativeAngle) > 90f) behindAttenuation else 1.0f

        return StereoPan(
            left = leftGain * attenuation,
            right = rightGain * attenuation
        )
    }

    fun rssiToVolume(rssi: Int, exponent: Float): Float {
        val normalized = ((rssi + 100f) / 40f).coerceIn(0f, 1f)
        return normalized.pow(exponent)
    }
}
