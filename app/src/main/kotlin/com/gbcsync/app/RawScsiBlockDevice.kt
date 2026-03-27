package com.gbcsync.app

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.gbcsync.app.data.AppLog
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal SCSI READ(10) implementation that talks directly to USB bulk endpoints.
 * Bypasses libaums's ScsiBlockDevice entirely to avoid INQUIRY issues with
 * devices like RP2040/TinyUSB that return truncated SCSI responses.
 *
 * Only supports READ — no WRITE, no INQUIRY, no TEST UNIT READY.
 */
class RawScsiBlockDevice(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val interfaceId: Int = 0
) : BlockDeviceDriver {

    private var _blockSize = 512
    private var _lastBlockAddress = Int.MAX_VALUE
    private val tag = AtomicInteger(1)

    /** True if the device responded to at least one SCSI command during init */
    var initialized = false
        private set

    override val blockSize: Int get() = _blockSize
    override val blocks: Long get() = _lastBlockAddress.toLong()

    override fun init() {
        // Reset the Bulk-Only Transport state and wait for device to stabilize
        AppLog.d("Resetting USB Bulk-Only Transport...")
        resetBulkOnly()
        Thread.sleep(500)

        // Try READ CAPACITY (non-fatal if it fails)
        try {
            val cbw = buildCbw(tag.getAndIncrement(), 8, 0x80, buildReadCapacity())
            sendCbw(cbw)
            val buf = ByteArray(maxOf(64, inEndpoint.maxPacketSize))
            val dataResult = connection.bulkTransfer(inEndpoint, buf, buf.size, 5000)
            AppLog.d("READ CAPACITY data result=$dataResult")

            if (dataResult >= 8) {
                val cswBuf = ByteArray(13)
                connection.bulkTransfer(inEndpoint, cswBuf, cswBuf.size, 5000)

                val lastLba = ((buf[0].toInt() and 0xFF) shl 24) or
                        ((buf[1].toInt() and 0xFF) shl 16) or
                        ((buf[2].toInt() and 0xFF) shl 8) or
                        (buf[3].toInt() and 0xFF)
                val blkSize = ((buf[4].toInt() and 0xFF) shl 24) or
                        ((buf[5].toInt() and 0xFF) shl 16) or
                        ((buf[6].toInt() and 0xFF) shl 8) or
                        (buf[7].toInt() and 0xFF)

                _lastBlockAddress = lastLba
                _blockSize = blkSize
                initialized = true
                AppLog.i("READ CAPACITY OK: lastLba=$lastLba, blockSize=$blkSize")
                return
            } else {
                AppLog.w("READ CAPACITY short response: $dataResult bytes")
                if (dataResult >= 0) {
                    // Drain CSW
                    connection.bulkTransfer(inEndpoint, ByteArray(13), 13, 5000)
                }
                resetBulkOnly()
                Thread.sleep(200)
            }
        } catch (e: Exception) {
            AppLog.w("READ CAPACITY failed: ${e.message}")
            resetBulkOnly()
            Thread.sleep(200)
        }

        // Fallback: verify device responds with READ(10) of block 0
        try {
            AppLog.d("Trying READ(10) block 0 as fallback...")
            val cbw = buildCbw(tag.getAndIncrement(), 512, 0x80, buildRead10(0, 1))
            sendCbw(cbw)
            val buf = ByteArray(512)
            val dataResult = connection.bulkTransfer(inEndpoint, buf, buf.size, 5000)
            AppLog.d("READ(10) block 0 data result=$dataResult")

            if (dataResult > 0) {
                val cswBuf = ByteArray(13)
                connection.bulkTransfer(inEndpoint, cswBuf, cswBuf.size, 5000)
                initialized = true
                AppLog.i("READ(10) block 0 OK — device is responsive (using blockSize=512)")
            } else {
                AppLog.e("READ(10) block 0 failed: result=$dataResult")
            }
        } catch (e: Exception) {
            AppLog.e("Device not responding after init: ${e.message}")
        }
    }

    override fun read(deviceOffset: Long, buffer: ByteBuffer) {
        val blockCount = buffer.remaining() / _blockSize
        if (blockCount == 0) return

        val lba = deviceOffset
        val cbw = buildCbw(tag.getAndIncrement(), blockCount * _blockSize, 0x80,
            buildRead10(lba.toInt(), blockCount))
        sendCbw(cbw)
        val data = receiveData(blockCount * _blockSize)
        receiveCSW()

        buffer.put(data, 0, minOf(data.size, buffer.remaining()))
    }

    override fun write(deviceOffset: Long, buffer: ByteBuffer) {
        throw UnsupportedOperationException("Write not supported by RawScsiBlockDevice")
    }

    // --- SCSI command builders ---

    private fun buildRead10(lba: Int, blockCount: Int): ByteArray {
        val cmd = ByteArray(10)
        cmd[0] = 0x28.toByte() // READ(10)
        cmd[2] = ((lba shr 24) and 0xFF).toByte()
        cmd[3] = ((lba shr 16) and 0xFF).toByte()
        cmd[4] = ((lba shr 8) and 0xFF).toByte()
        cmd[5] = (lba and 0xFF).toByte()
        cmd[7] = ((blockCount shr 8) and 0xFF).toByte()
        cmd[8] = (blockCount and 0xFF).toByte()
        return cmd
    }

    private fun buildReadCapacity(): ByteArray {
        val cmd = ByteArray(10)
        cmd[0] = 0x25.toByte() // READ CAPACITY(10)
        return cmd
    }

    // --- USB Bulk-Only Transport ---

    private fun buildCbw(tag: Int, dataLength: Int, flags: Int, command: ByteArray): ByteArray {
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        cbw.putInt(0x43425355) // dCBWSignature "USBC"
        cbw.putInt(tag)
        cbw.putInt(dataLength)
        cbw.put(flags.toByte()) // bmCBWFlags (0x80 = data-in)
        cbw.put(0) // bCBWLUN
        cbw.put(command.size.toByte()) // bCBWCBLength
        cbw.put(command)
        return cbw.array()
    }

    private fun sendCbw(cbw: ByteArray) {
        val result = connection.bulkTransfer(outEndpoint, cbw, cbw.size, 5000)
        if (result < 0) {
            throw IOException("Failed to send CBW, result=$result")
        }
    }

    private fun receiveData(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val toRead = minOf(length - offset, inEndpoint.maxPacketSize * 128)
            val result = connection.bulkTransfer(inEndpoint, data, offset, toRead, 5000)
            if (result < 0) {
                throw IOException("Failed to receive data at offset $offset, result=$result")
            }
            offset += result
        }
        return data
    }

    private fun receiveCSW(): ByteArray {
        val csw = ByteArray(13)
        val result = connection.bulkTransfer(inEndpoint, csw, csw.size, 5000)
        if (result < 0) {
            throw IOException("Failed to receive CSW, result=$result")
        }
        val status = csw[12].toInt() and 0xFF
        if (status != 0) {
            AppLog.w("CSW status=$status (non-zero)")
        }
        return csw
    }

    private fun resetBulkOnly() {
        // USB Bulk-Only Mass Storage Reset
        try {
            val result = connection.controlTransfer(
                0x21, 0xFF, 0, interfaceId, null, 0, 2000
            )
            AppLog.d("Bulk-Only Reset result=$result")
        } catch (e: Exception) {
            AppLog.d("Bulk-Only Reset failed: ${e.message}")
        }

        // Clear HALT on both endpoints
        try {
            clearHaltFeature(outEndpoint)
            clearHaltFeature(inEndpoint)
        } catch (e: Exception) {
            AppLog.d("Clear HALT failed: ${e.message}")
        }

        // Drain any stale data from IN endpoint
        try {
            val drain = ByteArray(512)
            while (true) {
                val r = connection.bulkTransfer(inEndpoint, drain, drain.size, 100)
                if (r <= 0) break
                AppLog.d("Drained $r stale bytes from IN endpoint")
            }
        } catch (_: Exception) {}

        // Post-reset delay for device stabilization
        Thread.sleep(200)
    }

    private fun clearHaltFeature(endpoint: UsbEndpoint) {
        connection.controlTransfer(
            0x02, 1, 0, endpoint.address, null, 0, 1000
        )
    }
}
