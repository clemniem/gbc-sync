// enter_bootloader.ino
// Minimal sketch for Seeed XIAO RP2040: sends the board into UF2 bootloader
// when 'x' is received over USB serial. Useful when BOOTSEL button doesn't work.

extern "C" {
  #include "pico/bootrom.h"
}

void setup() {
  Serial.begin(115200);
  unsigned long timeout = millis() + 2000;
  while (!Serial && millis() < timeout) { delay(10); }
  Serial.println("XIAO RP2040 ready. Send 'x' to enter UF2 bootloader.");
}

void loop() {
  if (Serial.available()) {
    char c = (char)Serial.read();
    if (c == 'x' || c == 'X') {
      Serial.println("Entering UF2 bootloader...");
      delay(50);
      reset_usb_boot(0, 0);
    }
  }
}
