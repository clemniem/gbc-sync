#!/bin/bash
# Flash the bootloader sketch to a Seeed XIAO RP2040 via arduino-cli,
# then open a serial connection to send 'x' and enter UF2 bootloader mode.

set -e

FQBN="rp2040:rp2040:seeed_xiao_rp2040"
SKETCH_DIR="$(cd "$(dirname "$0")" && pwd)"
BOARD_URL="https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json"

# Find serial port
PORT=$(ls /dev/tty.usbmodem* 2>/dev/null | head -1)
if [ -z "$PORT" ]; then
    echo "Error: No serial port found. Is the board connected?"
    exit 1
fi
echo "Found serial port: $PORT"

# Ensure board package is installed
if ! arduino-cli core list 2>/dev/null | grep -q rp2040:rp2040; then
    echo "Installing RP2040 board package..."
    arduino-cli core update-index --additional-urls "$BOARD_URL"
    arduino-cli core install rp2040:rp2040 --additional-urls "$BOARD_URL"
fi

# Compile
echo "Compiling sketch..."
arduino-cli compile --fqbn "$FQBN" "$SKETCH_DIR/enter_bootloader.ino"

# Upload
echo "Uploading to $PORT..."
arduino-cli upload --fqbn "$FQBN" -p "$PORT" "$SKETCH_DIR/enter_bootloader.ino"

echo ""
echo "Upload complete. Entering bootloader mode..."
sleep 2

# Send 'x' to trigger bootloader
echo "x" > "$PORT"

sleep 2

# Check if RPI-RP2 appeared
if diskutil list 2>/dev/null | grep -q "RPI-RP2"; then
    echo "Board is now in UF2 bootloader mode (RPI-RP2)."
    echo "Drag-drop a .uf2 file onto /Volumes/RPI-RP2/ or run:"
    echo "  cp firmware.uf2 /Volumes/RPI-RP2/"
else
    echo "RPI-RP2 not detected yet. Try manually:"
    echo "  screen $PORT 115200"
    echo "  (type 'x' and press Enter)"
fi
