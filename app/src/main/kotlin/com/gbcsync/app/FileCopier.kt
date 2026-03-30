package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import com.gbcsync.app.data.SyncRepository
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.fs.UsbFileStreamFactory
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileCopier(
    private val repository: SyncRepository,
) {
    fun collectLibaumsFiles(
        dir: UsbFile,
        pathPrefix: String,
        filter: String,
        recursive: Boolean,
        result: MutableList<Pair<UsbFile, String>>,
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
     * Read a small USB file entirely into memory (for hash comparison of .sav files).
     * Does NOT close the stream to avoid FatFile.flush() triggering SCSI WRITE commands.
     */
    fun readUsbFileContent(
        usbFile: UsbFile,
        fs: FileSystem,
    ): ByteArray {
        val inputStream = UsbFileStreamFactory.createBufferedInputStream(usbFile, fs)
        return inputStream.readBytes()
    }

    /** Read a small FatFsFile entirely into memory (for hash comparison of .sav files). */
    fun readFatFileContent(file: FatFsFile): ByteArray = file.readContents().use { it.readBytes() }

    /**
     * Builds the target path for a synced file.
     * Pattern: <infix>/<datetime>-<infix>.<ext>
     * e.g. "grn/2026-03-05_112233-grn.sav"
     * If no infix, returns the original path unchanged.
     */
    fun formatTargetPath(
        originalPath: String,
        infix: String?,
    ): String {
        if (infix == null) return originalPath
        val fileName = originalPath.substringAfterLast('/')
        val ext = fileName.substringAfterLast('.', "")
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
        val newName = if (ext.isNotEmpty()) "$timestamp-$infix.$ext" else "$timestamp-$infix"
        return "$infix/$newName"
    }

    /**
     * @param skipClose If true, skips closing the libaums input stream to avoid
     *   FatFile.flush() sending SCSI WRITE commands back to the device.
     *   Must be true for both Bridge (corrupts RP2040 USB state) and JoeyJr
     *   (corrupts ROM data on MiniCam PhotoRom carts).
     */
    fun copyLibaumsFile(
        usbFile: UsbFile,
        destDir: File,
        relativePath: String,
        chunkSize: Int,
        fs: FileSystem,
        skipClose: Boolean = false,
    ) {
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

        if (!tmpFile.renameTo(destFile)) {
            tmpFile.delete()
            throw java.io.IOException("Failed to rename tmp file to $relativePath")
        }
    }

    fun copyFat32LibFile(
        file: FatFsFile,
        destDir: File,
        relativePath: String,
    ) {
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

        if (!tmpFile.renameTo(destFile)) {
            tmpFile.delete()
            throw java.io.IOException("Failed to rename tmp file to $relativePath")
        }
    }
}
