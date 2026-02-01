# Blescape

Android app that scans WiFi networks, BLE devices, and phone orientation, displaying them interactively.

## Features

- **WiFi Scanning**: Lists nearby networks with SSID, signal strength (dBm + bars), and frequency band
- **BLE Scanning**: Lists Bluetooth LE devices with name, address, and signal strength
- **Orientation**: Real-time azimuth (compass), pitch, and roll from device sensors
- Auto-scans every 10 seconds with countdown indicator
- Dark theme UI with expandable panels

## Build & Deploy (WSL2 to Physical Device)

### Prerequisites

Environment variables in `~/.bashrc`:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=~/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

### Build

```bash
cd /home/tot/blescape
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Deploy to Phone

WSL2 can't see USB devices directly, but Windows ADB works from WSL:

```bash
# Check device is connected
/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe devices

# Install
/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

### One-liner Build & Deploy

```bash
./gradlew assembleDebug && /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

## Phone Setup

1. Enable Developer Options: Settings > About > tap "Build number" 7 times
2. Enable USB Debugging in Developer Options
3. Connect via USB, tap "Allow" when prompted to authorize computer

## Permissions Required

The app will request these on first launch:
- Location (required for WiFi/BLE scan results)
- Bluetooth Scan & Connect
