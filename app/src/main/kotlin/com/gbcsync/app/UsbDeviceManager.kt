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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
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
        private const val TAG = "UsbDeviceManager"
        const val ACTION_USB_PERMISSION = "com.gbcsync.app.USB_PERMISSION"
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
        } catch (_: IllegalArgumentException) {
            // Already unregistered
        }
    }

    fun onUsbDeviceAttached() {
        AppLog.i("USB device attached event received")
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
                return // Will continue in receiver callback
            }

            startSync()
            return
        }
    }

    private fun findMatchingConfig(usbDevice: UsbDevice, configs: List<DeviceConfig>): DeviceConfig? {
        // First try exact vendor/product ID match
        for (config in configs) {
            if (config.vendorId != 0 && config.productId != 0 &&
                config.vendorId == usbDevice.vendorId && config.productId == usbDevice.productId) {
                return config
            }
        }
        // If no exact match, use first config with unset IDs (0/0) as fallback
        return configs.firstOrNull { it.vendorId == 0 && it.productId == 0 }
    }

    private suspend fun startSync() {
        val massStorageDevices = UsbMassStorageDevice.getMassStorageDevices(context)
        if (massStorageDevices.isEmpty()) return

        val configs = repository.deviceConfigs.first()

        for (storageDevice in massStorageDevices) {
            try {
                AppLog.d("Initializing storage device...")
                storageDevice.init()

                val config = findMatchingConfig(storageDevice.usbDevice, configs) ?: configs.firstOrNull() ?: continue
                val destDir = repository.getDestDir(config)
                val deviceName = config.name
                AppLog.i("Syncing $deviceName -> ${destDir.absolutePath}")

                _syncState.value = SyncState(
                    status = SyncState.Status.SYNCING,
                    deviceName = deviceName
                )

                val partition = storageDevice.partitions.firstOrNull() ?: continue
                val fs = partition.fileSystem
                val root = fs.rootDirectory

                // Collect files to copy
                AppLog.d("Scanning files (filter=${config.fileFilter}, recursive=${config.recursive})...")
                val filesToCopy = mutableListOf<Pair<UsbFile, String>>()
                collectFiles(root, "", config.fileFilter, config.recursive, filesToCopy)
                AppLog.d("Found ${filesToCopy.size} matching file(s) on device")

                // Filter out already-synced files (.sav files always copy with timestamp)
                val newFiles = filesToCopy.filter { (usbFile, relativePath) ->
                    val fileName = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                    if (fileName.lowercase().endsWith(".sav")) true
                    else repository.shouldCopyFile(fileName, usbFile.length, destDir)
                }
                AppLog.i("${newFiles.size} new file(s) to copy (${filesToCopy.size - newFiles.size} already synced)")

                _syncState.value = _syncState.value.copy(totalFiles = newFiles.size)

                if (newFiles.isEmpty()) {
                    _syncState.value = SyncState(
                        status = SyncState.Status.DONE,
                        deviceName = deviceName,
                        filesCopied = 0,
                        totalFiles = 0
                    )
                    storageDevice.close()
                    continue
                }

                var copied = 0
                val chunkSize = fs.chunkSize

                for ((usbFile, relativePath) in newFiles) {
                    val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                    val targetPath = addTimestampIfSav(originalPath)
                    _syncState.value = _syncState.value.copy(currentFile = targetPath)

                    try {
                        AppLog.d("Copying $targetPath (${usbFile.length} bytes)...")
                        copyFile(usbFile, destDir, targetPath, chunkSize, fs)
                        copied++
                        AppLog.d("Copied $targetPath OK")

                        repository.addSyncLogEntry(
                            SyncLogEntry(
                                fileName = targetPath,
                                deviceName = deviceName,
                                timestamp = System.currentTimeMillis(),
                                fileSize = usbFile.length
                            )
                        )

                        _syncState.value = _syncState.value.copy(filesCopied = copied)
                    } catch (e: Exception) {
                        AppLog.e("Error copying $targetPath", e)
                    }
                }

                AppLog.i("Sync complete: $copied/${newFiles.size} file(s) copied")
                _syncState.value = SyncState(
                    status = SyncState.Status.DONE,
                    deviceName = deviceName,
                    filesCopied = copied,
                    totalFiles = newFiles.size
                )

                storageDevice.close()

            } catch (e: Exception) {
                AppLog.e("Sync error", e)
                _syncState.value = SyncState(
                    status = SyncState.Status.ERROR,
                    error = e.message ?: "Unknown error"
                )
                try { storageDevice.close() } catch (_: Exception) {}
            }
        }
    }

    private fun collectFiles(
        dir: UsbFile,
        pathPrefix: String,
        filter: String,
        recursive: Boolean,
        result: MutableList<Pair<UsbFile, String>>
    ) {
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                if (recursive) {
                    val subPath = if (pathPrefix.isEmpty()) file.name else "$pathPrefix/${file.name}"
                    collectFiles(file, subPath, filter, recursive, result)
                }
            } else {
                if (repository.matchesFilter(file.name, filter)) {
                    val relativePath = if (pathPrefix.isEmpty()) file.name else "$pathPrefix/${file.name}"
                    result.add(file to relativePath)
                }
            }
        }
    }

    private fun addTimestampIfSav(path: String): String {
        if (!path.lowercase().endsWith(".sav")) return path
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val dot = path.lastIndexOf('.')
        return "${path.substring(0, dot)}_$timestamp${path.substring(dot)}"
    }

    private fun copyFile(usbFile: UsbFile, destDir: File, relativePath: String, chunkSize: Int, fs: me.jahnen.libaums.core.fs.FileSystem) {
        val destFile = File(destDir, relativePath)
        destFile.parentFile?.mkdirs()

        FileOutputStream(destFile).use { fos ->
            // Pre-allocate file size for performance
            fos.channel.truncate(0)

            val buffer = ByteArray(chunkSize)
            val inputStream = me.jahnen.libaums.core.fs.UsbFileStreamFactory.createBufferedInputStream(usbFile, fs)
            inputStream.use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    fos.write(buffer, 0, bytesRead)
                }
            }
        }

        // Preserve file length
        if (destFile.length() != usbFile.length) {
            AppLog.w("Size mismatch for $relativePath: expected ${usbFile.length}, got ${destFile.length()}")
        }
    }
}
