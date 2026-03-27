package com.gbcsync.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncLogEntry
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

data class ImportChoice(
    val message: String,
    val appendLabel: String = "Append",
    val newLabel: String = "Start New",
    val autoAppendSeconds: Int = 0
)

data class SyncState(
    val status: Status = Status.IDLE,
    val deviceName: String = "",
    val currentFile: String = "",
    val filesCopied: Int = 0,
    val totalFiles: Int = 0,
    val errors: Int = 0,
    val error: String? = null,
    val importChoice: ImportChoice? = null, // non-null = show dialog
    val cameraChoice: List<CameraType>? = null, // non-null = show camera picker
    val targetFolder: String = "",
    val durationMs: Long = 0,
    val safeToDisconnect: Boolean = false
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
        private const val BRIDGE_VENDOR_ID = 9114
        private const val BRIDGE_PRODUCT_ID = 51966
        private const val BRIDGE_BOOT_DELAY_MS = 4000L
        private const val BRIDGE_RECONNECT_DELAY_MS = 2000L
        private const val BRIDGE_FILE_DELAY_MS = 250L
        private val SYNC_FOLDER_REGEX = Regex("sync-\\d{3}")
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val syncLock = Mutex()
    private var importChoiceDeferred: CompletableDeferred<Boolean>? = null // true = continue existing, false = new
    private var cameraChoiceDeferred: CompletableDeferred<CameraType>? = null

    /** Called from UI when user chooses to continue existing import */
    fun onContinueImport() {
        _syncState.value = _syncState.value.copy(importChoice = null)
        importChoiceDeferred?.complete(true)
    }

    /** Called from UI when user chooses to start a new import */
    fun onNewImport() {
        _syncState.value = _syncState.value.copy(importChoice = null)
        importChoiceDeferred?.complete(false)
    }

    /** Called from UI when user picks a camera type */
    fun onCameraChosen(camera: CameraType) {
        _syncState.value = _syncState.value.copy(cameraChoice = null)
        cameraChoiceDeferred?.complete(camera)
    }

    private fun requestPermission(device: UsbDevice) {
        val intent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, intent)
    }

    private fun UsbMassStorageDevice.closeSafely() {
        try { close() } catch (_: Exception) {}
    }

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
                requestPermission(usbDevice)
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
        if (!syncLock.tryLock()) {
            AppLog.d("Sync already in progress, skipping")
            return
        }
        try {
            doSync()
        } finally {
            syncLock.unlock()
        }
    }

    private suspend fun doSync() {
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) return

        val configs = repository.deviceConfigs.first()
        val baseFolder = repository.baseFolder.first()

        for (storageDevice in massStorageDevices) {
            val config = findMatchingConfig(storageDevice.usbDevice, configs) ?: configs.firstOrNull() ?: continue
            val destDir = repository.getDestDir(config, baseFolder)
            val deviceName = config.name
            val usbDev = storageDevice.usbDevice

            if (!usbManager.hasPermission(usbDev)) {
                AppLog.w("USB permission lost, re-requesting...")
                requestPermission(usbDev)
                return
            }

            AppLog.i("=== SYNC START: $deviceName (vendor=${usbDev.vendorId}, product=${usbDev.productId}) ===")

            when {
                config.vendorId == BRIDGE_VENDOR_ID && config.productId == BRIDGE_PRODUCT_ID ->
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

                // Detect camera type
                val hasRomGbc = hasFileOnRoot(fs.rootDirectory, "ROM.GBC")
                val camera = detectOrPickCamera(hasRomGbc)
                AppLog.i("[JoeyJr] Camera: ${camera.displayName}")

                syncWithLibaums(storageDevice, fs, config, destDir, deviceName, camera)
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
        storageDevice.closeSafely()

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

            // Detect camera type via FAT reader
            val hasRomGbc = fatReader.listRootFiles("ROM.GBC") { name, filter ->
                name.equals(filter, ignoreCase = true)
            }.isNotEmpty()
            val camera = detectOrPickCamera(hasRomGbc)
            AppLog.i("[JoeyJr] Camera: ${camera.displayName}")

            syncWithFatReader(fatReader, config, destDir, deviceName, camera)
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
     * The RP2040/TinyUSB connection degrades after sustained SCSI traffic,
     * so we copy files one at a time with delays, and reconnect when errors occur.
     * Tracks successfully copied files across reconnections.
     */
    private suspend fun syncBridge(
        storageDevice: UsbMassStorageDevice,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        // Step 0: Quick scan to list device files for prefix matching
        _syncState.value = SyncState(
            status = SyncState.Status.CONNECTING,
            deviceName = deviceName,
            currentFile = "Scanning device files..."
        )

        val deviceFileIndex = mutableListOf<Triple<String, Long, UsbFile>>() // relativePath, size, usbFile
        try {
            storageDevice.init()
            val partition = storageDevice.partitions.firstOrNull()
            if (partition != null) {
                val fs = partition.fileSystem
                val allFiles = mutableListOf<Pair<UsbFile, String>>()
                collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)
                for ((usbFile, relativePath) in allFiles) {
                    val path = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                    deviceFileIndex.add(Triple(path, usbFile.length, usbFile))
                }
                deviceFileIndex.sortBy { it.first }
                AppLog.i("[Bridge] Device has ${deviceFileIndex.size} files")
            }
            storageDevice.closeSafely()
        } catch (e: Exception) {
            AppLog.w("[Bridge] Quick scan failed: ${e.message}, proceeding with fresh folder")
            storageDevice.closeSafely()
        }

        // Step 1: Find existing sync folder whose files are a prefix of the device files
        val matchingFolder = findMatchingImportFolder(destDir, deviceFileIndex)
        val importDir: File

        if (matchingFolder != null) {
            val existingFiles = matchingFolder.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
            val existingPaths = existingFiles.map { it.relativeTo(matchingFolder).path }.toSet()
            val newOnDevice = deviceFileIndex.count { (path, _, _) -> path !in existingPaths }

            if (newOnDevice == 0) {
                AppLog.i("[Bridge] All ${deviceFileIndex.size} files already in ${matchingFolder.name}, nothing to copy")
                _syncState.value = SyncState(
                    status = SyncState.Status.DONE,
                    deviceName = deviceName,
                    targetFolder = matchingFolder.absolutePath,
                    safeToDisconnect = true
                )
                return
            }

            AppLog.i("[Bridge] Found matching folder: ${matchingFolder.name} (${existingFiles.size} existing, $newOnDevice new)")
            importChoiceDeferred = CompletableDeferred()
            _syncState.value = _syncState.value.copy(
                importChoice = ImportChoice(
                    message = "${existingFiles.size} files already in \"${matchingFolder.name}\", $newOnDevice new to copy",
                    appendLabel = "Append",
                    newLabel = "Start New",
                    autoAppendSeconds = 10
                )
            )
            val append = importChoiceDeferred!!.await()
            if (append) {
                importDir = matchingFolder
                AppLog.i("[Bridge] Appending to: ${importDir.name}")
            } else {
                importDir = createImportFolder(destDir)
            }
        } else {
            importDir = createImportFolder(destDir)
        }

        // Pre-populate with files already in import folder
        val copyStartTime = System.currentTimeMillis()
        val copiedFiles = mutableSetOf<String>()
        importDir.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
            val rel = file.relativeTo(importDir).path
            copiedFiles.add(rel)
        }
        if (copiedFiles.isNotEmpty()) {
            AppLog.i("[Bridge] ${copiedFiles.size} files already in import folder")
        }
        var totalFiles = 0
        var consecutiveInitFailures = 0
        val maxInitFailures = 3
        var currentDevice = storageDevice

        for (round in 1..20) { // max 20 reconnection rounds
            // Step 1: Connect (wait for RP2040 boot on first round, shorter on reconnects)
            val waitMs = if (round == 1) BRIDGE_BOOT_DELAY_MS else BRIDGE_RECONNECT_DELAY_MS
            _syncState.value = SyncState(
                status = SyncState.Status.CONNECTING,
                deviceName = deviceName,
                currentFile = if (round == 1) "Waiting for Bridge to boot..." else "Reconnecting... (round $round)"
            )
            AppLog.i("[Bridge] Round $round: waiting ${waitMs}ms...")
            delay(waitMs)

            // Step 2: Init libaums
            try {
                if (round > 1) {
                    // Get fresh device handle for reconnection
                    val devices = UsbMassStorageDevice.getMassStorageDevices(context)
                    val found = devices.firstOrNull { d ->
                        d.usbDevice.vendorId == BRIDGE_VENDOR_ID && d.usbDevice.productId == BRIDGE_PRODUCT_ID
                    }
                    if (found == null) {
                        AppLog.e("[Bridge] Bridge not found on reconnect")
                        break
                    }
                    currentDevice = found
                    // Check permission (may have been lost on replug)
                    if (!usbManager.hasPermission(currentDevice.usbDevice)) {
                        AppLog.w("[Bridge] Permission lost, re-requesting...")
                        requestPermission(currentDevice.usbDevice)
                        break // will resume via permission callback
                    }
                }
                AppLog.i("[Bridge] Round $round: libaums init...")
                currentDevice.init()
                AppLog.i("[Bridge] Round $round: libaums init OK")
                consecutiveInitFailures = 0
            } catch (e: Exception) {
                AppLog.w("[Bridge] Round $round: init failed: ${e.message}")
                currentDevice.closeSafely()
                consecutiveInitFailures++
                if (consecutiveInitFailures >= maxInitFailures) {
                    AppLog.e("[Bridge] $maxInitFailures consecutive init failures, giving up")
                    break
                }
                continue
            }

            // Step 3: List files and find what still needs copying
            val partition = currentDevice.partitions.firstOrNull()
            if (partition == null) {
                AppLog.w("[Bridge] Round $round: no partitions")
                currentDevice.closeSafely()
                continue
            }

            val fs = partition.fileSystem
            if (round == 1) {
                AppLog.i("[Bridge] Filesystem: ${fs.volumeLabel}, capacity=${fs.capacity}")
            }

            val allFiles = mutableListOf<Pair<UsbFile, String>>()
            collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)

            val newFiles = allFiles.filter { (_, relativePath) ->
                val fileName = if (relativePath.isNotEmpty()) relativePath else return@filter true
                !copiedFiles.contains(fileName) // skip files already copied this session
            }

            if (round == 1) {
                totalFiles = newFiles.size + copiedFiles.size
                AppLog.i("[Bridge] ${newFiles.size} file(s) to copy (${copiedFiles.size} already done)")
            }

            if (newFiles.isEmpty()) {
                AppLog.i("[Bridge] All files copied!")
                currentDevice.closeSafely()
                break
            }

            _syncState.value = SyncState(
                status = SyncState.Status.SYNCING,
                deviceName = deviceName,
                filesCopied = copiedFiles.size,
                totalFiles = totalFiles
            )

            // Step 4: Copy files one at a time, stop on first error and reconnect
            val chunkSize = fs.chunkSize
            var errorInRound = false
            var copiedThisRound = 0
            AppLog.i("[Bridge] Round $round: ${newFiles.size} files remaining, delay=${BRIDGE_FILE_DELAY_MS}ms")

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                val targetPath = originalPath
                _syncState.value = _syncState.value.copy(currentFile = targetPath)

                try {
                    AppLog.d("[Bridge] Copying $targetPath (${usbFile.length} bytes)...")
                    copyLibaumsFile(usbFile, importDir, targetPath, chunkSize, fs, skipClose = true)

                    // Verify file was actually written with content
                    val destFile = File(importDir, targetPath)
                    if (destFile.length() == 0L && usbFile.length > 0) {
                        AppLog.w("[Bridge] $targetPath copied as 0 bytes, connection degraded")
                        destFile.delete()
                        errorInRound = true
                        break
                    }

                    copiedFiles.add(originalPath)
                    copiedThisRound++
                    AppLog.d("[Bridge] Copied $targetPath OK (#$copiedThisRound this round, delay=${BRIDGE_FILE_DELAY_MS}ms)")
                    logSyncEntry(targetPath, deviceName, usbFile.length)
                    _syncState.value = _syncState.value.copy(filesCopied = copiedFiles.size)

                    // Give RP2040 time to recover between files
                    delay(BRIDGE_FILE_DELAY_MS)
                } catch (e: Exception) {
                    AppLog.w("[Bridge] Error copying $targetPath: ${e.message}")
                    // Delete partial file
                    try { File(importDir, targetPath).delete() } catch (_: Exception) {}
                    errorInRound = true
                    break // stop this round, reconnect
                }
            }

            // Clean up connection before reconnecting
            currentDevice.closeSafely()

            if (!errorInRound) {
                AppLog.i("[Bridge] Round $round finished without errors (delay=${BRIDGE_FILE_DELAY_MS}ms)")
                break
            }

            AppLog.i("[Bridge] Round $round: $copiedThisRound copied this round, ${copiedFiles.size}/$totalFiles total, delay=${BRIDGE_FILE_DELAY_MS}ms, reconnecting...")
        }

        // Final status
        val remaining = totalFiles - copiedFiles.size
        val durationMs = System.currentTimeMillis() - copyStartTime
        finishSync(deviceName, copiedFiles.size, totalFiles, remaining, importDir.absolutePath, durationMs)
        if (remaining > 0) {
            AppLog.w("[Bridge] Finished with $remaining file(s) remaining")
        } else {
            AppLog.i("[Bridge] All $totalFiles file(s) synced successfully")
        }
    }

    // --- libaums sync path (FAT32 with partition table) ---

    private suspend fun syncWithLibaums(
        storageDevice: UsbMassStorageDevice,
        fs: FileSystem,
        config: DeviceConfig,
        destDir: File,
        deviceName: String,
        camera: CameraType? = null
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
                _syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName, targetFolder = destDir.absolutePath, safeToDisconnect = true)
                storageDevice.close()
                return
            }

            val copyStartTime = System.currentTimeMillis()
            var copied = 0
            var errors = 0
            val chunkSize = fs.chunkSize

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                val targetPath = formatTargetPath(originalPath, camera?.filePrefix)
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

            val durationMs = System.currentTimeMillis() - copyStartTime
            finishSync(deviceName, copied, newFiles.size, errors, destDir.absolutePath, durationMs)
            storageDevice.close()
        } catch (e: Exception) {
            AppLog.e("Sync error", e)
            _syncState.value = SyncState(status = SyncState.Status.ERROR, error = e.message ?: "Unknown error")
            storageDevice.closeSafely()
        }
    }

    // --- FAT12/16 reader sync path (superfloppy without partition table) ---

    private suspend fun syncWithFatReader(
        fatReader: Fat12Reader,
        config: DeviceConfig,
        destDir: File,
        deviceName: String,
        camera: CameraType? = null
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
            _syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName, targetFolder = destDir.absolutePath, safeToDisconnect = true)
            return
        }

        val copyStartTime = System.currentTimeMillis()
        var copied = 0
        var errors = 0

        for (file in newFiles) {
            val targetPath = formatTargetPath(file.relativePath, camera?.filePrefix)
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

        val durationMs = System.currentTimeMillis() - copyStartTime
        finishSync(deviceName, copied, newFiles.size, errors, destDir.absolutePath, durationMs)
    }

    // --- Shared helpers ---

    /**
     * Find an existing sync-NNN folder whose files are a prefix of the device's file list.
     * Returns the matching folder, or null if no match (triggering creation of a new folder).
     */
    private fun findMatchingImportFolder(
        destDir: File,
        deviceFileIndex: List<Triple<String, Long, UsbFile>>
    ): File? {
        if (deviceFileIndex.isEmpty()) return null

        val devicePaths = deviceFileIndex.map { it.first }.sorted()

        val syncFolders = destDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(SYNC_FOLDER_REGEX) }
            ?.sortedByDescending { it.name }
            ?: return null

        for (folder in syncFolders) {
            val folderPaths = folder.walkTopDown()
                .filter { it.isFile && !it.name.endsWith(".tmp") }
                .map { it.relativeTo(folder).path }
                .sorted()
                .toList()

            if (folderPaths.isEmpty()) continue
            if (folderPaths.size > devicePaths.size) continue

            val isPrefix = folderPaths.indices.all { i ->
                folderPaths[i] == devicePaths[i]
            }

            if (isPrefix) return folder
        }
        return null
    }

    private fun createImportFolder(destDir: File): File {
        val nextNumber = (destDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(SYNC_FOLDER_REGEX) }
            ?.mapNotNull { it.name.removePrefix("sync-").toIntOrNull() }
            ?.maxOrNull() ?: 0) + 1
        val dir = File(destDir, "sync-%03d".format(nextNumber))
        dir.mkdirs()
        AppLog.i("[Bridge] New import folder: ${dir.name}")
        return dir
    }

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

    private fun hasFileOnRoot(rootDir: UsbFile, fileName: String): Boolean {
        return try {
            rootDir.listFiles().any { it.name.equals(fileName, ignoreCase = true) }
        } catch (e: Exception) {
            AppLog.w("Error checking for $fileName on root: ${e.message}")
            false
        }
    }

    /**
     * Detects camera type or shows picker dialog.
     * If ROM.GBC is present → MiniCam PhotoRom (auto-detected).
     * Otherwise → show picker with owned cameras (excluding auto-detected ones).
     */
    /** Look up owned version of a camera (may have custom prefix) or fall back to default */
    private suspend fun ownedOrDefault(default: CameraType): CameraType {
        val owned = repository.ownedCameras.first()
        return owned.find { it.displayName == default.displayName } ?: default
    }

    private suspend fun detectOrPickCamera(hasRomGbc: Boolean): CameraType {
        if (hasRomGbc) {
            AppLog.i("ROM.GBC detected → MiniCam (PhotoRom)")
            return ownedOrDefault(CameraType.MINI_CAM_PHOTO_ROM)
        }

        val owned = repository.ownedCameras.first()
        val pickerCameras = repository.pickerCameras(owned)

        if (pickerCameras.size == 1) {
            AppLog.i("Only one camera configured: ${pickerCameras[0].displayName}")
            return pickerCameras[0]
        }

        if (pickerCameras.isEmpty()) {
            AppLog.w("No cameras configured, defaulting to GB Camera (Green)")
            return CameraType.GB_CAMERA_GREEN
        }

        // Show picker dialog and wait for user choice
        cameraChoiceDeferred = CompletableDeferred()
        _syncState.value = _syncState.value.copy(cameraChoice = pickerCameras)
        val chosen = cameraChoiceDeferred!!.await()
        return chosen
    }

    private fun finishSync(deviceName: String, copied: Int, total: Int, errors: Int, targetFolder: String = "", durationMs: Long = 0) {
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
            error = if (errors > 0) "$errors file(s) failed to copy" else null,
            targetFolder = targetFolder,
            durationMs = durationMs,
            safeToDisconnect = true
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

    /**
     * Builds the target path for a synced file.
     * Pattern: <infix>/<datetime>-<infix>.<ext>
     * e.g. "grn/2026-03-05_112233-grn.sav"
     * If no infix, returns the original path unchanged.
     */
    private fun formatTargetPath(originalPath: String, infix: String?): String {
        if (infix == null) return originalPath
        val fileName = originalPath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val newName = if (ext.isNotEmpty()) "$timestamp-$infix.$ext" else "$timestamp-$infix"
        return "$infix/$newName"
    }

    /**
     * @param skipClose If true, skips closing the libaums input stream to avoid
     *   FatFile.flush() sending SCSI WRITE commands that corrupt RP2040 USB state.
     *   Only needed for Bridge; JoeyJr can close normally.
     */
    private fun copyLibaumsFile(usbFile: UsbFile, destDir: File, relativePath: String, chunkSize: Int, fs: FileSystem, skipClose: Boolean = false) {
        val destFile = File(destDir, relativePath)
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")

        FileOutputStream(tmpFile).use { fos ->
            fos.channel.truncate(0)
            val buffer = ByteArray(chunkSize)
            val inputStream = UsbFileStreamFactory.createBufferedInputStream(usbFile, fs)
            try {
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            } finally {
                if (!skipClose) inputStream.close()
            }
        }

        if (tmpFile.length() != usbFile.length) {
            AppLog.w("Size mismatch for $relativePath: expected ${usbFile.length}, got ${tmpFile.length()}, deleting partial file")
            tmpFile.delete()
            throw java.io.IOException("Size mismatch for $relativePath: expected ${usbFile.length}, got ${tmpFile.length()}")
        }

        tmpFile.renameTo(destFile)
    }

    private fun copyFat32LibFile(file: FatFsFile, destDir: File, relativePath: String) {
        val destFile = File(destDir, relativePath)
        destFile.parentFile?.mkdirs()
        val tmpFile = File(destFile.parentFile, destFile.name + ".tmp")

        FileOutputStream(tmpFile).use { fos ->
            val buffer = ByteArray(8192)
            file.readContents().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
        }

        if (tmpFile.length() != file.length) {
            AppLog.w("Size mismatch for $relativePath: expected ${file.length}, got ${tmpFile.length()}, deleting partial file")
            tmpFile.delete()
            throw java.io.IOException("Size mismatch for $relativePath: expected ${file.length}, got ${tmpFile.length()}")
        }

        tmpFile.renameTo(destFile)
    }
}
