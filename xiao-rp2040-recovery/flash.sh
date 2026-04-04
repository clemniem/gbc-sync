#!/bin/bash
# CLI for Seeed XIAO RP2040 recovery: flash sketch or connect via serial.

set -e

FQBN="rp2040:rp2040:seeed_xiao_rp2040"
SKETCH_DIR="$(cd "$(dirname "$0")" && pwd)"
BOARD_URL="https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json"

find_port() {
    local port
    port=$(ls /dev/tty.usbmodem* 2>/dev/null | head -1)
    if [ -z "$port" ]; then
        echo "Error: No serial port found. Is the board connected?" >&2
        exit 1
    fi
    echo "$port"
}

cmd_flash() {
    local port
    port=$(find_port)
    echo "Found serial port: $port"

    if ! arduino-cli core list 2>/dev/null | grep -q rp2040:rp2040; then
        echo "Installing RP2040 board package..."
        arduino-cli core update-index --additional-urls "$BOARD_URL"
        arduino-cli core install rp2040:rp2040 --additional-urls "$BOARD_URL"
    fi

    echo "Compiling sketch..."
    arduino-cli compile --fqbn "$FQBN" "$SKETCH_DIR"

    echo "Uploading to $port..."
    arduino-cli upload --fqbn "$FQBN" -p "$port" "$SKETCH_DIR"

    echo "Done. Run './flash.sh serial' to connect and send 'x'."
}

cmd_uf2() {
    local uf2_file="${1:-}"
    if [ -z "$uf2_file" ]; then
        echo "Usage: ./flash.sh uf2 <firmware.uf2>" >&2
        exit 1
    fi
    if [ ! -f "$uf2_file" ]; then
        echo "Error: File not found: $uf2_file" >&2
        exit 1
    fi

    local vol="/Volumes/RPI-RP2"
    if [ ! -d "$vol" ]; then
        echo "Error: RPI-RP2 volume not mounted. Is the board in bootloader mode?" >&2
        exit 1
    fi

    echo "Cleaning macOS metadata from $vol (may ask for password)..."
    sudo mdutil -i off "$vol" 2>/dev/null || true
    sudo mdutil -E "$vol" 2>/dev/null || true
    rm -f "$vol/.DS_Store"
    sudo rm -rf "$vol/.Trashes" "$vol/.fseventsd" 2>/dev/null || true
    sudo rm -rf "$vol/.Spotlight-V100" 2>/dev/null || true
    touch "$vol/.metadata_never_index"

    echo "Copying $uf2_file to $vol..."
    cp "$uf2_file" "$vol/"
    echo "Done. Board will reboot with new firmware."
}

cmd_serial() {
    local port
    port=$(find_port)
    echo "Connecting to $port at 115200 baud..."
    echo ""
    echo "Once connected, type 'x' to reboot the board into UF2 bootloader mode."
    echo "The board will disconnect and reappear as RPI-RP2 mass storage."
    echo "Then run: ./flash.sh uf2 <firmware.uf2>"
    echo ""
    echo "To quit screen: Ctrl-A then K"
    echo ""
    screen "$port" 115200
}

usage() {
    echo "Usage: ./flash.sh <command>"
    echo ""
    echo "Commands:"
    echo "  flash          Compile and upload the bootloader sketch"
    echo "  serial         Open serial connection (send 'x' to enter bootloader)"
    echo "  uf2 <file>     Clean macOS junk from RPI-RP2 and flash a .uf2 firmware"
}

case "${1:-}" in
    flash)  cmd_flash ;;
    serial) cmd_serial ;;
    uf2)    cmd_uf2 "$2" ;;
    *)      usage ;;
esac
