package com.gbcsync.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
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
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.File
import java.io.FileOutputStream
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

            // Try libaums native FAT32 first
            var synced = false
            for (attempt in 1..MAX_INIT_RETRIES) {
                try {
                    AppLog.d("Initializing storage device (attempt $attempt/$MAX_INIT_RETRIES)...")
                    storageDevice.init()
                    val partition = storageDevice.partitions.firstOrNull()
                    if (partition != null) {
                        val fs = partition.fileSystem
                        AppLog.i("libaums FAT32: ${fs.volumeLabel}, capacity=${fs.capacity}")
                        syncWithLibaums(storageDevice, fs, config, destDir, deviceName)
                        synced = true
                        break
                    } else {
                        AppLog.w("No partitions found after init")
                        break // Don't retry - partitions won't appear
                    }
                } catch (e: Exception) {
                    AppLog.w("libaums init attempt $attempt failed: ${e.message}")
                    try { storageDevice.close() } catch (_: Exception) {}
                    if (attempt < MAX_INIT_RETRIES) {
                        delay(INIT_RETRY_DELAY_MS)
                    }
                }
            }

            if (synced) continue

            // Fallback: direct FAT12/16 reader via block device
            AppLog.i("Falling back to FAT12/16 reader...")
            val blockDevice = getBlockDevice(storageDevice)
            if (blockDevice == null) {
                AppLog.e("Cannot access block device")
                _syncState.value = SyncState(
                    status = SyncState.Status.ERROR,
                    error = "Cannot access USB device. Try unplugging and re-plugging."
                )
                try { storageDevice.close() } catch (_: Exception) {}
                continue
            }

            try {
                val fatReader = Fat12Reader(blockDevice)
                syncWithFatReader(fatReader, config, destDir, deviceName)
                storageDevice.close()
            } catch (e: Exception) {
                AppLog.e("FAT reader sync error", e)
                _syncState.value = SyncState(
                    status = SyncState.Status.ERROR,
                    error = e.message ?: "Unknown error"
                )
                try { storageDevice.close() } catch (_: Exception) {}
            }
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
            _syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName)

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
        _syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName)

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

    private fun getBlockDevice(device: UsbMassStorageDevice): BlockDeviceDriver? {
        // UsbMassStorageDevice doesn't store BlockDeviceDriver as a field —
        // it's a local variable in setupDevice() passed into Partition objects.
        // For superfloppy devices (no partition table), partitions are empty,
        // so we need to create the BlockDeviceDriver ourselves via reflection
        // to get the UsbCommunication object.
        try {
            // First try: init normally and get from partitions
            try {
                device.init()
                if (device.partitions.isNotEmpty()) {
                    // Extract from partition's ByteBlockDevice.targetBlockDevice
                    val partition = device.partitions[0]
                    val byteBlockClass = partition.javaClass.superclass
                    val targetField = byteBlockClass?.getDeclaredField("targetBlockDevice")
                    if (targetField != null) {
                        targetField.isAccessible = true
                        val driver = targetField.get(partition) as? BlockDeviceDriver
                        if (driver != null) {
                            AppLog.d("Got BlockDeviceDriver from partition: blocks=${driver.blocks}, blockSize=${driver.blockSize}")
                            return driver
                        }
                    }
                }
            } catch (e: Exception) {
                AppLog.d("Normal init failed (expected for superfloppy): ${e.message}")
            }

            // Second try: create BlockDeviceDriver directly from UsbCommunication
            val clazz = device.javaClass
            var usbComm: UsbCommunication? = null
            for (field in clazz.declaredFields) {
                field.isAccessible = true
                val value = field.get(device)
                if (value is UsbCommunication) {
                    usbComm = value
                    AppLog.d("Found UsbCommunication via reflection (field: ${field.name})")
                    break
                }
            }

            if (usbComm == null) {
                // UsbCommunication might not be initialized yet if init() failed early.
                // Try to trigger partial init by calling init() again — it sets up USB comm
                // before failing on partition table.
                try { device.init() } catch (_: Exception) {}
                for (field in clazz.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(device)
                    if (value is UsbCommunication) {
                        usbComm = value
                        AppLog.d("Found UsbCommunication after retry (field: ${field.name})")
                        break
                    }
                }
            }

            if (usbComm != null) {
                val blockDevice = BlockDeviceDriverFactory.createBlockDevice(usbComm, lun = 0.toByte())
                blockDevice.init()
                AppLog.d("Created BlockDeviceDriver: blocks=${blockDevice.blocks}, blockSize=${blockDevice.blockSize}")
                return blockDevice
            }

            AppLog.w("Could not find UsbCommunication via reflection")
        } catch (e: Exception) {
            AppLog.e("Failed to get BlockDeviceDriver", e)
        }
        return null
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
