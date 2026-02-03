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

WSL2 can't see USB devices directly. Deploy via PowerShell using the Windows
copy of adb (`C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe`).

**Step 1 — Copy APK to a Windows path** (adb.exe can't read WSL filesystem paths):

```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/tot/Downloads/app-debug.apk
```

**Step 2 — Install from PowerShell:**

```powershell
& 'C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe' install 'C:\Users\tot\Downloads\app-debug.apk'
```

Or as a single bash command that does both steps:

```bash
cp app/build/outputs/apk/debug/app-debug.apk /mnt/c/Users/tot/Downloads/app-debug.apk && powershell.exe -NoProfile -Command "& 'C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe' install 'C:\Users\tot\Downloads\app-debug.apk'"
```

### Device not showing up?

If `adb devices` returns empty or `unauthorized`, kill and restart the adb
server from PowerShell — this fixes it every time:

```powershell
& 'C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe' kill-server
& 'C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe' start-server
& 'C:\Users\tot\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
```

Accept the "Allow USB Debugging" prompt on the phone when it appears.

## Phone Setup

1. Enable Developer Options: Settings > About > tap "Build number" 7 times
2. Enable USB Debugging in Developer Options
3. Connect via USB, tap "Allow" when prompted to authorize computer

## Permissions Required

The app will request these on first launch:
- Location (required for WiFi/BLE scan results)
- Bluetooth Scan & Connect
