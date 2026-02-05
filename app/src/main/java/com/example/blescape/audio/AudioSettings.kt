package com.example.blescape.audio

import android.content.Context
import android.content.SharedPreferences

data class AudioSettings(
    val masterVolume: Float = 0.08f,           // 0.0 - 1.0 (8% default)
    val rssiThreshold: Int = -90,              // -100 to -50 dBm
    val maxActiveDevices: Int = 40,            // 1 - 100
    val volumeCurveExponent: Float = 2.0f,     // 1.0 - 4.0 (quadratic default)
    val behindAttenuation: Float = 0.3f        // 0.0 - 1.0 (30% default)
)

class AudioSettingsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "audio_settings",
        Context.MODE_PRIVATE
    )

    fun load(): AudioSettings {
        return AudioSettings(
            masterVolume = prefs.getFloat("master_volume", 0.08f),
            rssiThreshold = prefs.getInt("rssi_threshold", -90),
            maxActiveDevices = prefs.getInt("max_active_devices", 40),
            volumeCurveExponent = prefs.getFloat("volume_curve_exponent", 2.0f),
            behindAttenuation = prefs.getFloat("behind_attenuation", 0.3f)
        )
    }

    fun save(settings: AudioSettings) {
        prefs.edit()
            .putFloat("master_volume", settings.masterVolume)
            .putInt("rssi_threshold", settings.rssiThreshold)
            .putInt("max_active_devices", settings.maxActiveDevices)
            .putFloat("volume_curve_exponent", settings.volumeCurveExponent)
            .putFloat("behind_attenuation", settings.behindAttenuation)
            .apply()
    }
}
