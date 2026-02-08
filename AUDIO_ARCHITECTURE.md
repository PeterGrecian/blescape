# Audio Architecture Documentation

## Overview

The spatial audio system implements constant-power stereo panning for multiple sound sources positioned in 3D space around the listener.

## Audio Library: Android AudioTrack

### What is AudioTrack?

**AudioTrack** is Android's low-level audio output API that allows direct PCM (Pulse Code Modulation) streaming to the audio hardware. Unlike higher-level APIs (MediaPlayer, SoundPool), AudioTrack gives us **sample-by-sample control** over the audio output.

**Reference:** https://developer.android.com/reference/android/media/AudioTrack

### Why AudioTrack?

We chose AudioTrack because it allows us to:
1. **Generate audio in real-time** - Synthesize waveforms on-the-fly
2. **Control every sample** - Apply panning per-frame
3. **Low latency** - Direct hardware access (23ms buffer in our case)
4. **Mix multiple sources** - Combine many audio streams manually
5. **Dynamic spatialization** - Update panning based on phone orientation (we implement this!)

**Important:** AudioTrack is a **low-level PCM output API only**. It knows nothing about:
- ❌ Spatial positioning
- ❌ Panning calculations
- ❌ 3D audio
- ❌ Head tracking
- ❌ Source positions

**We implement all spatial audio logic ourselves** using:
- ✅ Phone orientation sensor (gyroscope/magnetometer)
- ✅ Our panning algorithm (SpatialMixer.kt)
- ✅ Per-frame gain calculations
- ✅ Device position tracking

This is fundamentally different from higher-level APIs like:
- **Google Resonance Audio SDK** - Provides HRTF, room modeling, etc.
- **Apple Spatial Audio** - Hardware-accelerated head tracking
- **Windows Sonic / Dolby Atmos** - OS-level spatial audio

We're building spatial audio **from scratch** using math and sensors.

### AudioTrack Configuration

```kotlin
AudioTrack.Builder()
    .setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)        // Media playback
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)  // Music content
            .build()
    )
    .setAudioFormat(
        AudioFormat.Builder()
            .setSampleRate(44100)                          // CD-quality sample rate
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)   // 16-bit signed integers
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO) // L/R channels
            .build()
    )
    .setBufferSizeInBytes(bufferSize)  // 1024 samples × 2 channels × 2 bytes = 4096
    .setTransferMode(AudioTrack.MODE_STREAM)  // Streaming mode (not static buffer)
    .build()
```

**Key Parameters:**
- **Sample Rate:** 44,100 Hz (44.1 kHz) - Standard CD quality
- **Bit Depth:** 16-bit signed PCM (-32,768 to +32,767 range)
- **Channels:** Stereo (2 channels: Left & Right)
- **Frame Size:** 1024 samples per channel
- **Frame Duration:** 1024 / 44100 ≈ 23.2ms per frame

### The Audio Pipeline

```
┌─────────────────┐
│ Generate Samples│  ToneGenerator: Create waveforms (triangle wave)
│  (float 0-1)    │  Per-device phase tracking for continuity
└────────┬────────┘
         │
┌────────▼────────┐
│ Apply Volume    │  Multiply by smoothed volume (RSSI-based)
│  & Panning      │  Smoothed L/R gains from spatial calculation
└────────┬────────┘
         │
┌────────▼────────┐
│ Mix to Stereo   │  Accumulate all sources to L/R buffers
│   Buffers       │  leftChannel[i] += sample × smoothedLeftGain
└────────┬────────┘
         │
┌────────▼────────┐
│ Convert to PCM  │  float (-1.0 to +1.0) → int16 (-32768 to +32767)
│   16-bit        │  Clamping to prevent overflow
└────────┬────────┘
         │
┌────────▼────────┐
│ Write to        │  audioTrack.write(stereoBuffer, ...)
│  AudioTrack     │  Blocking call, waits for hardware ready
└─────────────────┘
```

## Sound Generation: Waveform Synthesis

### Overview

Rather than using pre-recorded audio files, we **synthesize all sounds in real-time** using mathematical waveforms. This gives us:
- **Infinite duration** - No looping artifacts
- **Per-device tones** - Each device gets a unique frequency
- **Phase continuity** - No clicks between frames
- **Minimal memory** - No audio file storage needed

### Current Waveform: Triangle Wave

**Why triangle wave?**
- **Rich harmonics** - Contains odd harmonics (3rd, 5th, 7th, ...) for easier debugging
- **Less harsh** than square wave
- **More interesting** than sine wave (pure tone)
- **Easy to generate** - Simple linear ramps

**Reference:** https://en.wikipedia.org/wiki/Triangle_wave

### Triangle Wave Formula

For a normalized phase `p ∈ [0, 1)`:

```
        1 ┤     ╱╲
          │    ╱  ╲
        0 ┤   ╱    ╲
          │  ╱      ╲
       -1 ┤ ╱        ╲╱
          └─────────────
          0    0.5    1

if p < 0.5:  value = 4p - 1      (rising ramp)
if p ≥ 0.5:  value = -4p + 3     (falling ramp)
```

**Harmonic Content:**
- Fundamental frequency (200 Hz in current implementation)
- 3rd harmonic @ 600 Hz (1/9 amplitude)
- 5th harmonic @ 1000 Hz (1/25 amplitude)
- 7th harmonic @ 1400 Hz (1/49 amplitude)
- etc.

**Reference:** https://en.wikipedia.org/wiki/Harmonic_series_(music)

### Phase Accumulator

To ensure **smooth, continuous audio** across frames, we track the phase for each device:

```kotlin
var phase = phaseAccumulators[deviceId] ?: 0f
val phaseIncrement = frequency / sampleRate  // e.g., 200/44100 ≈ 0.00454

for (i in 0 until frameCount) {
    // Generate sample at current phase
    val normalizedPhase = phase - phase.toInt()
    val triangleValue = if (normalizedPhase < 0.5f) {
        4f * normalizedPhase - 1f
    } else {
        -4f * normalizedPhase + 3f
    }

    outputBuffer[i] = triangleValue * volume

    // Advance phase
    phase += phaseIncrement
    if (phase >= 1f) phase -= 1f
}

phaseAccumulators[deviceId] = phase  // Save for next frame
```

**Key Points:**
- Phase is stored per-device (HashMap lookup)
- Phase wraps at 1.0 (modulo operation)
- Phase increment determines frequency: `f / sampleRate`
- Continuous phase prevents clicks at frame boundaries

**Reference:** http://www.nicholson.com/rhn/dsp.html#3

### Frequency Assignment

Each detected device gets a unique frequency based on its MAC address:

```kotlin
fun deviceFrequency(address: String, name: String?): Float {
    // Fixed 200 Hz for testing panning
    return 200f
}
```

**Current implementation:** Fixed 200 Hz for simplified panning tests

**Original implementation (commented out):**
```kotlin
fun deviceFrequency(address: String, name: String?): Float {
    val hash = "$address:${name ?: ""}".hashCode()
    val normalized = (hash and 0x7FFFFFFF) / Integer.MAX_VALUE.toFloat()
    return 200f + (normalized * 1800f)  // Range: 200-2000 Hz
}
```

This assigned each device a consistent but unique frequency (200-2000 Hz) based on its identity.

### Alternative Waveforms

The system can easily support other waveforms:

**Sine Wave** (pure tone):
```kotlin
val sineValue = sin(2.0 * PI * normalizedPhase).toFloat()
```

**Square Wave** (harsh, rich harmonics):
```kotlin
val squareValue = if (normalizedPhase < 0.5f) 1f else -1f
```

**Sawtooth Wave** (bright, buzzy):
```kotlin
val sawtoothValue = 2f * normalizedPhase - 1f
```

### Sample Rate & Nyquist Frequency

**Sample Rate:** 44,100 Hz
- **Nyquist frequency:** 22,050 Hz (maximum representable frequency)
- **Audible range:** 20 Hz - 20,000 Hz (humans)
- **Our range:** 200 Hz - 2,000 Hz (well within limits)

**Reference:** https://en.wikipedia.org/wiki/Nyquist_frequency

### PCM Conversion

Final samples are converted from float to 16-bit signed integers:

```kotlin
for (i in leftChannel.indices) {
    // Float range: -1.0 to +1.0
    val left = (leftChannel[i] * 32767f).toInt()
    val right = (rightChannel[i] * 32767f).toInt()

    // Clamp to prevent overflow
    stereoBuffer[i * 2] = left.coerceIn(-32768, 32767).toShort()
    stereoBuffer[i * 2 + 1] = right.coerceIn(-32768, 32767).toShort()
}
```

**Why 32767?** 16-bit signed integers range from -32,768 to +32,767. Multiplying by 32,767 maps float ±1.0 to the full integer range.

**Interleaving:** Stereo samples are interleaved: `[L, R, L, R, L, R, ...]`

**Reference:** https://en.wikipedia.org/wiki/Pulse-code_modulation

## Dynamic Spatialization (What We Built)

**Dynamic spatialization** means the audio panning updates in real-time as you move your head/phone. Unlike static stereo (fixed L/R), the sound sources stay "locked" to world positions while you rotate.

### How It Works

```
World Space (Fixed)                Listener Space (Rotating)
┌─────────────────┐               ┌─────────────────┐
│                 │               │                 │
│     Source      │               │    You turn     │
│   @ 0° North    │    ──────>    │    right 45°    │
│        *        │               │                 │
│                 │               │   Now source    │
│       You       │               │   appears at    │
│     @ 0°        │               │     -45°        │
│        ^        │               │   (to the left) │
└─────────────────┘               └─────────────────┘
        │                                  │
        │                                  │
        v                                  v
  Source stays                       Pan shifts
  at world 0°                       from Center → Left
```

### The Update Loop

Every audio frame (~23ms), we:

1. **Read Sensors**
   ```kotlin
   // MainActivity.kt
   override fun onSensorChanged(event: SensorEvent) {
       if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
           SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
           SensorManager.getOrientation(rotationMatrix, orientationAngles)

           val azimuth = Math.toDegrees(orientationAngles[0])  // 0-360°
           audioEngine.updateOrientation(Orientation(azimuth, pitch, roll))
       }
   }
   ```
   **Sensor:** Android TYPE_ROTATION_VECTOR (fuses gyro + accelerometer + magnetometer)
   **Update rate:** ~60 Hz (faster than audio frames)

2. **Calculate Relative Angle**
   ```kotlin
   // AudioEngine.kt - runs every frame
   val relativeAngle = spatialMixer.calculateRelativeAngle(
       phoneAzimuth = orientation.azimuth,      // e.g., 45° (you're facing NE)
       deviceWorldAzimuth = state.worldAzimuth  // e.g., 0° (source is N)
   )
   // Result: -45° (source is to your left)
   ```

3. **Calculate Pan Gains**
   ```kotlin
   val pan = spatialMixer.calculateStereoPan(relativeAngle)
   // At -45°: L=0.866, R=0.500 (more left)
   ```

4. **Smooth the Gains**
   ```kotlin
   // Exponential smoothing to prevent clicks
   state.smoothedLeftGain += alpha * (pan.left - state.smoothedLeftGain)
   state.smoothedRightGain += alpha * (pan.right - state.smoothedRightGain)
   ```

5. **Mix Audio**
   ```kotlin
   for (i in deviceBuffer.indices) {
       leftChannel[i] += deviceBuffer[i] * state.smoothedLeftGain
       rightChannel[i] += deviceBuffer[i] * state.smoothedRightGain
   }
   ```

### Example Scenario

**Setup:**
- WiFi router at world position 0° (North)
- You start facing North (phone azimuth = 0°)

**What you hear:**
```
You face:  0° (N)  →  Router ahead     →  Pan: Center  (L=0.71, R=0.71)
You turn: 45° (NE) →  Router to left   →  Pan: Left    (L=0.87, R=0.50)
You turn: 90° (E)  →  Router far left  →  Pan: Hard L  (L=1.00, R=0.00)
You turn: 180° (S) →  Router behind    →  Pan: Center  (L=0.71, R=0.71)
```

The router's **world position never changes**, but the **relative angle** updates based on your rotation, which drives the panning.

### Why "Dynamic"?

**Static Stereo:**
- Audio file: `music.mp3`
- Panning: Fixed at recording time
- Turn your head: Nothing changes

**Our Dynamic Spatialization:**
- Audio source: WiFi router at GPS/compass position
- Panning: Calculated every 23ms from (source position - listener orientation)
- Turn your head: Sound "stays in place" in the world

### Challenges We Solved

1. **Sensor Noise**
   - Raw sensor data is jittery
   - Solution: Exponential smoothing on pan gains (500ms default)

2. **Coordinate Systems**
   - Android azimuth: 0° = North, 90° = East (compass)
   - Audio panning: 0° = front, +90° = right (listener-relative)
   - Solution: `calculateRelativeAngle()` converts between them

3. **Frame Synchronization**
   - Sensors update at ~60 Hz
   - Audio frames at ~43 Hz (1024 samples / 44100 Hz)
   - Solution: Use AtomicReference for thread-safe orientation updates

4. **Click Prevention**
   - Rapid head movements cause instant pan changes
   - Solution: Smooth gains using exponential filter

### What We Don't Do (Yet)

**Not implemented:**
- ❌ Distance attenuation (all sources same volume regardless of distance)
- ❌ HRTF filtering (head-related transfer function for 3D perception)
- ❌ Elevation (pitch/roll only affect debugging, not audio)
- ❌ Doppler effect (frequency shift for moving sources)
- ❌ Room acoustics (reverb, reflections)
- ❌ Occlusion (walls blocking sound)

These would require:
- HRTF: Measured head-related impulse responses + convolution
- Distance: RSSI-to-distance mapping (already have RSSI, could add this easily)
- Elevation: Vertical panning (needs HRTF or elevation-specific panning law)

**References:**
- Android Sensors: https://developer.android.com/guide/topics/sensors/sensors_position
- HRTF: https://www.okayadr.com/hrtf-head-related-transfer-function.html
- Google Resonance Audio: https://resonance-audio.github.io/resonance-audio/

## Code Structure

```
app/src/main/java/com/example/blescape/audio/
├── AudioEngine.kt          - Main audio rendering loop
├── SpatialMixer.kt         - Stereo panning calculations
├── ToneGenerator.kt        - Waveform synthesis
├── AudioStateManager.kt    - Per-device state tracking
├── AudioSettings.kt        - Configuration & persistence
└── AudioDevice.kt          - Data classes
```

## Key Components

### 1. SpatialMixer.kt
**Purpose:** Core panning algorithm

**Key Methods:**
- `calculateRelativeAngle()` - Converts world position to listener-relative angle
- `calculateStereoPan()` - Converts angle to L/R stereo gains

**Algorithm:**
1. Front/back symmetry: Maps ±180° → ±90° (back mirrors to front)
2. Pan position: Normalizes angle to 0.0-1.0 range
3. Square-root law: Ensures constant perceived loudness (L² + R² ≈ 1.0)
4. Left/right swap: Handles negative angles
5. Behind attenuation: Optional volume reduction for rear sources

**References:**
- Constant-power panning: https://www.cs.cmu.edu/~music/icm-online/readings/panlaws/
- Stereo imaging: https://cmtext.indiana.edu/synthesis/chapter4_panning.php
- Square-root panning law: https://dsp.stackexchange.com/questions/21691/

### 2. AudioEngine.kt
**Purpose:** Real-time audio rendering thread

**Rendering Pipeline:**
```
For each frame (1024 samples @ 44.1kHz ≈ 23ms):
  1. Clear L/R buffers
  2. For each active device:
     a. Get/create device state (frequency, position, smoothed values)
     b. Calculate target volume from RSSI
     c. Calculate relative angle (device position vs listener orientation)
     d. Calculate stereo pan (L/R gains)
     e. Smooth gains using exponential filter
     f. Generate waveform samples
     g. Mix to L/R channels using smoothed gains
  3. Convert float to 16-bit PCM
  4. Write to AudioTrack
```

**Smoothing:**
- Exponential smoothing: `smoothed += α * (target - smoothed)`
- Alpha coefficient: `α = 1 - exp(-frameTime / tau)`
- Prevents clicks/pops when pan or volume changes

**References:**
- Android AudioTrack: https://developer.android.com/reference/android/media/AudioTrack
- Digital audio basics: http://msp.ucsd.edu/techniques/latest/book-html/

### 3. ToneGenerator.kt
**Purpose:** Synthesize test waveforms

**Current Implementation:**
- Triangle wave generation (rich harmonics for testing)
- Phase-continuous (no clicks between frames)
- Per-device phase tracking

**Formula:**
```
For normalized phase p ∈ [0, 1):
  if p < 0.5: value = 4p - 1
  if p ≥ 0.5: value = -4p + 3
```

**References:**
- Waveform synthesis: https://en.wikipedia.org/wiki/Waveform
- Phase accumulator: http://www.nicholson.com/rhn/dsp.html#3

### 4. AudioSettings.kt
**Purpose:** Configuration management

**Key Settings:**
- `masterVolume` - Overall output level (0.0-1.0)
- `rssiThreshold` - Minimum signal strength to play (-100 to -50 dBm)
- `maxActiveDevices` - Limit simultaneous sources (1-100)
- `volumeCurveExponent` - RSSI-to-volume mapping (1.0-4.0)
- `behindAttenuation` - Volume reduction for rear sources (0.0-1.0)
- `smoothingTimeMs` - Gain interpolation time (0-2000ms)
- `simulateAzimuth` - Use slider instead of sensor (debug mode)
- `freezeSources` - Lock device positions (testing)

**Persistence:** SharedPreferences (survives app restart)

## Testing

### Command-Line Tests
```bash
# Run automated tests and print panning tables
python3 test_panning.py

# Should output:
# - 19 passing assertions
# - Full range panning table (-180° to +180°)
# - Front hemisphere table (-90° to +90°)
```

### Test Coverage
- Center panning (0°): L=0.707, R=0.707
- Hard right (90°): L=0.0, R=1.0
- Hard left (-90°): L=1.0, R=0.0
- Behind symmetry (180° ≈ 0°)
- Front/back mirror (120° ≈ 60°)
- Constant power (L² + R² ≈ 1.0 for all angles)
- Relative angle calculation

## Key Concepts

### Constant-Power Panning
Traditional linear panning (L=1-p, R=p) causes a **volume dip** in the center.
Constant-power panning maintains perceived loudness:
- **Linear pan**: At p=0.5, L=0.5, R=0.5 → power = 0.25 + 0.25 = 0.5 ❌ (-3dB dip!)
- **Constant-power**: At p=0.5, L=0.707, R=0.707 → power = 0.5 + 0.5 = 1.0 ✓

Formula: L² + R² = constant

### Square-Root Panning Law
For pan position p ∈ [0, 1]:
- L = √((1 - p) / 2)
- R = √((p + 1) / 2)

At p=0 (center): L=R=√(1/2)=0.707 (-3dB each, but sum to 0dB)

### Exponential Smoothing
Prevents clicks when gains change:
- `smoothed_value(t) = smoothed_value(t-1) + α * (target - smoothed_value(t-1))`
- Time constant τ: Time to reach ~63% of target
- Smoothing alpha: `α = 1 - e^(-Δt / τ)`

## Performance

**Per-frame cost** (1024 samples @ 44.1kHz):
- Frame time: 23.2ms
- Per device: ~0.1ms (angle calc + pan + mix)
- Max devices @ 60fps: ~100 (limited by CPU, not algorithm)

**Optimization opportunities:**
- Lookup tables for sqrt/trig (not needed yet, CPU handles it)
- SIMD/NEON for mixing (Android doesn't expose easily)
- Reduce frame size for lower latency (1024 is good balance)

## Debugging

### Audio Debug Panel
Shows real-time panning table with:
- Current phone azimuth (or simulated)
- Relative angle to source
- Current L/R pan values
- Reference table for all test angles
- `>` marker shows current position

### Simulation Mode
Enable "Simulate Azimuth" to:
- Override phone orientation sensor
- Use slider as direct pan control (-90° to +90°)
- Test panning without physical rotation
- Verify smoothing behavior

## Future Enhancements

### Possible Improvements:
1. **HRTF filtering** - Head-related transfer functions for better localization
   - Reference: https://www.okayadr.com/hrtf-head-related-transfer-function.html
2. **Distance attenuation** - Inverse-square law based on RSSI
3. **Doppler shift** - Frequency shift for moving sources
4. **Reverb/room modeling** - Spatial ambience
5. **Elevation** - Use pitch/roll for vertical positioning

### Code Quality:
- ✅ Well factored (single responsibility per class)
- ✅ Documented (comments + this guide)
- ✅ Testable (pure functions, command-line tests)
- ✅ Observable (debug output, settings)

## References

### Core Papers & Tutorials:
1. **Miller Puckette - Theory and Technique of Electronic Music**
   http://msp.ucsd.edu/techniques/latest/book-html/
   - Chapter 1: Audio synthesis fundamentals
   - Chapter 7: Spatialization

2. **CMU Interactive Computer Music**
   https://www.cs.cmu.edu/~music/icm-online/
   - Panning laws: readings/panlaws/

3. **Indiana University - Computer Music Synthesis**
   https://cmtext.indiana.edu/synthesis/
   - Chapter 4: Panning and spatial audio

### Android Audio:
4. **Android AudioTrack**
   https://developer.android.com/reference/android/media/AudioTrack
5. **Android Audio Latency**
   https://source.android.com/devices/audio/latency

### DSP Fundamentals:
6. **Julius O. Smith III - Digital Audio Signal Processing**
   https://ccrma.stanford.edu/~jos/
7. **Seeing Circles, Sines and Signals**
   https://jackschaedler.github.io/circles-sines-signals/
