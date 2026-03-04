package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import de.waldheinz.fs.BlockDevice
import de.waldheinz.fs.FsDirectoryEntry
import de.waldheinz.fs.fat.FatFileSystem
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * Adapts libaums BlockDeviceDriver to fat32-lib BlockDevice interface.
 * This lets fat32-lib (which supports FAT12/16/32) read from a USB device
 * that libaums has already set up at the SCSI/USB level.
 */
class LibaumsBlockDeviceAdapter(
    private val driver: BlockDeviceDriver
) : BlockDevice {

    private var closed = false

    override fun getSize(): Long = driver.blocks.toLong() * driver.blockSize.toLong()

    override fun getSectorSize(): Int = driver.blockSize

    override fun read(devOffset: Long, dest: ByteBuffer) {
        val blockSize = driver.blockSize
        val startBlock = (devOffset / blockSize).toInt()
        val offsetInBlock = (devOffset % blockSize).toInt()
        val bytesToRead = dest.remaining()

        // Calculate how many full blocks we need
        val blocksNeeded = ((offsetInBlock + bytesToRead + blockSize - 1) / blockSize)

        val buffer = ByteBuffer.allocate(blocksNeeded * blockSize)
        driver.read(startBlock.toLong(), buffer)
        buffer.position(offsetInBlock)
        buffer.limit(offsetInBlock + bytesToRead)
        dest.put(buffer)
    }

    override fun write(devOffset: Long, src: ByteBuffer) {
        // Read-only for our use case
        throw UnsupportedOperationException("Read-only")
    }

    override fun flush() {}
    override fun close() { closed = true }
    override fun isClosed(): Boolean = closed
    override fun isReadOnly(): Boolean = true
}

/**
 * Represents a file entry found on a FAT12/16 filesystem via fat32-lib.
 */
data class FatFsFile(
    val name: String,
    val relativePath: String,
    val length: Long,
    val isDirectory: Boolean,
    private val entry: FsDirectoryEntry?
) {
    fun readContents(): InputStream {
        val file = entry?.file ?: throw IllegalStateException("Not a file entry")
        return FatFsFileInputStream(file)
    }
}

/**
 * InputStream wrapper for fat32-lib FsFile.
 */
private class FatFsFileInputStream(
    private val file: de.waldheinz.fs.FsFile
) : InputStream() {

    private var position: Long = 0
    private val length: Long = file.length

    override fun read(): Int {
        if (position >= length) return -1
        val buf = ByteBuffer.allocate(1)
        file.read(position, buf)
        buf.flip()
        position++
        return buf.get().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (position >= length) return -1
        val toRead = minOf(len.toLong(), length - position).toInt()
        val buf = ByteBuffer.wrap(b, off, toRead)
        file.read(position, buf)
        position += toRead
        return toRead
    }
}

/**
 * Opens a FAT12/16/32 filesystem using fat32-lib on top of a libaums block device.
 */
object FatFsBridge {

    fun openFilesystem(driver: BlockDeviceDriver): FatFileSystem? {
        return try {
            val adapter = LibaumsBlockDeviceAdapter(driver)
            AppLog.d("fat32-lib: opening filesystem (size=${adapter.size}, sectorSize=${adapter.sectorSize})")
            val fs = FatFileSystem.read(adapter, true)
            AppLog.i("fat32-lib: filesystem opened, type=${fs.fatType}, label=${fs.volumeLabel}")
            fs
        } catch (e: Exception) {
            AppLog.e("fat32-lib: failed to open filesystem", e)
            null
        }
    }

    fun listFiles(
        fs: FatFileSystem,
        filter: String,
        recursive: Boolean,
        matchesFilter: (String, String) -> Boolean
    ): List<FatFsFile> {
        val result = mutableListOf<FatFsFile>()
        collectFiles(fs.root, "", filter, recursive, matchesFilter, result)
        return result
    }

    private fun collectFiles(
        dir: de.waldheinz.fs.FsDirectory,
        pathPrefix: String,
        filter: String,
        recursive: Boolean,
        matchesFilter: (String, String) -> Boolean,
        result: MutableList<FatFsFile>
    ) {
        try {
            for (entry in dir) {
                val name = entry.name
                if (name == "." || name == "..") continue

                if (entry.isDirectory) {
                    if (recursive) {
                        val subPath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                        collectFiles(entry.directory, subPath, filter, recursive, matchesFilter, result)
                    }
                } else if (entry.isFile) {
                    if (matchesFilter(name, filter)) {
                        val relativePath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                        result.add(
                            FatFsFile(
                                name = name,
                                relativePath = relativePath,
                                length = entry.file.length,
                                isDirectory = false,
                                entry = entry
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("fat32-lib: error listing directory $pathPrefix", e)
        }
    }
}
