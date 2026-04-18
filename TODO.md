## TODO

### Active: synthetic aperture BLE telescope

WiFi telescope tests done — revealing results. Next: synthetic aperture BLE telescope tests.

### Audio pipeline (paused)

- Panning not working properly compared to hardware implementations
- Need to really understand the low-level audio pipeline — envelopes, synthesis
- Probably needs a standalone synth — very influential on the overall effect
- Front/back discrimination: currently simple amplitude attenuation. Better: frequency-dependent HRTF (Head-Related Transfer Functions) for high-frequency rolloff behind listener
- In central London: 300+ BLE, 60+ WiFi — synthesising many sources will be important
- Lime/Forrest bikes identify themselves — should have separate tone, possibly noise-based
