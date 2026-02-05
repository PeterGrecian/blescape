package com.example.blescape.audio

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class SpatialMixer {

    fun calculateRelativeAngle(phoneAzimuth: Float, deviceWorldAzimuth: Float): Float {
        var angle = deviceWorldAzimuth - phoneAzimuth
        while (angle > 180f) angle -= 360f
        while (angle < -180f) angle += 360f
        return angle
    }

    fun calculateStereoPan(relativeAngle: Float, behindAttenuation: Float): StereoPan {
        val clamped = relativeAngle.coerceIn(-90f, 90f)
        val panPosition = clamped / 90f

        val rightGain = sqrt((panPosition + 1f) / 2f)
        val leftGain = sqrt((1f - panPosition) / 2f)

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
