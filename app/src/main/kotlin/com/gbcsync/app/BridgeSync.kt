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
import kotlinx.coroutines.flow.first
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File

class BridgeSync(
    private val context: Context,
    private val usbManager: UsbManager,
    private val repository: SyncRepository,
    private val fileCopier: FileCopier,
    private val syncState: MutableStateFlow<SyncState>,
    private val requestPermission: (UsbDevice) -> Unit,
) {
    companion object {
        const val VENDOR_ID = 9114
        const val PRODUCT_ID = 51966
        private const val BOOT_DELAY_MS = 4000L
        private const val RECONNECT_DELAY_MS = 2000L
        private const val RECONNECT_DELAY_MAX_MS = 10000L
        private const val FILE_DELAY_MS = 250L
        private val SYNC_FOLDER_REGEX = Regex("sync-\\d{3}")
    }

    var importChoiceDeferred: CompletableDeferred<Boolean?>? = null

    /**
     * PicNRec/2bitBridge sync flow.
     * The RP2040/TinyUSB connection degrades after sustained SCSI traffic,
     * so we copy files one at a time with delays, and reconnect when errors occur.
     */
    suspend fun sync(
        storageDevice: UsbMassStorageDevice,
        config: DeviceConfig,
        destDir: File,
        deviceName: String,
    ) {
        syncState.value =
            SyncState(
                status = SyncState.Status.CONNECTING,
                deviceName = deviceName,
                currentFile = "Waiting for Bridge to boot...",
            )

        // Load persisted set of previously synced files (survives app restarts and file deletions)
        var previouslySynced = repository.getSyncedFiles(deviceName)
        val newlySynced = mutableSetOf<String>()

        // Merge on-disk files into sync history (catches files from before history tracking)
        val filterExt = config.fileFilter.removePrefix("*.").lowercase()
        val onDiskFiles = mutableSetOf<String>()
        destDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(SYNC_FOLDER_REGEX) }
            ?.forEach { syncFolder ->
                syncFolder.walkTopDown()
                    .filter { it.isFile && it.extension.lowercase() == filterExt }
                    .forEach { file ->
                        onDiskFiles.add(file.relativeTo(syncFolder).path)
                    }
            }
        val missingFromHistory = onDiskFiles - previouslySynced
        if (missingFromHistory.isNotEmpty()) {
            repository.addSyncedFiles(deviceName, missingFromHistory)
            previouslySynced = previouslySynced + missingFromHistory
            AppLog.i("[Bridge] Added ${missingFromHistory.size} on-disk files to sync history (total: ${previouslySynced.size})")
        }

        AppLog.i("[Bridge] ${previouslySynced.size} files in sync history")

        // Import dir is resolved after first successful init (quick scan + folder matching).
        // We avoid a separate init for the quick scan because double-init breaks RP2040/TinyUSB.
        var importDir: File? = null
        val copyStartTime = System.currentTimeMillis()
        val copiedFiles = mutableSetOf<String>()
        var totalFiles = 0
        var consecutiveInitFailures = 0
        val maxInitFailures = 3
        var currentDevice = storageDevice
        // Device relative paths still to copy after the first walk; drives targeted
        // re-resolution on reconnect so we don't re-walk the whole tree each round.
        var remainingPaths: List<String> = emptyList()
        var noProgressStreak = 0
        val maxNoProgress = 3

        for (round in 1..20) {
            val waitMs =
                if (round == 1) {
                    BOOT_DELAY_MS
                } else {
                    (RECONNECT_DELAY_MS * (noProgressStreak + 1)).coerceAtMost(RECONNECT_DELAY_MAX_MS)
                }
            syncState.value =
                SyncState(
                    status = SyncState.Status.CONNECTING,
                    deviceName = deviceName,
                    currentFile = if (round == 1) "Waiting for Bridge to boot..." else "Reconnecting... (round $round)",
                )
            AppLog.i("[Bridge] Round $round: waiting ${waitMs}ms...")
            delay(waitMs)

            try {
                if (round > 1) {
                    val devices = UsbMassStorageDevice.getMassStorageDevices(context)
                    val found =
                        devices.firstOrNull { d ->
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

            // On first successful init: full walk to resolve the import dir and build the
            // work list. On reconnect rounds: re-resolve only the still-remaining files by
            // path, so we don't re-walk the whole tree (less SCSI traffic on the fragile link).
            val roundFiles: List<Pair<UsbFile, String>>
            if (importDir == null) {
                val allFiles = mutableListOf<Pair<UsbFile, String>>()
                fileCopier.collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)

                val deviceFileIndex = allFiles.map { (usbFile, relativePath) ->
                    val path = flattenPath(if (relativePath.isNotEmpty()) relativePath else usbFile.name)
                    Triple(path, usbFile.length, usbFile)
                }.sortedBy { it.first }
                AppLog.i("[Bridge] Device has ${deviceFileIndex.size} files")

                val matchingFolder = findMatchingImportFolder(destDir, deviceFileIndex, config.fileFilter)
                if (matchingFolder != null) {
                    val existingFiles = matchingFolder.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
                    val existingPaths = existingFiles.map { it.relativeTo(matchingFolder).path }.toSet()
                    val knownPaths = existingPaths + previouslySynced
                    val newOnDevice = deviceFileIndex.count { (path, _, _) -> path !in knownPaths }

                    if (newOnDevice == 0) {
                        AppLog.i("[Bridge] All ${deviceFileIndex.size} files already synced (${existingPaths.size} on disk, ${previouslySynced.size} in history)")
                        currentDevice.closeSafely()
                        syncState.value =
                            SyncState(
                                status = SyncState.Status.DONE,
                                deviceName = deviceName,
                                targetFolder = matchingFolder.absolutePath,
                                safeToDisconnect = true,
                            )
                        return
                    }

                    AppLog.i("[Bridge] Found matching folder: ${matchingFolder.name} (${existingPaths.size} on disk, ${previouslySynced.size} in history, $newOnDevice new)")
                    importChoiceDeferred = CompletableDeferred()
                    syncState.value =
                        syncState.value.copy(
                            importChoice =
                                ImportChoice(
                                    message = "$newOnDevice new file${if (newOnDevice != 1) "s" else ""} to copy",
                                    appendLabel = "Append",
                                    newLabel = "Start New",
                                    autoAppendSeconds = 10,
                                ),
                        )
                    val append = importChoiceDeferred!!.await()
                    if (append == null) {
                        AppLog.i("[Bridge] Import cancelled by user")
                        currentDevice.closeSafely()
                        syncState.value = SyncState(status = SyncState.Status.IDLE)
                        return
                    } else if (append) {
                        importDir = matchingFolder
                        AppLog.i("[Bridge] Appending to: ${importDir!!.name}")
                    } else {
                        importDir = createImportFolder(destDir, deviceName)
                    }
                } else {
                    importDir = createImportFolder(destDir, deviceName)
                }

                // Pre-populate with files already in import folder
                importDir!!.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                    copiedFiles.add(file.relativeTo(importDir!!).path)
                }
                if (copiedFiles.isNotEmpty()) {
                    AppLog.i("[Bridge] ${copiedFiles.size} files already in import folder")
                }

                totalFiles = allFiles.size
                // remainingPaths keeps the device path (needed to re-search on reconnect);
                // dedup compares by the flattened key.
                remainingPaths =
                    allFiles
                        .map { it.second }
                        .filter { flattenPath(it) !in copiedFiles && flattenPath(it) !in previouslySynced }
                roundFiles = allFiles
            } else {
                var resolved = resolveRemaining(fs, remainingPaths)
                if (resolved.isEmpty() && remainingPaths.isNotEmpty()) {
                    AppLog.w("[Bridge] Targeted resolve found no files; falling back to full walk")
                    val allFiles = mutableListOf<Pair<UsbFile, String>>()
                    fileCopier.collectLibaumsFiles(fs.rootDirectory, "", config.fileFilter, config.recursive, allFiles)
                    resolved = allFiles
                }
                AppLog.i("[Bridge] Round $round: re-resolved ${resolved.size} of ${remainingPaths.size} remaining file(s)")
                roundFiles = resolved
            }

            val newFiles =
                roundFiles.filter { (usbFile, relativePath) ->
                    val devicePath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                    val key = flattenPath(devicePath)
                    key !in copiedFiles && key !in previouslySynced
                }

            AppLog.i("[Bridge] ${newFiles.size} file(s) to copy (${copiedFiles.size} on disk, ${previouslySynced.size} in history, $totalFiles total)")

            if (newFiles.isEmpty()) {
                AppLog.i("[Bridge] All files copied!")
                currentDevice.closeSafely()
                break
            }

            syncState.value =
                SyncState(
                    status = SyncState.Status.SYNCING,
                    deviceName = deviceName,
                    filesCopied = newlySynced.size,
                    totalFiles = newFiles.size + newlySynced.size,
                )

            val chunkSize = fs.chunkSize
            var errorInRound = false
            var copiedThisRound = 0
            AppLog.i("[Bridge] Round $round: ${newFiles.size} files remaining, delay=${FILE_DELAY_MS}ms")

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                // Flatten into the single import folder; this is also the dedup key.
                val targetPath = flattenPath(originalPath)
                syncState.value = syncState.value.copy(currentFile = targetPath)

                try {
                    AppLog.d("[Bridge] Copying $targetPath (${usbFile.length} bytes)...")
                    fileCopier.copyLibaumsFile(usbFile, importDir!!, targetPath, chunkSize, fs)

                    val destFile = File(importDir!!, targetPath)
                    if (destFile.length() == 0L && usbFile.length > 0) {
                        AppLog.w("[Bridge] $targetPath copied as 0 bytes, connection degraded")
                        destFile.delete()
                        errorInRound = true
                        break
                    }

                    copiedFiles.add(targetPath)
                    newlySynced.add(targetPath)
                    copiedThisRound++
                    AppLog.d("[Bridge] Copied $targetPath OK (#$copiedThisRound this round, delay=${FILE_DELAY_MS}ms)")
                    syncState.value = syncState.value.copy(filesCopied = newlySynced.size)

                    delay(FILE_DELAY_MS)
                } catch (e: Exception) {
                    AppLog.w("[Bridge] Error copying $targetPath: ${e.message}")
                    try {
                        File(importDir!!, targetPath).delete()
                    } catch (_: Exception) {
                    }
                    errorInRound = true
                    break
                }
            }

            currentDevice.closeSafely()

            // Persist progress each productive round so an interrupted sync doesn't
            // re-evaluate already-copied files on the next run.
            if (copiedThisRound > 0 && newlySynced.isNotEmpty()) {
                repository.addSyncedFiles(deviceName, newlySynced)
            }

            // Shrink the remaining work so each reconnect round does strictly less.
            remainingPaths = remainingPaths.filter { flattenPath(it) !in copiedFiles }

            if (!errorInRound) {
                AppLog.i("[Bridge] Round $round finished without errors (delay=${FILE_DELAY_MS}ms)")
                break
            }

            if (copiedThisRound > 0) {
                noProgressStreak = 0
            } else {
                noProgressStreak++
                if (noProgressStreak >= maxNoProgress) {
                    AppLog.e("[Bridge] $maxNoProgress consecutive rounds with no progress, giving up")
                    break
                }
            }

            AppLog.i(
                "[Bridge] Round $round: $copiedThisRound copied this round, ${copiedFiles.size}/$totalFiles total, delay=${FILE_DELAY_MS}ms, reconnecting...",
            )
        }

        // Persist newly synced file paths for cross-session dedup
        if (newlySynced.isNotEmpty()) {
            repository.addSyncedFiles(deviceName, newlySynced)
            AppLog.i("[Bridge] Persisted ${newlySynced.size} new file(s) to sync history")
        }

        val durationMs = System.currentTimeMillis() - copyStartTime
        val copied = newlySynced.size
        val skipped = previouslySynced.size
        val errors = totalFiles - copiedFiles.size - skipped
        finishSync(syncState, repository, deviceName, copied, totalFiles, errors.coerceAtLeast(0), importDir?.absolutePath ?: "", durationMs)
        if (errors > 0) {
            AppLog.w("[Bridge] Finished with $errors file(s) remaining")
        } else {
            AppLog.i("[Bridge] Sync complete: $copied new, $skipped from history, ${copiedFiles.size} on disk")
        }
    }

    /**
     * Flattens a device relative path into a single-folder filename, so imports never
     * recreate the device's nested subfolders on disk. Deterministic (unlike a counter,
     * which depends on enumeration order) and also the canonical dedup key everywhere
     * (history, copiedFiles). Flat files (no separator) are returned unchanged, keeping
     * pre-existing history and on-disk files compatible.
     */
    private fun flattenPath(path: String): String = path.replace('/', '_')

    /**
     * Re-resolve only the still-remaining files by path after a reconnect, instead of
     * re-walking the entire directory tree. Avoids re-reading directory entries for
     * already-synced files and shrinks per-round SCSI traffic on the fragile link.
     */
    private fun resolveRemaining(
        fs: FileSystem,
        paths: List<String>,
    ): List<Pair<UsbFile, String>> {
        val root = fs.rootDirectory
        val result = mutableListOf<Pair<UsbFile, String>>()
        for (path in paths) {
            try {
                val file = root.search(path)
                if (file != null && !file.isDirectory) {
                    result.add(file to path)
                }
            } catch (e: Exception) {
                AppLog.w("[Bridge] Failed to resolve $path on reconnect: ${e.message}")
            }
        }
        return result
    }

    private fun findMatchingImportFolder(
        destDir: File,
        deviceFileIndex: List<Triple<String, Long, UsbFile>>,
        fileFilter: String,
    ): File? {
        if (deviceFileIndex.isEmpty()) return null

        val devicePaths = deviceFileIndex.map { it.first }.toSet()

        val syncFolders =
            destDir
                .listFiles()
                ?.filter { it.isDirectory && it.name.matches(SYNC_FOLDER_REGEX) }
                ?.sortedByDescending { it.name }
                ?: return null

        // Only compare files matching the sync filter (ignore GIFs, etc.)
        val filterExt = fileFilter.removePrefix("*.").lowercase()

        for (folder in syncFolders) {
            val folderPaths =
                folder
                    .walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".tmp") && it.extension.lowercase() == filterExt }
                    .map { it.relativeTo(folder).path }
                    .toSet()

            if (folderPaths.isEmpty()) continue
            if (folderPaths.size > devicePaths.size) continue

            // Check that all synced files in the folder are a subset of the device files
            if (devicePaths.containsAll(folderPaths)) return folder
        }
        return null
    }

    private suspend fun createImportFolder(destDir: File, deviceName: String): File {
        val configuredNumber = repository.nextSyncNumber(deviceName).first()
        val nextNumber = if (configuredNumber > 0) {
            // Use configured number and clear it (one-shot)
            repository.setNextSyncNumber(deviceName, 0)
            configuredNumber
        } else {
            (
                destDir
                    .listFiles()
                    ?.filter { it.isDirectory && it.name.matches(SYNC_FOLDER_REGEX) }
                    ?.mapNotNull { it.name.removePrefix("sync-").toIntOrNull() }
                    ?.maxOrNull() ?: 0
            ) + 1
        }
        val dir = File(destDir, "sync-%03d".format(nextNumber))
        dir.mkdirs()
        AppLog.i("[Bridge] New import folder: ${dir.name}")
        return dir
    }
}
