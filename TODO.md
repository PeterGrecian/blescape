## TODO

- really understand the audio pipeline and panning.  It's very low level so some infrastructure will be needed and some synthesis.  Envelopes.  Currently panning is not working if you compare it to other implementations, eg hardware.

- I think there's going to have to be a stand alone synth because this is going to be very influential on the overall effect.


Front/back discrimination: Currently using simple amplitude attenuation for sounds behind (90° < d < 270°).
Better models exist using frequency-dependent HRTF (Head-Related Transfer Functions) that modify frequency
response based on angle. Research HRTF models and implement frequency-dependent filtering for more realistic
spatial audio (e.g., high-frequency rolloff for sounds behind).

in central london getting 300+ ble and 60+ wifi! so synthesising many sources will become important.
Many do identify as lime and forrest bikes.  They should have a separate tone.  They might need to be represented with noise.

step back from this for now.

