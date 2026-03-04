# GBC Sync

Android app for Pixel 5 that auto-syncs files from USB drives (JoeyJr, 2bitBridge) to your phone. Uses [libaums](https://github.com/nicholasgasior/libaums) to read FAT32 filesystems directly via USB Host API, bypassing Android's broken kernel FAT32 support.

## How it works

1. Plug in USB drive → app auto-launches
2. Matches device against configured list → requests USB permission if needed
3. Auto-copies matching files to configured destination folder
4. Shows progress → done, unplug

## Build

### GitHub Actions (recommended)

Push to GitHub and the CI workflow builds automatically. Download the APK from the workflow's **Artifacts** section.

### Local build

Prerequisites: JDK 17+, Android SDK with platform 34.

```bash
# Set ANDROID_HOME if not already set
export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew assembleDebug
```

## Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Make sure USB debugging is enabled on your Pixel 5.

## First-run setup

1. Open the app → go to **Settings** (gear icon)
2. Configure your devices with correct Vendor/Product IDs
   - Plug the device in, then check `adb shell lsusb` or Settings > Connected devices to find IDs
3. Set file filters (e.g. `*.sav` for save files, `*` for everything)
4. Set destination folder (default: `Documents/GBCSync/<device-name>/`)

## Usage

After setup, just plug in your USB device. The app auto-launches, syncs new/changed files, and shows progress. Already-synced files (same name + size) are skipped.

## Default device configs

| Device | Filter | Recursive | Notes |
|--------|--------|-----------|-------|
| JoeyJr | `*.sav` | No | Game Boy save files |
| 2bitBridge | `*` | Yes | All files including subfolders |

Vendor/Product IDs default to 0 (matches any USB mass storage device). Update these in Settings once you know your device IDs.
