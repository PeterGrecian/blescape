#!/bin/bash
# Build and deploy Blescape to connected Android device

set -e

echo "Building..."
./gradlew assembleDebug --quiet

echo "Installing..."
/mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk

echo "Done! Launch Blescape on your phone."
