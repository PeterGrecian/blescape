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

    var onDebugInfo: ((String) -> Unit)? = null

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

        // If sources are frozen, don't update the device list
        if (settings.freezeSources && currentDevices.get().isNotEmpty()) {
            return
        }

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
                val rawOrientation = currentOrientation.get()

                // Use simulated azimuth if enabled
                val orientation = if (settings.simulateAzimuth) {
                    rawOrientation.copy(azimuth = settings.simulatedAzimuthValue)
                } else {
                    rawOrientation
                }

                // Calculate smoothing coefficient based on smoothing time
                val frameTimeMs = (FRAME_COUNT.toFloat() / SAMPLE_RATE) * 1000f
                val smoothingAlpha = if (settings.smoothingTimeMs > 0) {
                    1f - kotlin.math.exp(-frameTimeMs / settings.smoothingTimeMs)
                } else {
                    1f // No smoothing
                }.coerceIn(0f, 1f)

                // Debug logging and UI update every 50 frames (~1.1 seconds)
                if (frameCount % 50 == 0 && devices.isNotEmpty()) {
                    val azSource = if (settings.simulateAzimuth) "SIM" else "REAL"
                    Log.d(TAG, "=== Frame $frameCount === Az: ${orientation.azimuth.toInt()}° ($azSource) | Devices: ${devices.size} | Frozen: ${settings.freezeSources}")

                    val debugText = buildString {
                        appendLine("Phone Azimuth: ${orientation.azimuth.toInt()}°")
                        appendLine("Devices: ${devices.size} | Frozen: ${settings.freezeSources}")
                        appendLine()
                    }
                    onDebugInfo?.invoke(debugText)
                }

                val debugLines = mutableListOf<String>()

                for ((index, device) in devices.withIndex()) {
                    // For single device mode, place it at world azimuth 0° (north)
                    val forcedAzimuth = if (settings.maxActiveDevices == 1) 0f else null
                    val state = audioStateManager.getOrCreateState(device, forcedAzimuth)

                    val targetVolume = spatialMixer.rssiToVolume(device.rssi, settings.volumeCurveExponent)
                    audioStateManager.updateVolume(state, targetVolume, smoothingAlpha)

                    // When simulating azimuth, use it directly as relative angle (pan control)
                    val relativeAngle = if (settings.simulateAzimuth) {
                        settings.simulatedAzimuthValue
                    } else {
                        spatialMixer.calculateRelativeAngle(orientation.azimuth, state.worldAzimuth)
                    }
                    // No behind attenuation for testing (-90 to +90 only)
                    val pan = spatialMixer.calculateStereoPan(relativeAngle, 1.0f)

                    // Smooth the pan gains
                    state.smoothedLeftGain += smoothingAlpha * (pan.left - state.smoothedLeftGain)
                    state.smoothedRightGain += smoothingAlpha * (pan.right - state.smoothedRightGain)

                    deviceBuffer.fill(0f)
                    toneGenerator.generateSamples(
                        state.deviceId,
                        state.frequency,
                        state.smoothedVolume * settings.masterVolume,
                        FRAME_COUNT,
                        deviceBuffer
                    )

                    // Debug logging for first device every 50 frames
                    if (frameCount % 50 == 0 && index == 0) {
                        Log.d(TAG, "  Device 0: ${state.frequency.toInt()}Hz @ WorldAz=${state.worldAzimuth.toInt()}° | RelAngle=${relativeAngle.toInt()}° | Pan L=${String.format("%.2f", pan.left)} R=${String.format("%.2f", pan.right)} | Vol=${String.format("%.2f", state.smoothedVolume)}")
                    }

                    // Collect debug info for UI
                    if (frameCount % 50 == 0 && index < 5) {
                        debugLines.add("${index+1}. ${state.frequency.toInt()}Hz @ ${state.worldAzimuth.toInt()}° | Rel:${relativeAngle.toInt()}° | L:${String.format("%.2f", pan.left)} R:${String.format("%.2f", pan.right)} | Vol:${String.format("%.2f", state.smoothedVolume)}")
                    }

                    // Mix using smoothed pan gains
                    for (i in deviceBuffer.indices) {
                        leftChannel[i] += deviceBuffer[i] * state.smoothedLeftGain
                        rightChannel[i] += deviceBuffer[i] * state.smoothedRightGain
                    }
                }

                // Send debug info to UI - show panning math table
                if (frameCount % 50 == 0) {
                    val azSource = if (settings.simulateAzimuth) "SIMULATED" else "sensor"

                    // Get current panning info
                    val currentAngle = if (devices.isNotEmpty()) {
                        val relAngle = if (settings.simulateAzimuth) {
                            settings.simulatedAzimuthValue
                        } else {
                            val state = audioStateManager.getOrCreateState(devices[0], if (settings.maxActiveDevices == 1) 0f else null)
                            spatialMixer.calculateRelativeAngle(orientation.azimuth, state.worldAzimuth)
                        }
                        val pan = spatialMixer.calculateStereoPan(relAngle, 1.0f)
                        Triple(relAngle.toInt(), pan.left, pan.right)
                    } else null

                    val debugText = buildString {
                        if (settings.simulateAzimuth) {
                            appendLine("AZ (Pan Control): ${orientation.azimuth.toInt()}°")
                        } else {
                            appendLine("Phone Az: ${orientation.azimuth.toInt()}° (sensor)")
                        }
                        if (currentAngle != null) {
                            appendLine("Relative Angle: ${currentAngle.first}°")
                            appendLine("CURRENT PAN: L=${String.format("%.2f", currentAngle.second)} R=${String.format("%.2f", currentAngle.third)}")
                        }
                        appendLine("Devices: ${devices.size}")
                        appendLine()
                        appendLine("Panning Math (200Hz tone):")
                        appendLine("AZ    d'  Pan   Left  Right")
                        appendLine("---   --- ----  ----  -----")

                        // Build list of AZ (pan angles) from -90 to +90
                        val baseAngles = listOf(-90, -75, -60, -45, -30, -15, 0, 15, 30, 45, 60, 75, 90)
                        val currentAz = orientation.azimuth.toInt().coerceIn(-90, 90)

                        // Combine and sort angles, including current
                        val testAngles = (baseAngles + currentAz).toSet().sorted()

                        for (azAngle in testAngles) {
                            // AZ directly controls relative angle when simulating
                            val angle = azAngle
                            // Calculate d' (symmetry-adjusted angle)
                            var dPrime = angle.toFloat()
                            while (dPrime > 90f) dPrime = 180f - dPrime
                            while (dPrime < -90f) dPrime = -180f - dPrime

                            val absDPrime = kotlin.math.abs(dPrime)
                            val panPosition = absDPrime / 90f

                            var rightGain = kotlin.math.sqrt((panPosition + 1f) / 2f)
                            var leftGain = kotlin.math.sqrt((1f - panPosition) / 2f)

                            if (dPrime < 0f) {
                                val temp = leftGain
                                leftGain = rightGain
                                rightGain = temp
                            }

                            // No behind attenuation for testing
                            val finalLeft = leftGain
                            val finalRight = rightGain

                            // Mark current AZ
                            val marker = if (azAngle == currentAz) ">" else " "

                            val azStr = String.format("%4d°", azAngle)
                            val dPrimeStr = String.format("%3d", dPrime.toInt())
                            val panStr = String.format("%.2f", panPosition)
                            val leftStr = String.format("%.2f", finalLeft)
                            val rightStr = String.format("%.2f", finalRight)

                            appendLine("$marker$azStr $dPrimeStr° $panStr  $leftStr  $rightStr")
                        }
                    }
                    onDebugInfo?.invoke(debugText)
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
