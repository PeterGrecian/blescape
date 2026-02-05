package com.example.blescape.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class AudioEngine(context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        private const val SAMPLE_RATE = 44100
        private const val FRAME_COUNT = 1024
    }

    private var audioTrack: AudioTrack? = null
    private var renderThread: Thread? = null
    @Volatile private var isRunning = false

    private val currentDevices = AtomicReference<List<AudioDevice>>(emptyList())
    private val currentOrientation = AtomicReference(Orientation(0f, 0f, 0f))
    private val currentSettings = AtomicReference(AudioSettings())

    private val audioStateManager = AudioStateManager(context)
    private val toneGenerator = ToneGenerator(SAMPLE_RATE)
    private val spatialMixer = SpatialMixer()

    private val leftChannel = FloatArray(FRAME_COUNT)
    private val rightChannel = FloatArray(FRAME_COUNT)
    private val deviceBuffer = FloatArray(FRAME_COUNT)
    private val stereoBuffer = ShortArray(FRAME_COUNT * 2)

    fun start() {
        if (isRunning) return

        Log.d(TAG, "Starting audio engine")

        val minBufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = maxOf(minBufferSize, FRAME_COUNT * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
        isRunning = true

        renderThread = thread(name = "AudioRenderThread", priority = Thread.MAX_PRIORITY) {
            renderLoop()
        }
    }

    fun stop() {
        if (!isRunning) return

        Log.d(TAG, "Stopping audio engine")
        isRunning = false

        renderThread?.join(1000)
        renderThread = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        toneGenerator.reset()
        audioStateManager.reset()
    }

    fun updateDevices(bleDevices: List<AudioDevice>, wifiDevices: List<AudioDevice>) {
        val settings = currentSettings.get()
        val allDevices = (bleDevices + wifiDevices)
            .filter { it.rssi > settings.rssiThreshold }
            .sortedByDescending { it.rssi }

        currentDevices.set(allDevices)
    }

    fun updateOrientation(orientation: Orientation) {
        currentOrientation.set(orientation)
    }

    fun updateSettings(settings: AudioSettings) {
        currentSettings.set(settings)
    }

    private fun renderLoop() {
        var frameCount = 0
        while (isRunning) {
            try {
                leftChannel.fill(0f)
                rightChannel.fill(0f)

                val settings = currentSettings.get()
                val devices = currentDevices.get().take(settings.maxActiveDevices)
                val orientation = currentOrientation.get()

                // Debug logging every 100 frames (~2.3 seconds)
                if (frameCount % 100 == 0 && devices.isNotEmpty()) {
                    Log.d(TAG, "Orientation azimuth: ${orientation.azimuth}, Devices: ${devices.size}")
                }

                for (device in devices) {
                    val state = audioStateManager.getOrCreateState(device)

                    val targetVolume = spatialMixer.rssiToVolume(device.rssi, settings.volumeCurveExponent)
                    audioStateManager.updateVolume(state, targetVolume)

                    deviceBuffer.fill(0f)
                    toneGenerator.generateSamples(
                        state.deviceId,
                        state.frequency,
                        state.smoothedVolume * settings.masterVolume,
                        FRAME_COUNT,
                        deviceBuffer
                    )

                    // Special case: if max devices is 1, place tone at 0 degrees (straight ahead)
                    val relativeAngle = if (settings.maxActiveDevices == 1) {
                        0f
                    } else {
                        spatialMixer.calculateRelativeAngle(
                            orientation.azimuth,
                            state.worldAzimuth
                        )
                    }
                    val pan = spatialMixer.calculateStereoPan(relativeAngle, settings.behindAttenuation)

                    // Debug logging for first device every 100 frames
                    if (frameCount % 100 == 0 && devices.indexOf(device) == 0) {
                        Log.d(TAG, "Device: ${state.frequency.toInt()}Hz, World: ${state.worldAzimuth}°, Relative: ${relativeAngle}°, Pan: L=${pan.left} R=${pan.right}")
                    }

                    for (i in deviceBuffer.indices) {
                        leftChannel[i] += deviceBuffer[i] * pan.left
                        rightChannel[i] += deviceBuffer[i] * pan.right
                    }
                }

                for (i in leftChannel.indices) {
                    val left = (leftChannel[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    val right = (rightChannel[i] * 32767f).toInt().coerceIn(-32768, 32767)
                    stereoBuffer[i * 2] = left.toShort()
                    stereoBuffer[i * 2 + 1] = right.toShort()
                }

                audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)
                frameCount++

            } catch (e: Exception) {
                Log.e(TAG, "Error in render loop", e)
            }
        }
    }
}
