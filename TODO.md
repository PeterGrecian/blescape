## TODO

Debug Mode for Spatial Audio Testing:
- Need a debug version of the app with manual controls:
  - Slider to manually set AZ (azimuth) instead of using phone orientation
  - Fixed tone at 300Hz for consistent testing
  - Display values: d (deviation), d' (symmetry-adjusted), amplitude, L and R gains
  - Button to reverse d (flip device angle)
  - Button to toggle between manual AZ slider and actual phone orientation
- This will allow systematic testing of the panning math without rotating the phone

Front/back discrimination: Currently using simple amplitude attenuation for sounds behind (90° < d < 270°).
Better models exist using frequency-dependent HRTF (Head-Related Transfer Functions) that modify frequency
response based on angle. Research HRTF models and implement frequency-dependent filtering for more realistic
spatial audio (e.g., high-frequency rolloff for sounds behind).

## Done

in central london getting 300+ ble and 60+ wifi!
many do identify as lime and forrest bikes.  They should have a separate tone.

Give them static random directions for now and do simple stereo based on head rotation.

Im not getting dynamic panning. The sources do not rotate as I rotate the phone.

the single test tone should rotate as the phone rotates too
