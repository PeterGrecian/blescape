package com.example.blescape.audio

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class AudioStateManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "audio_device_positions",
        Context.MODE_PRIVATE
    )

    private val deviceStates = ConcurrentHashMap<String, DeviceAudioState>()
    private val toneGenerator = ToneGenerator()

    fun getOrCreateState(device: AudioDevice, forcedAzimuth: Float? = null): DeviceAudioState {
        val deviceId = "${device.type}:${device.address}"

        return deviceStates.getOrPut(deviceId) {
            val worldAzimuth = forcedAzimuth ?: prefs.getFloat(deviceId, -1f).let { saved ->
                if (saved >= 0f) {
                    saved
                } else {
                    val newAzimuth = Random.nextFloat() * 360f
                    prefs.edit().putFloat(deviceId, newAzimuth).apply()
                    newAzimuth
                }
            }

            val frequency = toneGenerator.deviceFrequency(device.address, device.name)

            DeviceAudioState(
                deviceId = deviceId,
                frequency = frequency,
                worldAzimuth = worldAzimuth
            )
        }.also {
            // Update azimuth if forced (for single device mode)
            if (forcedAzimuth != null && it.worldAzimuth != forcedAzimuth) {
                it.worldAzimuth = forcedAzimuth
            }
        }
    }

    fun updateVolume(state: DeviceAudioState, targetVolume: Float, alpha: Float) {
        state.smoothedVolume += alpha * (targetVolume - state.smoothedVolume)
    }

    fun reset() {
        deviceStates.clear()
    }
}
