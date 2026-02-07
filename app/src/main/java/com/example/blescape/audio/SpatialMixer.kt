package com.example.blescape.audio

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class SpatialMixer {

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

    fun calculateStereoPan(relativeAngle: Float, behindAttenuation: Float): StereoPan {
        // Apply symmetry: for angles > 90°, mirror around 180°
        var dPrime = relativeAngle
        while (dPrime > 90f) {
            dPrime = 180f - dPrime
        }
        while (dPrime < -90f) {
            dPrime = -180f - dPrime
        }

        // Calculate pan using abs(d'), then swap L/R if d' was negative
        val absDPrime = abs(dPrime)
        val panPosition = absDPrime / 90f

        var rightGain = sqrt((panPosition + 1f) / 2f)
        var leftGain = sqrt((1f - panPosition) / 2f)

        // Swap L and R if d' < 0 (left side)
        if (dPrime < 0f) {
            val temp = leftGain
            leftGain = rightGain
            rightGain = temp
        }

        // Apply attenuation only for back hemisphere (90 < relativeAngle < 270)
        // Note: relativeAngle is in range -180 to +180, so back is |angle| > 90
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
