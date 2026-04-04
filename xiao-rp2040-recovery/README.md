# Seeed XIAO RP2040 Recovery (without BOOTSEL)

Flash a small sketch over USB serial that lets you enter the UF2 bootloader by
sending `x` — no BOOTSEL button press needed. Once in bootloader mode the board
appears as `RPI-RP2` mass storage and you can drag-drop any `.uf2` firmware.

## Prerequisites

- macOS with Homebrew
- `arduino-cli` (`brew install arduino-cli`)
- A data-capable USB-C cable

## Setup (one-time)

```bash
# Add the RP2040 board package
arduino-cli core update-index \
  --additional-urls https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json

arduino-cli core install rp2040:rp2040 \
  --additional-urls https://github.com/earlephilhower/arduino-pico/releases/download/global/package_rp2040_index.json
```

## Flash the bootloader sketch

```bash
# Find the serial port (look for usbmodem)
ls /dev/tty.usbmodem*

# Compile and upload (replace the port with yours)
arduino-cli compile --fqbn rp2040:rp2040:seeed_xiao_rp2040 enter_bootloader.ino
arduino-cli upload --fqbn rp2040:rp2040:seeed_xiao_rp2040 -p /dev/tty.usbmodemXXXX enter_bootloader.ino
```

## Enter bootloader mode

```bash
# Connect to the serial port
screen /dev/tty.usbmodemXXXX 115200

# Type x and press Enter — the board reboots into UF2 bootloader
```

The board disconnects from serial and reappears as `RPI-RP2` mass storage.
Confirm with:

```bash
diskutil list | grep RPI-RP2
```

## Flash a .uf2 firmware

Drag-drop your `.uf2` file onto the `RPI-RP2` drive, or from the terminal:

```bash
cp firmware.uf2 /Volumes/RPI-RP2/
```

The board reboots automatically with the new firmware.
