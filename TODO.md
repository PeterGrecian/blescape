## Done

- Global WiFi sweep every 10s; continuous BLE scanning with real-time updates
  (callbacks fire every 1-3s per device — no more 5s on / 5s dead cycle)
- Stale BLE devices pruned after 20s of silence
- Show 4 strongest signals only, summarize the rest
- Compactified layout (smaller padding, fonts, inline signal bars)
- MAC address → manufacturer via bundled OUI table (WiFi and BLE rows both show it)
  - Note: many BLE devices (especially iOS) use rotating random addresses;
    OUI lookup only works for public/static addresses

## Up next

- Per-device pin / monitor: tap a device to "lock on" and see its RSSI over time
- Auto-classify mobility vendors (Lime, Bird, etc.) — seed OUI table with their prefixes
  and surface them prominently
- Expand OUI table or add periodic online refresh
- "Xs ago" timestamp on BLE devices so you can see which ones just disappeared


in central london getting 300+ ble and 60+ wifi!
many do identify as lime and forrest bikes.  They should have a separate tone.  

do audio syntheses based on the sources.  Give them static random directions for now and do simple stereo based on head rotation.