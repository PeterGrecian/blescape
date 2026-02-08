package com.example.blescape.audio

import kotlin.math.sin
import kotlin.math.PI

class ToneGenerator(private val sampleRate: Int = 44100) {

    companion object {
        private const val LOOKUP_TABLE_SIZE = 8192
        private val SINE_TABLE = FloatArray(LOOKUP_TABLE_SIZE) { i ->
            sin(2.0 * PI * i / LOOKUP_TABLE_SIZE).toFloat()
        }
    }

    private val phaseAccumulators = HashMap<String, Float>()

    fun deviceFrequency(address: String, name: String?): Float {
        // Fixed 200 Hz for testing panning
        return 200f
    }

    fun generateSamples(
        deviceId: String,
        frequency: Float,
        volume: Float,
        frameCount: Int,
        outputBuffer: FloatArray
    ) {
        var phase = phaseAccumulators[deviceId] ?: 0f
        val phaseIncrement = frequency / sampleRate

        for (i in 0 until frameCount) {
            // Generate triangle wave: -1 to +1
            val normalizedPhase = phase - phase.toInt()
            val triangleValue = if (normalizedPhase < 0.5f) {
                4f * normalizedPhase - 1f
            } else {
                -4f * normalizedPhase + 3f
            }

            outputBuffer[i] = triangleValue * volume
            phase += phaseIncrement

            if (phase >= 1f) {
                phase -= 1f
            }
        }

        phaseAccumulators[deviceId] = phase
    }

    fun reset() {
        phaseAccumulators.clear()
    }
}
