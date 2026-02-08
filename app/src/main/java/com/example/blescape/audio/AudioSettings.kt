package com.example.blescape.audio

import android.content.Context
import android.content.SharedPreferences

data class AudioSettings(
    val masterVolume: Float = 0.08f,           // 0.0 - 1.0 (8% default)
    val rssiThreshold: Int = -90,              // -100 to -50 dBm
    val maxActiveDevices: Int = 40,            // 1 - 100
    val volumeCurveExponent: Float = 2.0f,     // 1.0 - 4.0 (quadratic default)
    val behindAttenuation: Float = 1.0f,       // 0.0 - 1.0 (100% default, no attenuation)
    val freezeSources: Boolean = false,        // Freeze current sources for testing
    val simulateAzimuth: Boolean = false,      // Use simulated azimuth instead of sensor
    val simulatedAzimuthValue: Float = 0f,     // 0 - 360 degrees
    val smoothingTimeMs: Float = 500f          // Smoothing time in milliseconds (0-2000ms)
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
            behindAttenuation = prefs.getFloat("behind_attenuation", 1.0f),
            freezeSources = prefs.getBoolean("freeze_sources", false),
            simulateAzimuth = prefs.getBoolean("simulate_azimuth", false),
            simulatedAzimuthValue = prefs.getFloat("simulated_azimuth_value", 0f),
            smoothingTimeMs = prefs.getFloat("smoothing_time_ms", 500f)
        )
    }

    fun save(settings: AudioSettings) {
        prefs.edit()
            .putFloat("master_volume", settings.masterVolume)
            .putInt("rssi_threshold", settings.rssiThreshold)
            .putInt("max_active_devices", settings.maxActiveDevices)
            .putFloat("volume_curve_exponent", settings.volumeCurveExponent)
            .putFloat("behind_attenuation", settings.behindAttenuation)
            .putBoolean("freeze_sources", settings.freezeSources)
            .putBoolean("simulate_azimuth", settings.simulateAzimuth)
            .putFloat("simulated_azimuth_value", settings.simulatedAzimuthValue)
            .putFloat("smoothing_time_ms", settings.smoothingTimeMs)
            .apply()
    }
}
