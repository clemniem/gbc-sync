package com.gbcsync.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File

class BridgeSync(
    private val context: Context,
    private val usbManager: UsbManager,
    private val repository: SyncRepository,
    private val fileCopier: FileCopier,
    private val syncState: MutableStateFlow<SyncState>,
    private val requestPermission: (UsbDevice) -> Unit
) {
    companion object {
        const val VENDOR_ID = 9114
        const val PRODUCT_ID = 51966
        private const val BOOT_DELAY_MS = 4000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val FILE_DELAY_MS = 250L
        private val SYNC_FOLDER_REGEX = Regex("sync-\\d{3}")
    }

    var importChoiceDeferred: CompletableDeferred<Boolean>? = null

    /**
     * PicNRec/2bitBridge sync flow.
     * The RP2040/TinyUSB connection degrades after sustained SCSI traffic,
     * so we copy files one at a time with delays, and reconnect when errors occur.
     */
    suspend fun sync(
        storageDevice: UsbMassStorageDevice,
        config: DeviceConfig,
        destDir: File,
        deviceName: String
    ) {
        // Step 0: Quick scan to list device files for prefix matching
        syncState.value = SyncState(
            status = SyncState.Status.CONNECTING,
            deviceName = deviceName,
            currentFile = "Scanning device files..."
        )

        val deviceFileIndex = mutableListOf<Triple<String, Long, UsbFile>>()
        try {
            storageDevice.init()
            val partition = storageDevice.partitions.firstOrNull()
            if (partition != null) {
                val fs = partition.fileSystem
                val allFiles = mutableListOf<Pair<UsbFile, String>>()
                fileCopier.collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)
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
                syncState.value = SyncState(
                    status = SyncState.Status.DONE,
                    deviceName = deviceName,
                    targetFolder = matchingFolder.absolutePath,
                    safeToDisconnect = true
                )
                return
            }

            AppLog.i("[Bridge] Found matching folder: ${matchingFolder.name} (${existingFiles.size} existing, $newOnDevice new)")
            importChoiceDeferred = CompletableDeferred()
            syncState.value = syncState.value.copy(
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

        for (round in 1..20) {
            val waitMs = if (round == 1) BOOT_DELAY_MS else RECONNECT_DELAY_MS
            syncState.value = SyncState(
                status = SyncState.Status.CONNECTING,
                deviceName = deviceName,
                currentFile = if (round == 1) "Waiting for Bridge to boot..." else "Reconnecting... (round $round)"
            )
            AppLog.i("[Bridge] Round $round: waiting ${waitMs}ms...")
            delay(waitMs)

            try {
                if (round > 1) {
                    val devices = UsbMassStorageDevice.getMassStorageDevices(context)
                    val found = devices.firstOrNull { d ->
                        d.usbDevice.vendorId == VENDOR_ID && d.usbDevice.productId == PRODUCT_ID
                    }
                    if (found == null) {
                        AppLog.e("[Bridge] Bridge not found on reconnect")
                        break
                    }
                    currentDevice = found
                    if (!usbManager.hasPermission(currentDevice.usbDevice)) {
                        AppLog.w("[Bridge] Permission lost, re-requesting...")
                        requestPermission(currentDevice.usbDevice)
                        break
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
            fileCopier.collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)

            val newFiles = allFiles.filter { (_, relativePath) ->
                val fileName = if (relativePath.isNotEmpty()) relativePath else return@filter true
                !copiedFiles.contains(fileName)
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

            syncState.value = SyncState(
                status = SyncState.Status.SYNCING,
                deviceName = deviceName,
                filesCopied = copiedFiles.size,
                totalFiles = totalFiles
            )

            val chunkSize = fs.chunkSize
            var errorInRound = false
            var copiedThisRound = 0
            AppLog.i("[Bridge] Round $round: ${newFiles.size} files remaining, delay=${FILE_DELAY_MS}ms")

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                val targetPath = originalPath
                syncState.value = syncState.value.copy(currentFile = targetPath)

                try {
                    AppLog.d("[Bridge] Copying $targetPath (${usbFile.length} bytes)...")
                    fileCopier.copyLibaumsFile(usbFile, importDir, targetPath, chunkSize, fs, skipClose = true)

                    val destFile = File(importDir, targetPath)
                    if (destFile.length() == 0L && usbFile.length > 0) {
                        AppLog.w("[Bridge] $targetPath copied as 0 bytes, connection degraded")
                        destFile.delete()
                        errorInRound = true
                        break
                    }

                    copiedFiles.add(originalPath)
                    copiedThisRound++
                    AppLog.d("[Bridge] Copied $targetPath OK (#$copiedThisRound this round, delay=${FILE_DELAY_MS}ms)")
                    syncState.value = syncState.value.copy(filesCopied = copiedFiles.size)

                    delay(FILE_DELAY_MS)
                } catch (e: Exception) {
                    AppLog.w("[Bridge] Error copying $targetPath: ${e.message}")
                    try { File(importDir, targetPath).delete() } catch (_: Exception) {}
                    errorInRound = true
                    break
                }
            }

            currentDevice.closeSafely()

            if (!errorInRound) {
                AppLog.i("[Bridge] Round $round finished without errors (delay=${FILE_DELAY_MS}ms)")
                break
            }

            AppLog.i("[Bridge] Round $round: $copiedThisRound copied this round, ${copiedFiles.size}/$totalFiles total, delay=${FILE_DELAY_MS}ms, reconnecting...")
        }

        val remaining = totalFiles - copiedFiles.size
        val durationMs = System.currentTimeMillis() - copyStartTime
        finishSync(syncState, repository, deviceName, copiedFiles.size, totalFiles, remaining, importDir.absolutePath, durationMs)
        if (remaining > 0) {
            AppLog.w("[Bridge] Finished with $remaining file(s) remaining")
        } else {
            AppLog.i("[Bridge] All $totalFiles file(s) synced successfully")
        }
    }

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
}
