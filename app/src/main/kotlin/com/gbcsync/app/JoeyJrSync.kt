package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.CameraType
import com.gbcsync.app.data.DeviceConfig
import com.gbcsync.app.data.SyncRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import me.jahnen.libaums.core.UsbMassStorageDevice
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import java.io.File

class JoeyJrSync(
    private val repository: SyncRepository,
    private val fileCopier: FileCopier,
    private val blockDeviceFactory: BlockDeviceFactory,
    private val syncState: MutableStateFlow<SyncState>
) {
    var cameraChoiceDeferred: CompletableDeferred<CameraType>? = null

    /**
     * JoeyJr sync flow (vendor=49745, product=8224).
     * Tries libaums first, falls back to raw SCSI with a fresh connection.
     */
    suspend fun sync(
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

        val freshDevice = blockDeviceFactory.getRawBlockDeviceFresh(storageDevice.usbDevice)
        if (freshDevice == null) {
            AppLog.e("[JoeyJr] Cannot access block device via raw SCSI")
            syncState.value = SyncState(
                status = SyncState.Status.ERROR,
                error = "Cannot access USB device. Try unplugging and re-plugging."
            )
            return
        }

        try {
            val partitionOffset = blockDeviceFactory.findPartitionOffset(freshDevice)
            AppLog.d("[JoeyJr] Partition offset: $partitionOffset")
            val fatReader = Fat12Reader(freshDevice, partitionOffset)

            val hasRomGbc = fatReader.listRootFiles("ROM.GBC") { name, filter ->
                name.equals(filter, ignoreCase = true)
            }.isNotEmpty()
            val camera = detectOrPickCamera(hasRomGbc)
            AppLog.i("[JoeyJr] Camera: ${camera.displayName}")

            syncWithFatReader(fatReader, config, destDir, deviceName, camera)
            AppLog.i("[JoeyJr] Sync complete via raw SCSI")
        } catch (e: Exception) {
            AppLog.e("[JoeyJr] FAT reader sync error", e)
            syncState.value = SyncState(
                status = SyncState.Status.ERROR, error = e.message ?: "Unknown error"
            )
        }
    }

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
            syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName, currentFile = "Scanning files...")

            val root = fs.rootDirectory
            AppLog.d("chunkSize=${fs.chunkSize}")

            AppLog.d("Scanning files (filter=${config.fileFilter}, recursive=${config.recursive})...")
            val filesToCopy = mutableListOf<Pair<UsbFile, String>>()
            fileCopier.collectLibaumsFiles(root, "", config.fileFilter, config.recursive, filesToCopy)
            AppLog.d("Found ${filesToCopy.size} matching file(s) on device")

            val newFiles = filesToCopy.filter { (usbFile, relativePath) ->
                val fileName = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                if (fileName.lowercase().endsWith(".sav")) true
                else repository.shouldCopyFile(fileName, usbFile.length, destDir)
            }
            AppLog.i("${newFiles.size} new file(s) to copy (${filesToCopy.size - newFiles.size} already synced)")

            syncState.value = syncState.value.copy(totalFiles = newFiles.size)

            if (newFiles.isEmpty()) {
                AppLog.i("All files up to date")
                syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName, targetFolder = destDir.absolutePath, safeToDisconnect = true)
                return
            }

            val copyStartTime = System.currentTimeMillis()
            var copied = 0
            var errors = 0
            val chunkSize = fs.chunkSize

            for ((usbFile, relativePath) in newFiles) {
                val originalPath = if (relativePath.isNotEmpty()) relativePath else usbFile.name
                val targetPath = fileCopier.formatTargetPath(originalPath, camera?.filePrefix)
                syncState.value = syncState.value.copy(currentFile = targetPath)

                try {
                    AppLog.d("Copying $targetPath (${usbFile.length} bytes)...")
                    fileCopier.copyLibaumsFile(usbFile, destDir, targetPath, chunkSize, fs)
                    copied++
                    AppLog.d("Copied $targetPath OK")
                    syncState.value = syncState.value.copy(filesCopied = copied)
                } catch (e: Exception) {
                    errors++
                    AppLog.e("Error copying $targetPath", e)
                    syncState.value = syncState.value.copy(errors = errors)
                }
            }

            val durationMs = System.currentTimeMillis() - copyStartTime
            finishSync(syncState, repository, deviceName, copied, newFiles.size, errors, destDir.absolutePath, durationMs)
        } catch (e: Exception) {
            AppLog.e("Sync error", e)
            syncState.value = SyncState(status = SyncState.Status.ERROR, error = e.message ?: "Unknown error")
        } finally {
            storageDevice.closeSafely()
        }
    }

    private suspend fun syncWithFatReader(
        fatReader: Fat12Reader,
        config: DeviceConfig,
        destDir: File,
        deviceName: String,
        camera: CameraType? = null
    ) {
        AppLog.i("Syncing $deviceName -> ${destDir.absolutePath} (via ${fatReader.fatType} reader)")
        syncState.value = SyncState(status = SyncState.Status.SYNCING, deviceName = deviceName, currentFile = "Scanning files...")

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

        syncState.value = syncState.value.copy(totalFiles = newFiles.size)

        if (newFiles.isEmpty()) {
            AppLog.i("All files up to date")
            syncState.value = SyncState(status = SyncState.Status.DONE, deviceName = deviceName, targetFolder = destDir.absolutePath, safeToDisconnect = true)
            return
        }

        val copyStartTime = System.currentTimeMillis()
        var copied = 0
        var errors = 0

        for (file in newFiles) {
            val targetPath = fileCopier.formatTargetPath(file.relativePath, camera?.filePrefix)
            syncState.value = syncState.value.copy(currentFile = targetPath)

            try {
                AppLog.d("Copying $targetPath (${file.length} bytes)...")
                fileCopier.copyFat32LibFile(file, destDir, targetPath)
                copied++
                AppLog.d("Copied $targetPath OK")
                syncState.value = syncState.value.copy(filesCopied = copied)
            } catch (e: Exception) {
                errors++
                AppLog.e("Error copying $targetPath", e)
                syncState.value = syncState.value.copy(errors = errors)
            }
        }

        val durationMs = System.currentTimeMillis() - copyStartTime
        finishSync(syncState, repository, deviceName, copied, newFiles.size, errors, destDir.absolutePath, durationMs)
    }

    private fun hasFileOnRoot(rootDir: UsbFile, fileName: String): Boolean {
        return try {
            rootDir.listFiles().any { it.name.equals(fileName, ignoreCase = true) }
        } catch (e: Exception) {
            AppLog.w("Error checking for $fileName on root: ${e.message}")
            false
        }
    }

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

        cameraChoiceDeferred = CompletableDeferred()
        syncState.value = syncState.value.copy(cameraChoice = pickerCameras)
        val chosen = cameraChoiceDeferred!!.await()
        return chosen
    }
}
