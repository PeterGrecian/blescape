package com.example.blescape.audio

data class AudioDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val type: DeviceType
)

enum class DeviceType {
    BLE,
    WIFI
}

data class DeviceAudioState(
    val deviceId: String,
    val frequency: Float,
    val worldAzimuth: Float,
    var phase: Float = 0f,
    var smoothedVolume: Float = 0f
)

data class StereoPan(
    val left: Float,
    val right: Float
)

data class Orientation(
    val azimuth: Float,
    val pitch: Float,
    val roll: Float
)
