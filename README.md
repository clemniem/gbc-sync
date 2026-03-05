# GBC Sync

Android app that auto-syncs files from Game Boy Camera cart readers (JoeyJr, PicNRec) to your phone. Works on any Android device with USB Host (OTG) support. Uses [libaums](https://github.com/magnusja/libaums) to read FAT filesystems directly via USB Host API, bypassing Android's kernel USB mass storage driver.

## How it works

1. Plug in USB drive via OTG adapter → app auto-launches
2. Matches device against configured list → requests USB permission if needed
3. Auto-copies matching files to configured destination folder
4. Shows progress → done, unplug

Save files (`.sav`) are saved with a timestamp and camera infix (e.g. `grn/2026-03-04_143000-grn.sav`). Other files use duplicate detection (skip if same name + size).

## Compatibility

- Any Android phone/tablet with **USB Host (OTG) support** — most devices running Android 5.0+ (API 21)
- USB-C or Micro-USB OTG adapter for connecting USB-A cart readers
- Tested on Pixel 5 (Android 14), but not Pixel-specific

## Prerequisites

- **JDK 17+** (e.g. `sdk install java 21.0.5-tem`)
- **Gradle 8.9** (`sdk install gradle 8.9`)
- **Android SDK** with platform 35 (install via Android Studio or `sdkmanager`)

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

## Install

### Enable USB debugging

1. Settings → About phone → tap **Build number** 7 times to enable Developer Options
2. Settings → System → Developer options → **USB debugging** ON
3. Connect phone to computer via USB

### Install via adb

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
| PicNRec | XIAO RP2040 | 9114 | 51966 |

These are preconfigured as defaults in the app.

## Default device configs

| Device | Filter | Recursive | Behavior |
|--------|--------|-----------|----------|
| JoeyJr | `*.sav` | No | Copies save files into `<infix>/` subfolder with timestamped names |
| PicNRec | `*.png` | Yes | Copies photos into timestamped import folder, preserving original folder structure |

Files are saved to `Documents/GBCSync/<device-name>/` on the phone.

## Supported cameras

The app identifies which camera a cartridge belongs to and organizes files accordingly.

| Camera | Infix | Detection |
|--------|-------|-----------|
| Game Boy Camera (Green) | `grn` | Manual picker |
| Game Boy Camera (Yellow) | `ylw` | Manual picker |
| Game Boy Camera (Red) | `red` | Manual picker |
| Game Boy Camera (Blue) | `blu` | Manual picker |
| Game Boy Camera (Atomic Purple) | `pur` | Manual picker |
| MiniCam (PhotoRom) | `mip` | Auto-detected (ROM.GBC on cart) |
| MiniCam (GBCRom) | `mis` | Manual picker |
| PicNRec | `pic` | Auto-detected (PicNRec device) |

Configure which cameras you own in **Settings → My Gear**. Custom cameras (e.g. Cam+) can be added with a custom name and infix. All infixes are editable.

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
