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
        val hash = "$address:${name ?: ""}".hashCode()
        val normalized = (hash and 0x7FFFFFFF) / Integer.MAX_VALUE.toFloat()
        return 200f + (normalized * 1800f)
    }

    fun generateSamples(
        deviceId: String,
        frequency: Float,
        volume: Float,
        frameCount: Int,
        outputBuffer: FloatArray
    ) {
        var phase = phaseAccumulators[deviceId] ?: 0f
        val phaseIncrement = frequency * LOOKUP_TABLE_SIZE / sampleRate

        for (i in 0 until frameCount) {
            val index = phase.toInt() % LOOKUP_TABLE_SIZE
            outputBuffer[i] = SINE_TABLE[index] * volume
            phase += phaseIncrement

            if (phase >= LOOKUP_TABLE_SIZE) {
                phase -= LOOKUP_TABLE_SIZE
            }
        }

        phaseAccumulators[deviceId] = phase
    }

    fun reset() {
        phaseAccumulators.clear()
    }
}
