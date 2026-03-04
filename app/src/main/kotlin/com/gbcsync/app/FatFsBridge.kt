package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only FAT12/16 reader that works directly with libaums BlockDeviceDriver.
 * Handles unsigned bytes correctly (unlike fat32-lib which breaks on 128 sectors/cluster).
 */
class Fat12Reader(private val driver: BlockDeviceDriver) {

    // Boot sector fields (all unsigned)
    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val reservedSectors: Int
    val numberOfFats: Int
    val rootEntryCount: Int
    val totalSectors: Long
    val sectorsPerFat: Int
    val fatType: String

    private val clusterSize: Int
    private val fatStartByte: Long
    private val rootDirStartByte: Long
    private val dataStartByte: Long

    init {
        val bpb = readBytes(0, 512)

        bytesPerSector = u16(bpb, 0x0B)
        sectorsPerCluster = u8(bpb, 0x0D)
        reservedSectors = u16(bpb, 0x0E)
        numberOfFats = u8(bpb, 0x10)
        rootEntryCount = u16(bpb, 0x11)
        val totalSectors16 = u16(bpb, 0x13)
        sectorsPerFat = u16(bpb, 0x16)
        val totalSectors32 = u32(bpb, 0x20)
        totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32

        clusterSize = sectorsPerCluster * bytesPerSector
        fatStartByte = reservedSectors.toLong() * bytesPerSector
        val rootDirSectors = ((rootEntryCount * 32) + bytesPerSector - 1) / bytesPerSector
        rootDirStartByte = fatStartByte + (numberOfFats.toLong() * sectorsPerFat * bytesPerSector)
        dataStartByte = rootDirStartByte + (rootDirSectors.toLong() * bytesPerSector)

        val dataSectors = totalSectors - (reservedSectors + numberOfFats * sectorsPerFat + rootDirSectors)
        val clusterCount = dataSectors / sectorsPerCluster

        fatType = when {
            clusterCount < 4085 -> "FAT12"
            clusterCount < 65525 -> "FAT16"
            else -> "FAT32"
        }

        // Read filesystem label from boot sector
        val labelBytes = bpb.copyOfRange(0x2B, 0x2B + 11)
        val label = String(labelBytes).trim()

        AppLog.i("FAT reader: $fatType, label=\"$label\", " +
                "bytesPerSector=$bytesPerSector, sectorsPerCluster=$sectorsPerCluster, " +
                "clusterSize=$clusterSize, clusters=$clusterCount")
    }

    fun listRootFiles(filter: String, matchesFilter: (String, String) -> Boolean): List<FatFsFile> {
        val result = mutableListOf<FatFsFile>()
        val rootDirBytes = readBytes(rootDirStartByte, rootEntryCount * 32)

        for (i in 0 until rootEntryCount) {
            val offset = i * 32
            val firstByte = rootDirBytes[offset].toInt() and 0xFF
            if (firstByte == 0x00) break // no more entries
            if (firstByte == 0xE5) continue // deleted entry
            val attr = rootDirBytes[offset + 11].toInt() and 0xFF
            if (attr and 0x0F == 0x0F) continue // LFN entry
            if (attr and 0x08 != 0) continue // volume label

            val name = parseDosName(rootDirBytes, offset)
            val isDir = attr and 0x10 != 0
            val fileSize = u32(rootDirBytes, offset + 28)
            val startCluster = u16(rootDirBytes, offset + 26)

            if (!isDir && matchesFilter(name, filter)) {
                result.add(FatFsFile(
                    name = name,
                    relativePath = name,
                    length = fileSize,
                    isDirectory = false,
                    startCluster = startCluster,
                    reader = this
                ))
            }
        }
        return result
    }

    fun listFilesRecursive(filter: String, matchesFilter: (String, String) -> Boolean): List<FatFsFile> {
        val result = mutableListOf<FatFsFile>()
        listDirRecursive(rootDirStartByte, rootEntryCount, "", filter, matchesFilter, result, isRoot = true)
        return result
    }

    private fun listDirRecursive(
        dirByteOffset: Long, entryCount: Int, pathPrefix: String,
        filter: String, matchesFilter: (String, String) -> Boolean,
        result: MutableList<FatFsFile>, isRoot: Boolean
    ) {
        val dirBytes = readBytes(dirByteOffset, entryCount * 32)
        for (i in 0 until entryCount) {
            val offset = i * 32
            val firstByte = dirBytes[offset].toInt() and 0xFF
            if (firstByte == 0x00) break
            if (firstByte == 0xE5) continue
            val attr = dirBytes[offset + 11].toInt() and 0xFF
            if (attr and 0x0F == 0x0F) continue
            if (attr and 0x08 != 0) continue

            val name = parseDosName(dirBytes, offset)
            val isDir = attr and 0x10 != 0
            val fileSize = u32(dirBytes, offset + 28)
            val startCluster = u16(dirBytes, offset + 26)

            if (name == "." || name == "..") continue

            if (isDir) {
                val subPath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                // Read subdirectory cluster chain
                val subDirBytes = readClusterChain(startCluster)
                val subEntryCount = subDirBytes.size / 32
                // Write to temp position and recurse
                listDirFromBytes(subDirBytes, subEntryCount, subPath, filter, matchesFilter, result)
            } else if (matchesFilter(name, filter)) {
                val relativePath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                result.add(FatFsFile(
                    name = name,
                    relativePath = relativePath,
                    length = fileSize,
                    isDirectory = false,
                    startCluster = startCluster,
                    reader = this
                ))
            }
        }
    }

    private fun listDirFromBytes(
        dirBytes: ByteArray, entryCount: Int, pathPrefix: String,
        filter: String, matchesFilter: (String, String) -> Boolean,
        result: MutableList<FatFsFile>
    ) {
        for (i in 0 until entryCount) {
            val offset = i * 32
            if (offset + 32 > dirBytes.size) break
            val firstByte = dirBytes[offset].toInt() and 0xFF
            if (firstByte == 0x00) break
            if (firstByte == 0xE5) continue
            val attr = dirBytes[offset + 11].toInt() and 0xFF
            if (attr and 0x0F == 0x0F) continue
            if (attr and 0x08 != 0) continue

            val name = parseDosName(dirBytes, offset)
            val isDir = attr and 0x10 != 0
            val fileSize = u32(dirBytes, offset + 28)
            val startCluster = u16(dirBytes, offset + 26)

            if (name == "." || name == "..") continue

            if (isDir) {
                val subPath = "$pathPrefix/$name"
                val subDirBytes = readClusterChain(startCluster)
                val subEntryCount = subDirBytes.size / 32
                listDirFromBytes(subDirBytes, subEntryCount, subPath, filter, matchesFilter, result)
            } else if (matchesFilter(name, filter)) {
                result.add(FatFsFile(
                    name = name,
                    relativePath = "$pathPrefix/$name",
                    length = fileSize,
                    isDirectory = false,
                    startCluster = startCluster,
                    reader = this
                ))
            }
        }
    }

    internal fun readClusterChain(startCluster: Int): ByteArray {
        val chunks = mutableListOf<ByteArray>()
        var cluster = startCluster
        while (!isEndOfChain(cluster) && cluster >= 2) {
            val offset = clusterToByteOffset(cluster)
            chunks.add(readBytes(offset, clusterSize))
            cluster = getNextCluster(cluster)
        }
        val result = ByteArray(chunks.sumOf { it.size })
        var pos = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.size)
            pos += chunk.size
        }
        return result
    }

    fun readFileData(startCluster: Int, fileSize: Long): InputStream {
        return FatFileInputStream(this, startCluster, fileSize)
    }

    internal fun getNextCluster(cluster: Int): Int {
        return when (fatType) {
            "FAT12" -> getNextClusterFat12(cluster)
            "FAT16" -> getNextClusterFat16(cluster)
            else -> throw UnsupportedOperationException("$fatType not supported by this reader")
        }
    }

    private fun getNextClusterFat12(cluster: Int): Int {
        val fatOffset = cluster + (cluster / 2) // 1.5 bytes per entry
        val fatBytes = readBytes(fatStartByte + fatOffset, 2)
        val entry = u16(fatBytes, 0)
        return if (cluster % 2 == 0) {
            entry and 0x0FFF
        } else {
            (entry shr 4) and 0x0FFF
        }
    }

    private fun getNextClusterFat16(cluster: Int): Int {
        val fatOffset = cluster * 2
        val fatBytes = readBytes(fatStartByte + fatOffset, 2)
        return u16(fatBytes, 0)
    }

    internal fun isEndOfChain(cluster: Int): Boolean {
        return when (fatType) {
            "FAT12" -> cluster >= 0x0FF8
            "FAT16" -> cluster >= 0xFFF8
            else -> true
        }
    }

    internal fun clusterToByteOffset(cluster: Int): Long {
        return dataStartByte + (cluster - 2).toLong() * clusterSize
    }

    internal fun readBytes(byteOffset: Long, count: Int): ByteArray {
        val blockSize = driver.blockSize
        val startBlock = byteOffset / blockSize
        val offsetInBlock = (byteOffset % blockSize).toInt()
        val blocksNeeded = ((offsetInBlock + count + blockSize - 1) / blockSize)

        val buffer = ByteBuffer.allocate(blocksNeeded * blockSize)
        driver.read(startBlock * blockSize, buffer)
        buffer.flip()

        val result = ByteArray(count)
        buffer.position(offsetInBlock)
        buffer.get(result)
        return result
    }

    internal val clusterSizeBytes: Int get() = clusterSize

    private fun parseDosName(data: ByteArray, offset: Int): String {
        val name = String(data, offset, 8).trim()
        val ext = String(data, offset + 8, 3).trim()
        return if (ext.isEmpty()) name else "$name.$ext"
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF
    private fun u16(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }
    private fun u32(data: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }
}

/**
 * Represents a file found on a FAT12/16 filesystem.
 */
data class FatFsFile(
    val name: String,
    val relativePath: String,
    val length: Long,
    val isDirectory: Boolean,
    private val startCluster: Int,
    private val reader: Fat12Reader
) {
    fun readContents(): InputStream = reader.readFileData(startCluster, length)
}

/**
 * InputStream that follows a FAT cluster chain.
 */
private class FatFileInputStream(
    private val reader: Fat12Reader,
    startCluster: Int,
    private val fileSize: Long
) : InputStream() {

    private var currentCluster = startCluster
    private var clusterBuffer: ByteArray? = null
    private var posInCluster = 0
    private var totalRead: Long = 0

    override fun read(): Int {
        if (totalRead >= fileSize) return -1
        ensureClusterLoaded()
        val b = clusterBuffer!![posInCluster].toInt() and 0xFF
        posInCluster++
        totalRead++
        advanceIfNeeded()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (totalRead >= fileSize) return -1
        var bytesRead = 0
        var remaining = minOf(len.toLong(), fileSize - totalRead).toInt()

        while (remaining > 0) {
            ensureClusterLoaded()
            val buf = clusterBuffer!!
            val available = buf.size - posInCluster
            val toRead = minOf(remaining, available)
            System.arraycopy(buf, posInCluster, b, off + bytesRead, toRead)
            posInCluster += toRead
            totalRead += toRead
            bytesRead += toRead
            remaining -= toRead
            advanceIfNeeded()
        }
        return bytesRead
    }

    private fun ensureClusterLoaded() {
        if (clusterBuffer == null) {
            val offset = reader.clusterToByteOffset(currentCluster)
            clusterBuffer = reader.readBytes(offset, reader.clusterSizeBytes)
        }
    }

    private fun advanceIfNeeded() {
        if (clusterBuffer != null && posInCluster >= clusterBuffer!!.size && totalRead < fileSize) {
            currentCluster = reader.getNextCluster(currentCluster)
            if (reader.isEndOfChain(currentCluster)) return
            clusterBuffer = null
            posInCluster = 0
        }
    }
}
