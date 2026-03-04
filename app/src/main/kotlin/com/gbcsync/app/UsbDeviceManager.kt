package com.gbcsync.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncLogEntry
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncState(
    val status: Status = Status.IDLE,
    val deviceName: String = "",
    val currentFile: String = "",
    val filesCopied: Int = 0,
    val totalFiles: Int = 0,
    val errors: Int = 0,
    val error: String? = null
) {
    enum class Status { IDLE, CONNECTING, SYNCING, DONE, ERROR }

    val progress: Float
        get() = if (totalFiles > 0) filesCopied.toFloat() / totalFiles else 0f
}

class UsbDeviceManager(
    private val context: Context,
    private val repository: SyncRepository,
    private val scope: CoroutineScope
) {
    companion object {
        const val ACTION_USB_PERMISSION = "com.gbcsync.app.USB_PERMISSION"
        private const val MAX_INIT_RETRIES = 3
        private const val INIT_RETRY_DELAY_MS = 1000L
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    AppLog.d("USB device detached")
                    _connectedDevice.value = null
                    _syncState.value = SyncState()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        AppLog.d("USB permission granted")
                        scope.launch(Dispatchers.IO) { startSync() }
                    } else {
                        AppLog.w("USB permission denied")
                        _syncState.value = SyncState(
                            status = SyncState.Status.ERROR,
                            error = "USB permission denied"
                        )
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    fun unregister() {
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    fun onUsbDeviceAttached() {
        AppLog.i("USB device attached event received")
        scope.launch(Dispatchers.IO) { detectAndSync() }
    }

    fun retrySync() {
        AppLog.i("Manual sync retry requested")
        scope.launch(Dispatchers.IO) { detectAndSync() }
    }

    private suspend fun detectAndSync() {
        AppLog.d("Scanning for mass storage devices...")
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) {
            AppLog.w("No mass storage devices found")
            return
        }
        AppLog.d("Found ${massStorageDevices.size} mass storage device(s)")

        val configs = repository.deviceConfigs.first()
        AppLog.d("Loaded ${configs.size} device config(s)")

        for (device in massStorageDevices) {
            val usbDevice = device.usbDevice
            AppLog.d("Device: vendorId=${usbDevice.vendorId} productId=${usbDevice.productId} name=${usbDevice.deviceName}")
            val matchedConfig = findMatchingConfig(usbDevice, configs)

            _connectedDevice.value = matchedConfig?.name ?: "Unknown USB (${usbDevice.vendorId}:${usbDevice.productId})"
            AppLog.i("Matched config: ${matchedConfig?.name ?: "none"}")
            _syncState.value = SyncState(status = SyncState.Status.CONNECTING, deviceName = _connectedDevice.value ?: "")

            if (!usbManager.hasPermission(usbDevice)) {
                AppLog.d("Requesting USB permission...")
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(usbDevice, permissionIntent)
                return
            }

            startSync()
            return
        }
    }

    private fun findMatchingConfig(usbDevice: UsbDevice, configs: List<DeviceConfig>): DeviceConfig? {
        for (config in configs) {
            if (config.vendorId != 0 && config.productId != 0 &&
                config.vendorId == usbDevice.vendorId && config.productId == usbDevice.productId) {
                return config
            }
        }
        return configs.firstOrNull { it.vendorId == 0 && it.productId == 0 }
    }

    private suspend fun startSync() {
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) return

        val configs = repository.deviceConfigs.first()

        for (storageDevice in massStorageDevices) {
            val config = findMatchingConfig(storageDevice.usbDevice, configs) ?: configs.firstOrNull() ?: continue
            val destDir = repository.getDestDir(config)
            val deviceName = config.name
            val usbDev = storageDevice.usbDevice

            if (!usbManager.hasPermission(usbDev)) {
                AppLog.w("USB permission lost, re-requesting...")
                val permissionIntent = PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_MUTABLE
                )
                usbManager.requestPermission(usbDev, permissionIntent)
                return
            }

            AppLog.i("=== SYNC START: $deviceName (vendor=${usbDev.vendorId}, product=${usbDev.productId}) ===")

            when {
                config.vendorId == 9114 && config.productId == 51966 ->
                    syncBridge(storageDevice, config, destDir, deviceName)
                else ->
                    syncJoeyJr(storageDevice, config, destDir, deviceName)
            }
        }
    }

    /**
     * JoeyJr sync flow (vendor=49745, product=8224).
     * Tries libaums first, falls back to raw SCSI with a fresh connection.
     * No reflection/extraction — keep it simple.
     */
    private suspend fun syncJoeyJr(
        storageDevice: UsbMassStorageDevice,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        // Step 1: Try libaums
        AppLog.i("[JoeyJr] Attempting libaums init...")
        try {
            storageDevice.init()
            val partition = storageDevice.partitions.firstOrNull()
            if (partition != null) {
                val fs = partition.fileSystem
                AppLog.i("[JoeyJr] libaums OK: ${fs.volumeLabel}, capacity=${fs.capacity}")
                syncWithLibaums(storageDevice, fs, config, destDir, deviceName)
                AppLog.i("[JoeyJr] Sync complete via libaums")
                return
            } else {
                AppLog.w("[JoeyJr] libaums failed: no partitions found")
            }
        } catch (e: Exception) {
            AppLog.w("[JoeyJr] libaums failed: ${e.message}")
        }

        // Step 2: Fall back to raw SCSI with fresh connection
        AppLog.i("[JoeyJr] Falling back to raw SCSI...")
        try { storageDevice.close() } catch (_: Exception) {}

        val freshDevice = getRawBlockDeviceFresh(storageDevice.usbDevice)
        if (freshDevice == null) {
            AppLog.e("[JoeyJr] Cannot access block device via raw SCSI")
            _syncState.value = SyncState(
                status = SyncState.Status.ERROR,
                error = "Cannot access USB device. Try unplugging and re-plugging."
            )
            return
        }

        try {
            val partitionOffset = findPartitionOffset(freshDevice)
            AppLog.d("[JoeyJr] Partition offset: $partitionOffset")
            val fatReader = Fat12Reader(freshDevice, partitionOffset)
            syncWithFatReader(fatReader, config, destDir, deviceName)
            AppLog.i("[JoeyJr] Sync complete via raw SCSI")
        } catch (e: Exception) {
            AppLog.e("[JoeyJr] FAT reader sync error", e)
            _syncState.value = SyncState(
                status = SyncState.Status.ERROR, error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * 2bitBridge sync flow (vendor=9114, product=51966).
     * Tries libaums first, then extracted connection, then fresh connection as last resort.
     */
    private suspend fun syncBridge(
        storageDevice: UsbMassStorageDevice,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        // Step 1: Try libaums
        AppLog.i("[Bridge] Attempting libaums init...")
        try {
            storageDevice.init()
            val partition = storageDevice.partitions.firstOrNull()
            if (partition != null) {
                val fs = partition.fileSystem
                AppLog.i("[Bridge] libaums OK: ${fs.volumeLabel}, capacity=${fs.capacity}")
                syncWithLibaums(storageDevice, fs, config, destDir, deviceName)
                AppLog.i("[Bridge] Sync complete via libaums")
                return
            } else {
                AppLog.w("[Bridge] libaums failed: no partitions found")
            }
        } catch (e: Exception) {
            AppLog.w("[Bridge] libaums failed: ${e.message}")
        }

        // Step 2: Extract connection from libaums (don't close — that kills the connection)
        AppLog.i("[Bridge] Extracting connection from libaums for raw SCSI...")
        val blockDevice = extractBlockDeviceFromLibaums(storageDevice)

        if (blockDevice != null) {
            try {
                val partitionOffset = findPartitionOffset(blockDevice)
                AppLog.d("[Bridge] Partition offset: $partitionOffset")
                val fatReader = Fat12Reader(blockDevice, partitionOffset)
                syncWithFatReader(fatReader, config, destDir, deviceName)
                AppLog.i("[Bridge] Sync complete via extracted connection")
                return
            } catch (e: Exception) {
                AppLog.e("[Bridge] Extraction sync error: ${e.message}")
            }
        } else {
            AppLog.w("[Bridge] Extraction failed")
        }

        // Step 3: Fresh connection as last resort
        AppLog.i("[Bridge] Trying fresh connection as last resort...")
        try { storageDevice.close() } catch (_: Exception) {}

        val freshDevice = getRawBlockDeviceFresh(storageDevice.usbDevice)
        if (freshDevice == null) {
            AppLog.e("[Bridge] Cannot access block device via fresh connection")
            _syncState.value = SyncState(
                status = SyncState.Status.ERROR,
                error = "Cannot access USB device. Try unplugging and re-plugging."
            )
            return
        }

        try {
            val partitionOffset = findPartitionOffset(freshDevice)
            AppLog.d("[Bridge] Partition offset: $partitionOffset")
            val fatReader = Fat12Reader(freshDevice, partitionOffset)
            syncWithFatReader(fatReader, config, destDir, deviceName)
            AppLog.i("[Bridge] Sync complete via fresh connection")
        } catch (e: Exception) {
            AppLog.e("[Bridge] FAT reader sync error", e)
            _syncState.value = SyncState(
                status = SyncState.Status.ERROR, error = e.message ?: "Unknown error"
            )
        }
    }

    // --- libaums sync path (FAT32 with partition table) ---

    private suspend fun syncWithLibaums(
        storageDevice: UsbMassStorageDevice,
        fs: FileSystem,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        try {
            AppLog.i("Syncing $deviceName -> ${destDir.absolutePath}")
            _syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName, currentFile = "Scanning files...")

            val root = fs.rootDirectory
            AppLog.d("chunkSize=${fs.chunkSize}")

            AppLog.d("Scanning files (filter=${config.fileFilter}, recursive=${config.recursive})...")
            val filesToCopy = mutableListOf<Pair<UsbFile, String>>()
            collectLibaumsFiles(root, "", config.fileFilter, config.recursive, filesToCopy)
            AppLog.d("Found ${filesToCopy.size} matching file(s) on device")

            val newFiles = filesToCopy.filter { (usbFile, relativePath) ->
                val fileName = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                if (fileName.lowercase().endsWith(".sav")) true
                else repository.shouldCopyFile(fileName, usbFile.length, destDir)
            }
            AppLog.i("${newFiles.size} new file(s) to copy (${filesToCopy.size - newFiles.size} already synced)")

            _syncState.value = _syncState.value.copy(totalFiles = newFiles.size)

            if (newFiles.isEmpty()) {
                AppLog.i("All files up to date")
                _syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName)
                storageDevice.close()
                return
            }

            var copied = 0
            var errors = 0
            val chunkSize = fs.chunkSize

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                val targetPath = addTimestampIfSav(originalPath)
                _syncState.value = _syncState.value.copy(currentFile = targetPath)

                try {
                    AppLog.d("Copying $targetPath (${usbFile.length} bytes)...")
                    copyLibaumsFile(usbFile, destDir, targetPath, chunkSize, fs)
                    copied++
                    AppLog.d("Copied $targetPath OK")
                    logSyncEntry(targetPath, deviceName, usbFile.length)
                    _syncState.value = _syncState.value.copy(filesCopied = copied)
                } catch (e: Exception) {
                    errors++
                    AppLog.e("Error copying $targetPath", e)
                    _syncState.value = _syncState.value.copy(errors = errors)
                }
            }

            finishSync(deviceName, copied, newFiles.size, errors)
            storageDevice.close()
        } catch (e: Exception) {
            AppLog.e("Sync error", e)
            _syncState.value = SyncState(status = SyncState.Status.ERROR, error = e.message ?: "Unknown error")
            try { storageDevice.close() } catch (_: Exception) {}
        }
    }

    // --- FAT12/16 reader sync path (superfloppy without partition table) ---

    private suspend fun syncWithFatReader(
        fatReader: Fat12Reader,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        AppLog.i("Syncing $deviceName -> ${destDir.absolutePath} (via ${fatReader.fatType} reader)")
        _syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName, currentFile = "Scanning files...")

        AppLog.d("Scanning files (filter=${config.fileFilter}, recursive=${config.recursive})...")
        val allFiles = if (config.recursive) {
            fatReader.listFilesRecursive(config.fileFilter) { name, filter ->
                repository.matchesFilter(name, filter)
            }
        } else {
            fatReader.listRootFiles(config.fileFilter) { name, filter ->
                repository.matchesFilter(name, filter)
            }
        }
        AppLog.d("Found ${allFiles.size} matching file(s) on device")

        val newFiles = allFiles.filter { file ->
            if (file.name.lowercase().endsWith(".sav")) true
            else repository.shouldCopyFile(file.relativePath, file.length, destDir)
        }
        AppLog.i("${newFiles.size} new file(s) to copy (${allFiles.size - newFiles.size} already synced)")

        _syncState.value = _syncState.value.copy(totalFiles = newFiles.size)

        if (newFiles.isEmpty()) {
            AppLog.i("All files up to date")
            _syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName)
            return
        }

        var copied = 0
        var errors = 0

        for (file in newFiles) {
            val targetPath = addTimestampIfSav(file.relativePath)
            _syncState.value = _syncState.value.copy(currentFile = targetPath)

            try {
                AppLog.d("Copying $targetPath (${file.length} bytes)...")
                copyFat32LibFile(file, destDir, targetPath)
                copied++
                AppLog.d("Copied $targetPath OK")
                logSyncEntry(targetPath, deviceName, file.length)
                _syncState.value = _syncState.value.copy(filesCopied = copied)
            } catch (e: Exception) {
                errors++
                AppLog.e("Error copying $targetPath", e)
                _syncState.value = _syncState.value.copy(errors = errors)
            }
        }

        finishSync(deviceName, copied, newFiles.size, errors)
    }

    // --- Shared helpers ---

    private suspend fun logSyncEntry(targetPath: String, deviceName: String, fileSize: Long) {
        try {
            repository.addSyncLogEntry(
                SyncLogEntry(
                    fileName = targetPath,
                    deviceName = deviceName,
                    timestamp = System.currentTimeMillis(),
                    fileSize = fileSize
                )
            )
        } catch (e: Exception) {
            AppLog.w("Failed to write sync log entry: ${e.message}")
        }
    }

    private fun finishSync(deviceName: String, copied: Int, total: Int, errors: Int) {
        val summary = buildString {
            append("Sync complete: $copied copied")
            if (errors > 0) append(", $errors failed")
        }
        AppLog.i(summary)

        _syncState.value = SyncState(
            status = if (errors > 0 && copied == 0) SyncState.Status.ERROR else SyncState.Status.DONE,
            deviceName = deviceName,
            filesCopied = copied,
            totalFiles = total,
            errors = errors,
            error = if (errors > 0) "$errors file(s) failed to copy" else null
        )
    }

    private fun collectLibaumsFiles(
        dir: UsbFile,
        pathPrefix: String,
        filter: String,
        recursive: Boolean,
        result: MutableList<Pair<UsbFile, String>>
    ) {
        try {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    if (recursive) {
                        val subPath = if (pathPrefix.isEmpty()) file.name else "$pathPrefix/${file.name}"
                        collectLibaumsFiles(file, subPath, filter, recursive, result)
                    }
                } else {
                    if (repository.matchesFilter(file.name, filter)) {
                        val relativePath = if (pathPrefix.isEmpty()) file.name else "$pathPrefix/${file.name}"
                        result.add(file to relativePath)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("Error listing directory $pathPrefix", e)
        }
    }

    /**
     * Creates a RawScsiBlockDevice with a fresh USB connection.
     * Bypasses libaums entirely — no INQUIRY, no SCSI init that could corrupt state.
     * Retries with progressive backoff if the device doesn't respond.
     */
    private fun getRawBlockDeviceFresh(usbDevice: UsbDevice): RawScsiBlockDevice? {
        // Find mass storage interface and endpoints
        var massStorageInterface: UsbInterface? = null
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

        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null
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

        // Retry with progressive backoff — each attempt opens a fresh connection
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

                // Progressive delay before init — give device more time on each attempt
                Thread.sleep(1000L * attempt)

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
     * Extracts the working USB connection from libaums's internal objects via reflection.
     * libaums's connection successfully communicates with the device (INQUIRY data was received),
     * even though its parser crashes on truncated responses. We reuse that exact connection
     * for our raw SCSI implementation instead of opening a fresh one (which doesn't work
     * with RP2040/TinyUSB devices).
     */
    private fun extractBlockDeviceFromLibaums(storageDevice: UsbMassStorageDevice): RawScsiBlockDevice? {
        try {
            // Step 1: Get the private usbCommunication field from UsbMassStorageDevice
            val commField = storageDevice.javaClass.getDeclaredField("usbCommunication")
            commField.isAccessible = true
            val communication = commField.get(storageDevice)
            if (communication == null) {
                AppLog.w("usbCommunication field is null")
                return null
            }
            AppLog.d("Got usbCommunication: ${communication.javaClass.name}")

            // Step 2: Get deviceConnection from AndroidUsbCommunication (parent class)
            // Walk up the class hierarchy to find the field
            var connectionField: java.lang.reflect.Field? = null
            var clazz: Class<*>? = communication.javaClass
            while (clazz != null && connectionField == null) {
                try {
                    connectionField = clazz.getDeclaredField("deviceConnection")
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }
            if (connectionField == null) {
                AppLog.w("Could not find deviceConnection field in ${communication.javaClass.name}")
                return null
            }
            connectionField.isAccessible = true
            val connection = connectionField.get(communication) as? UsbDeviceConnection
            if (connection == null) {
                AppLog.w("deviceConnection is null")
                return null
            }
            AppLog.d("Got UsbDeviceConnection from libaums")

            // Step 3: Get endpoints and interface
            var outEp: UsbEndpoint? = null
            var inEp: UsbEndpoint? = null
            var usbIface: UsbInterface? = null

            // Try getter methods first (AndroidUsbCommunication has these)
            try {
                val getOut = communication.javaClass.getMethod("getOutEndpoint")
                val getIn = communication.javaClass.getMethod("getInEndpoint")
                val getIface = communication.javaClass.getMethod("getUsbInterface")
                outEp = getOut.invoke(communication) as? UsbEndpoint
                inEp = getIn.invoke(communication) as? UsbEndpoint
                usbIface = getIface.invoke(communication) as? UsbInterface
            } catch (e: Exception) {
                AppLog.d("Getter methods failed, trying fields: ${e.message}")
            }

            // Fallback: try field access
            if (outEp == null || inEp == null) {
                var c: Class<*>? = communication.javaClass
                while (c != null && (outEp == null || inEp == null)) {
                    for (f in c.declaredFields) {
                        f.isAccessible = true
                        when (f.name) {
                            "outEndpoint" -> outEp = f.get(communication) as? UsbEndpoint
                            "inEndpoint" -> inEp = f.get(communication) as? UsbEndpoint
                            "usbInterface" -> usbIface = f.get(communication) as? UsbInterface
                        }
                    }
                    c = c.superclass
                }
            }

            if (outEp == null || inEp == null) {
                AppLog.w("Could not extract endpoints from libaums")
                return null
            }
            AppLog.d("Got endpoints: OUT=${outEp.address}, IN=${inEp.address}, interface=${usbIface?.id}")

            // Step 4: Drain any stale data from IN endpoint (e.g. CSW from failed INQUIRY)
            val drain = ByteArray(64)
            var drained = 0
            while (true) {
                val r = connection.bulkTransfer(inEp, drain, drain.size, 100)
                if (r <= 0) break
                drained += r
                AppLog.d("Drained $r stale bytes from IN endpoint")
            }
            if (drained > 0) AppLog.i("Drained $drained total stale bytes")

            // Step 5: Create RawScsiBlockDevice with the extracted connection
            val device = RawScsiBlockDevice(connection, outEp, inEp, usbIface?.id ?: 0)
            device.init()

            if (device.initialized) {
                AppLog.i("RawScsiBlockDevice ready via libaums connection: blockSize=${device.blockSize}")
                return device
            } else {
                AppLog.w("RawScsiBlockDevice init failed via libaums connection")
                return null
            }
        } catch (e: Exception) {
            AppLog.e("Failed to extract connection from libaums: ${e.message}")
            return null
        }
    }

    /**
     * Reads block 0 to check for MBR partition table.
     * Returns the block offset of the first partition, or 0 for superfloppy.
     */
    private fun findPartitionOffset(driver: BlockDeviceDriver): Long {
        try {
            val mbr = ByteBuffer.allocate(512)
            driver.read(0, mbr)
            val data = mbr.array()

            // Check MBR signature
            if ((data[510].toInt() and 0xFF) != 0x55 || (data[511].toInt() and 0xFF) != 0xAA) {
                AppLog.d("No MBR signature, treating as superfloppy")
                return 0
            }

            // Check if this looks like a VBR (FAT boot sector) rather than MBR
            // FAT boot sectors start with EB xx 90 or E9 xx xx
            val firstByte = data[0].toInt() and 0xFF
            if (firstByte == 0xEB || firstByte == 0xE9) {
                // Could be either MBR or VBR — check for "FAT" string at offset 0x36 or 0x52
                val fat16Sig = String(data, 0x36, 5)
                val fat32Sig = String(data, 0x52, 5)
                if (fat16Sig.startsWith("FAT") || fat32Sig.startsWith("FAT")) {
                    AppLog.d("Block 0 is a FAT boot sector (superfloppy)")
                    return 0
                }
            }

            // Parse first partition entry at offset 446
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

    private fun addTimestampIfSav(path: String): String {
        if (!path.lowercase().endsWith(".sav")) return path
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dot = path.lastIndexOf('.')
        return "${path.substring(0, dot)}_$timestamp${path.substring(dot)}"
    }

    private fun copyLibaumsFile(usbFile: UsbFile, destDir: File, relativePath: String, chunkSize: Int, fs: FileSystem) {
        val destFile = File(destDir, relativePath)
        destFile.parentFile?.mkdirs()

        FileOutputStream(destFile).use { fos ->
            fos.channel.truncate(0)
            val buffer = ByteArray(chunkSize)
            val inputStream = UsbFileStreamFactory.createBufferedInputStream(usbFile, fs)
            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
        }

        if (destFile.length() != usbFile.length) {
            AppLog.w("Size mismatch for $relativePath: expected ${usbFile.length}, got ${destFile.length()}")
        }
    }

    private fun copyFat32LibFile(file: FatFsFile, destDir: File, relativePath: String) {
        val destFile = File(destDir, relativePath)
        destFile.parentFile?.mkdirs()

        FileOutputStream(destFile).use { fos ->
            val buffer = ByteArray(8192)
            file.readContents().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
        }

        if (destFile.length() != file.length) {
            AppLog.w("Size mismatch for $relativePath: expected ${file.length}, got ${destFile.length()}")
        }
    }
}
