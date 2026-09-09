#!/usr/bin/env bash
# Build the debug APK, install it on the connected phone, and launch the app.
# Usage: scripts/deploy.sh [--no-build] [--no-launch] [--logs]
#   --no-build   skip the Gradle build and install the existing APK
#   --no-launch  install only, don't start the app
#   --logs       stream GBCSync logs after launching (Ctrl-C to stop)
set -euo pipefail

cd "$(dirname "$0")/.."

ADB="$HOME/Library/Android/sdk/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.gbcsync.app"
ACTIVITY="$PKG/.MainActivity"

BUILD=1
LAUNCH=1
LOGS=0
for arg in "$@"; do
  case "$arg" in
    --no-build) BUILD=0 ;;
    --no-launch) LAUNCH=0 ;;
    --logs) LOGS=1 ;;
    *) echo "Unknown option: $arg" >&2; exit 2 ;;
  esac
done

[ -x "$ADB" ] || { echo "adb not found at $ADB" >&2; exit 1; }

# Require exactly one authorized device.
state="$("$ADB" get-state 2>/dev/null || true)"
if [ "$state" != "device" ]; then
  echo "No authorized device (adb state: '${state:-none}')." >&2
  "$ADB" devices >&2
  echo "Unlock the phone and accept the 'Allow USB debugging?' prompt, then retry." >&2
  exit 1
fi

if [ "$BUILD" -eq 1 ]; then
  echo "==> Building debug APK..."
  ./gradlew assembleDebug
fi

[ -f "$APK" ] || { echo "APK not found at $APK — build first." >&2; exit 1; }

echo "==> Installing $APK ..."
"$ADB" install -r "$APK"

if [ "$LAUNCH" -eq 1 ]; then
  echo "==> Launching $ACTIVITY ..."
  "$ADB" shell am start -n "$ACTIVITY" >/dev/null
fi

echo "==> Done."

if [ "$LOGS" -eq 1 ]; then
  echo "==> Streaming GBCSync logs (Ctrl-C to stop)..."
  "$ADB" logcat -c
  "$ADB" logcat | /usr/bin/grep --line-buffered "GBCSync"
fi
