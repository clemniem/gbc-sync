package com.gbcsync.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.gbcsync.app.data.AppLog
import kotlinx.coroutines.delay
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.nio.ByteBuffer

class BlockDeviceFactory(private val usbManager: UsbManager) {

    companion object {
        private const val MAX_INIT_RETRIES = 3
    }

    /**
     * Creates a RawScsiBlockDevice with a fresh USB connection.
     * Bypasses libaums entirely — no INQUIRY, no SCSI init that could corrupt state.
     * Retries with progressive backoff if the device doesn't respond.
     */
    suspend fun getRawBlockDeviceFresh(usbDevice: UsbDevice): RawScsiBlockDevice? {
        var massStorageInterface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until usbDevice.interfaceCount) {
            val iface = usbDevice.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE) {
                massStorageInterface = iface
                break
            }
        }
        if (massStorageInterface == null) {
            AppLog.w("No mass storage interface found")
            return null
        }

        var inEndpoint: android.hardware.usb.UsbEndpoint? = null
        var outEndpoint: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until massStorageInterface.endpointCount) {
            val ep = massStorageInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) inEndpoint = ep
                else outEndpoint = ep
            }
        }
        if (inEndpoint == null || outEndpoint == null) {
            AppLog.w("Missing bulk endpoints")
            return null
        }

        for (attempt in 1..MAX_INIT_RETRIES) {
            try {
                AppLog.d("Raw SCSI init attempt $attempt/$MAX_INIT_RETRIES...")
                val connection = usbManager.openDevice(usbDevice)
                if (connection == null) {
                    AppLog.w("Failed to open USB device")
                    return null
                }
                if (!connection.claimInterface(massStorageInterface, true)) {
                    AppLog.w("Failed to claim interface")
                    connection.close()
                    return null
                }

                delay(1000L * attempt)

                val device = RawScsiBlockDevice(connection, outEndpoint, inEndpoint, massStorageInterface.id)
                device.init()

                if (device.initialized) {
                    AppLog.i("RawScsiBlockDevice ready (attempt $attempt): blockSize=${device.blockSize}")
                    return device
                }

                AppLog.w("Init attempt $attempt: device not responding, closing and retrying...")
                connection.close()
            } catch (e: Exception) {
                AppLog.w("Init attempt $attempt failed: ${e.message}")
            }
        }

        AppLog.e("All $MAX_INIT_RETRIES raw SCSI init attempts failed")
        return null
    }

    /**
     * Reads block 0 to check for MBR partition table.
     * Returns the block offset of the first partition, or 0 for superfloppy.
     */
    fun findPartitionOffset(driver: BlockDeviceDriver): Long {
        try {
            val mbr = ByteBuffer.allocate(512)
            driver.read(0, mbr)
            val data = mbr.array()

            if ((data[510].toInt() and 0xFF) != 0x55 || (data[511].toInt() and 0xFF) != 0xAA) {
                AppLog.d("No MBR signature, treating as superfloppy")
                return 0
            }

            val firstByte = data[0].toInt() and 0xFF
            if (firstByte == 0xEB || firstByte == 0xE9) {
                val fat16Sig = String(data, 0x36, 5)
                val fat32Sig = String(data, 0x52, 5)
                if (fat16Sig.startsWith("FAT") || fat32Sig.startsWith("FAT")) {
                    AppLog.d("Block 0 is a FAT boot sector (superfloppy)")
                    return 0
                }
            }

            val partOffset = 446
            val partType = data[partOffset + 4].toInt() and 0xFF
            val lbaStart = ((data[partOffset + 8].toInt() and 0xFF)) or
                    ((data[partOffset + 9].toInt() and 0xFF) shl 8) or
                    ((data[partOffset + 10].toInt() and 0xFF) shl 16) or
                    ((data[partOffset + 11].toInt() and 0xFF) shl 24)

            if (partType != 0 && lbaStart > 0) {
                AppLog.i("MBR partition: type=0x${partType.toString(16)}, LBA start=$lbaStart")
                return lbaStart.toLong()
            }

            AppLog.d("No valid partition in MBR, treating as superfloppy")
        } catch (e: Exception) {
            AppLog.w("Error reading MBR: ${e.message}")
        }
        return 0
    }
}
