package com.gbcsync.app

import com.gbcsync.app.data.AppLog
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal read-only FAT12/16/32 reader that works directly with libaums BlockDeviceDriver.
 * Handles unsigned bytes correctly and bypasses libaums's SCSI INQUIRY requirement.
 */
class Fat12Reader(
    private val driver: BlockDeviceDriver,
    private val partitionBlockOffset: Long = 0,
) {
    // Boot sector fields (all unsigned)
    val bytesPerSector: Int
    val sectorsPerCluster: Int
    val reservedSectors: Int
    val numberOfFats: Int
    val rootEntryCount: Int
    val totalSectors: Long
    val sectorsPerFat: Long
    val fatType: String
    private val fat32RootCluster: Int

    private val clusterSize: Int
    private val fatStartByte: Long
    private val rootDirStartByte: Long // only used for FAT12/16
    private val dataStartByte: Long

    init {
        val bpb = readBytes(0, 512)

        bytesPerSector = u16(bpb, 0x0B)
        sectorsPerCluster = u8(bpb, 0x0D)
        reservedSectors = u16(bpb, 0x0E)
        numberOfFats = u8(bpb, 0x10)
        rootEntryCount = u16(bpb, 0x11)
        val totalSectors16 = u16(bpb, 0x13)
        val fatSz16 = u16(bpb, 0x16)
        val fatSz32 = u32(bpb, 0x24)
        sectorsPerFat = if (fatSz16 != 0) fatSz16.toLong() else fatSz32
        fat32RootCluster = if (fatSz16 == 0) u32(bpb, 0x2C).toInt() else 0
        val totalSectors32 = u32(bpb, 0x20)
        totalSectors = if (totalSectors16 != 0) totalSectors16.toLong() else totalSectors32

        clusterSize = sectorsPerCluster * bytesPerSector
        fatStartByte = reservedSectors.toLong() * bytesPerSector
        val rootDirSectors = ((rootEntryCount * 32) + bytesPerSector - 1) / bytesPerSector
        rootDirStartByte = fatStartByte + (numberOfFats.toLong() * sectorsPerFat * bytesPerSector)
        dataStartByte = rootDirStartByte + (rootDirSectors.toLong() * bytesPerSector)

        val dataSectors = totalSectors - (reservedSectors + numberOfFats * sectorsPerFat + rootDirSectors)
        val clusterCount = dataSectors / sectorsPerCluster

        fatType =
            when {
                clusterCount < 4085 -> "FAT12"
                clusterCount < 65525 -> "FAT16"
                else -> "FAT32"
            }

        // Read filesystem label from boot sector
        val labelOffset = if (fatType == "FAT32") 0x47 else 0x2B
        val labelBytes = bpb.copyOfRange(labelOffset, labelOffset + 11)
        val label = String(labelBytes).trim()

        // Log raw BPB bytes for debugging
        val bpbHex = bpb.copyOfRange(0, 64).joinToString(" ") { "%02X".format(it) }
        AppLog.d("BPB raw (0-63): $bpbHex")

        AppLog.i(
            "FAT reader: $fatType, label=\"$label\", " +
                "bytesPerSector=$bytesPerSector, sectorsPerCluster=$sectorsPerCluster, " +
                "clusterSize=$clusterSize, clusters=$clusterCount",
        )
        AppLog.d(
            "FAT layout: reservedSectors=$reservedSectors, numFATs=$numberOfFats, " +
                "sectorsPerFat=$sectorsPerFat, rootEntries=$rootEntryCount",
        )
        AppLog.d(
            "FAT offsets: fatStart=$fatStartByte, rootDirStart=$rootDirStartByte, " +
                "dataStart=$dataStartByte",
        )
    }

    fun listRootFiles(
        filter: String,
        matchesFilter: (String, String) -> Boolean,
    ): List<FatFsFile> {
        val result = mutableListOf<FatFsFile>()

        val rootDirBytes: ByteArray
        val entryCount: Int

        if (fatType == "FAT32") {
            // FAT32: root directory is a cluster chain starting at fat32RootCluster
            rootDirBytes = readClusterChain(fat32RootCluster)
            entryCount = rootDirBytes.size / 32
            AppLog.d("FAT32 root dir: ${rootDirBytes.size} bytes from cluster $fat32RootCluster ($entryCount entries)")
        } else {
            // FAT12/16: root directory is at a fixed location
            rootDirBytes = readBytes(rootDirStartByte, rootEntryCount * 32)
            entryCount = rootEntryCount
        }

        for (i in 0 until entryCount) {
            val offset = i * 32
            if (offset + 32 > rootDirBytes.size) break
            val firstByte = rootDirBytes[offset].toInt() and 0xFF
            if (firstByte == 0x00) break
            if (firstByte == 0xE5) continue
            val attr = rootDirBytes[offset + 11].toInt() and 0xFF
            if (attr and 0x0F == 0x0F) continue // LFN entry
            if (attr and 0x08 != 0) continue // volume label

            val name = parseDosName(rootDirBytes, offset)
            val isDir = attr and 0x10 != 0
            val fileSize = u32(rootDirBytes, offset + 28)
            val startCluster = getEntryStartCluster(rootDirBytes, offset)

            AppLog.d("  entry: \"$name\" ${if (isDir) "[DIR]" else "${fileSize}b"} attr=0x${attr.toString(16)} cluster=$startCluster")

            if (!isDir && matchesFilter(name, filter)) {
                result.add(
                    FatFsFile(
                        name = name,
                        relativePath = name,
                        length = fileSize,
                        isDirectory = false,
                        startCluster = startCluster,
                        reader = this,
                    ),
                )
            }
        }
        AppLog.i("Root dir scan: ${result.size} file(s) matched filter \"$filter\"")
        return result
    }

    fun listFilesRecursive(
        filter: String,
        matchesFilter: (String, String) -> Boolean,
    ): List<FatFsFile> {
        val result = mutableListOf<FatFsFile>()

        val rootDirBytes: ByteArray
        val entryCount: Int

        if (fatType == "FAT32") {
            rootDirBytes = readClusterChain(fat32RootCluster)
            entryCount = rootDirBytes.size / 32
        } else {
            rootDirBytes = readBytes(rootDirStartByte, rootEntryCount * 32)
            entryCount = rootEntryCount
        }

        listDirFromBytes(rootDirBytes, entryCount, "", filter, matchesFilter, result)
        return result
    }

    private fun listDirFromBytes(
        dirBytes: ByteArray,
        entryCount: Int,
        pathPrefix: String,
        filter: String,
        matchesFilter: (String, String) -> Boolean,
        result: MutableList<FatFsFile>,
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
            val startCluster = getEntryStartCluster(dirBytes, offset)

            if (name == "." || name == "..") continue

            if (isDir) {
                val subPath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                val subDirBytes = readClusterChain(startCluster)
                val subEntryCount = subDirBytes.size / 32
                listDirFromBytes(subDirBytes, subEntryCount, subPath, filter, matchesFilter, result)
            } else if (matchesFilter(name, filter)) {
                val relativePath = if (pathPrefix.isEmpty()) name else "$pathPrefix/$name"
                result.add(
                    FatFsFile(
                        name = name,
                        relativePath = relativePath,
                        length = fileSize,
                        isDirectory = false,
                        startCluster = startCluster,
                        reader = this,
                    ),
                )
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

    fun readFileData(
        startCluster: Int,
        fileSize: Long,
    ): InputStream = FatFileInputStream(this, startCluster, fileSize)

    internal fun getNextCluster(cluster: Int): Int =
        when (fatType) {
            "FAT12" -> getNextClusterFat12(cluster)
            "FAT16" -> getNextClusterFat16(cluster)
            "FAT32" -> getNextClusterFat32(cluster)
            else -> throw UnsupportedOperationException("$fatType not supported by this reader")
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

    private fun getNextClusterFat32(cluster: Int): Int {
        val fatOffset = cluster.toLong() * 4
        val fatBytes = readBytes(fatStartByte + fatOffset, 4)
        return (u32(fatBytes, 0) and 0x0FFFFFFF).toInt()
    }

    internal fun isEndOfChain(cluster: Int): Boolean =
        when (fatType) {
            "FAT12" -> cluster >= 0x0FF8
            "FAT16" -> cluster >= 0xFFF8
            "FAT32" -> (cluster and 0x0FFFFFFF) >= 0x0FFFFFF8
            else -> true
        }

    internal fun clusterToByteOffset(cluster: Int): Long = dataStartByte + (cluster - 2).toLong() * clusterSize

    internal fun readBytes(
        byteOffset: Long,
        count: Int,
    ): ByteArray {
        val blockSize = driver.blockSize
        val startBlock = byteOffset / blockSize + partitionBlockOffset
        val offsetInBlock = (byteOffset % blockSize).toInt()
        val blocksNeeded = ((offsetInBlock + count + blockSize - 1) / blockSize)

        // Read in chunks of max 32 blocks (16KB) to avoid USB transfer failures
        // on devices that can't handle large SCSI READ commands
        val maxBlocksPerRead = 32
        val fullBuffer = ByteArray(blocksNeeded * blockSize)
        var blocksRead = 0

        while (blocksRead < blocksNeeded) {
            val remaining = blocksNeeded - blocksRead
            val toRead = minOf(remaining, maxBlocksPerRead)
            val chunkBuffer = ByteBuffer.allocate(toRead * blockSize)
            driver.read(startBlock + blocksRead, chunkBuffer)
            System.arraycopy(chunkBuffer.array(), 0, fullBuffer, blocksRead * blockSize, toRead * blockSize)
            blocksRead += toRead
        }

        val result = ByteArray(count)
        System.arraycopy(fullBuffer, offsetInBlock, result, 0, count)
        return result
    }

    internal val clusterSizeBytes: Int get() = clusterSize

    /** Get start cluster from directory entry. FAT32 uses high 16 bits at offset+20. */
    private fun getEntryStartCluster(
        data: ByteArray,
        offset: Int,
    ): Int {
        val lo = u16(data, offset + 26)
        return if (fatType == "FAT32") {
            val hi = u16(data, offset + 20)
            (hi shl 16) or lo
        } else {
            lo
        }
    }

    private fun parseDosName(
        data: ByteArray,
        offset: Int,
    ): String {
        val name = String(data, offset, 8).trim()
        val ext = String(data, offset + 8, 3).trim()
        return if (ext.isEmpty()) name else "$name.$ext"
    }

    private fun u8(
        data: ByteArray,
        offset: Int,
    ): Int = data[offset].toInt() and 0xFF

    private fun u16(
        data: ByteArray,
        offset: Int,
    ): Int = (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(
        data: ByteArray,
        offset: Int,
    ): Long =
        ByteBuffer
            .wrap(data, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xFFFFFFFFL
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
    private val reader: Fat12Reader,
) {
    fun readContents(): InputStream = reader.readFileData(startCluster, length)
}

/**
 * InputStream that follows a FAT cluster chain.
 */
private class FatFileInputStream(
    private val reader: Fat12Reader,
    startCluster: Int,
    private val fileSize: Long,
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

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
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
