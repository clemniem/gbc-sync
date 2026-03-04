# GBC Sync

Android app for Pixel 5 that auto-syncs files from USB drives (JoeyJr, 2bitBridge) to your phone. Uses [libaums](https://github.com/magnusja/libaums) to read FAT32 filesystems directly via USB Host API, bypassing Android's broken kernel FAT32 support on Pixel 5.

## How it works

1. Plug in USB drive via OTG adapter → app auto-launches
2. Matches device against configured list → requests USB permission if needed
3. Auto-copies matching files to configured destination folder
4. Shows progress → done, unplug

Save files (`.sav`) are timestamped on each sync (e.g. `SRAM_2026-03-04_143000.SAV`) to preserve history. Other files use duplicate detection (skip if same name + size).

## Prerequisites

- **JDK 17+** (e.g. `sdk install java 21.0.5-tem`)
- **Gradle 8.9** (`sdk install gradle 8.9`)
- **Android SDK** with platform 35 (install via Android Studio or `sdkmanager`)
- **USB-C OTG adapter** for connecting USB-A devices to Pixel 5

## Build

### GitHub Actions (recommended)

Push to GitHub and the CI workflow builds automatically. Download the APK from the Actions tab → latest run → **Artifacts** section.

### Local build

```bash
./gradlew assembleDebug
```

If you get an SDK error, create `local.properties` in the project root:

```
sdk.dir=/Users/<you>/Library/Android/sdk
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Install on Pixel 5

### Enable USB debugging

1. Settings → About phone → tap **Build number** 7 times to enable Developer Options
2. Settings → System → Developer options → **USB debugging** ON
3. Connect phone to computer via USB

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

First time you'll see a prompt on the phone to **Allow USB debugging** — tap Allow (check "Always allow from this computer").

## Finding device Vendor/Product IDs

You need the Vendor ID and Product ID for each USB device. Two ways to find them:

### On Mac

Plug the device into your Mac and run:

```bash
ioreg -p IOUSB -l | grep -E '"USB Product Name"|"idVendor"|"idProduct"'
```

### On the Pixel 5

Plug the device into the phone via OTG and run:

```bash
adb shell lsusb
```

### Known device IDs

| Device | USB Name | Vendor ID | Product ID |
|--------|----------|-----------|------------|
| JoeyJr | Dragon Drop FRAM cart | 49745 | 8224 |
| 2bitBridge | XIAO RP2040 | 9114 | 51966 |

These are preconfigured as defaults in the app.

## Default device configs

| Device | Filter | Recursive | Behavior |
|--------|--------|-----------|----------|
| JoeyJr | `*.sav` | No | Copies `SRAM.SAV` with timestamp (e.g. `SRAM_2026-03-04_143000.SAV`) |
| 2bitBridge | `*.png` | Yes | Copies Game Boy Camera photos from `dcim/1xx_brdg/` folders, skips duplicates |

Files are saved to `Documents/GBCSync/<device-name>/` on the phone.

## Usage

1. Open the app and verify your device configs in **Settings** (gear icon)
2. Plug in your USB device via OTG adapter
3. Grant USB permission when prompted (one-time per device)
4. Files auto-sync with progress shown on the home screen
5. Unplug when done

### Debugging

The app has a built-in **Live Log** tab on the home screen (enabled by default). It shows real-time USB detection, file scanning, and copy operations. Toggle it in Settings → Debug Log.

If sync fails, a **Retry Sync** button appears on the status card. The app retries device initialization up to 3 times automatically before showing an error.

## Technical notes

- **Superfloppy support**: JoeyJr has no partition table (FAT32 starts at block 0). The app handles this by falling back to direct filesystem access when no MBR/GPT is found.
- **libaums**: Bypasses Android's kernel USB mass storage driver entirely. Issues SCSI commands directly via USB Host API, then reads FAT32 structures in userspace.
- **File filters**: Simple glob patterns — `*.sav`, `*.png`, `*.gb*`, `*` (all files).
