package com.gbcsync.app.gifmaker

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Pure Kotlin GIF89a encoder with animation support.
 * Optimized for small, low-color images (Game Boy Camera: 128x112, 4 colors).
 */
class GifEncoder(private val out: OutputStream) {
    private var width = 0
    private var height = 0
    private var palette = intArrayOf()
    private var started = false

    fun start(width: Int, height: Int, palette: IntArray, loopCount: Int = 0) {
        this.width = width
        this.height = height
        this.palette = palette

        val colorBits = colorTableBits(palette.size)
        val tableSize = 1 shl colorBits

        // GIF89a header
        out.write("GIF89a".toByteArray())

        // Logical Screen Descriptor
        writeShort(width)
        writeShort(height)
        val packed = 0x80 or ((colorBits - 1) shl 4) or (colorBits - 1)
        out.write(packed)
        out.write(0) // background color index
        out.write(0) // pixel aspect ratio

        // Global Color Table
        for (i in 0 until tableSize) {
            val color = if (i < palette.size) palette[i] else 0
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }

        // Netscape Application Extension (looping)
        out.write(0x21)
        out.write(0xFF)
        out.write(11)
        out.write("NETSCAPE2.0".toByteArray())
        out.write(3)
        out.write(1)
        writeShort(loopCount)
        out.write(0)

        started = true
    }

    fun addFrame(bitmap: Bitmap, delayCs: Int) {
        check(started) { "Call start() before addFrame()" }
        check(bitmap.width == width && bitmap.height == height) {
            "Frame size ${bitmap.width}x${bitmap.height} doesn't match GIF size ${width}x$height"
        }

        // Graphic Control Extension
        out.write(0x21)
        out.write(0xF9)
        out.write(4)
        out.write(0)
        writeShort(delayCs)
        out.write(0)
        out.write(0)

        // Image Descriptor
        out.write(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        out.write(0)

        // Map pixels to palette indices
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            indices[i] = nearestColorIndex(pixels[i]).toByte()
        }

        // LZW compressed image data
        val minCodeSize = colorTableBits(palette.size).coerceAtLeast(2)
        out.write(minCodeSize)
        val compressed = lzwCompress(indices, minCodeSize)
        // Write as sub-blocks (max 255 bytes each)
        var offset = 0
        while (offset < compressed.size) {
            val blockSize = minOf(255, compressed.size - offset)
            out.write(blockSize)
            out.write(compressed, offset, blockSize)
            offset += blockSize
        }
        out.write(0) // block terminator
    }

    fun finish() {
        out.write(0x3B)
        out.flush()
    }

    private fun nearestColorIndex(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        var bestIndex = 0
        var bestDist = Int.MAX_VALUE
        for (i in palette.indices) {
            val pr = (palette[i] shr 16) and 0xFF
            val pg = (palette[i] shr 8) and 0xFF
            val pb = palette[i] and 0xFF
            val dist = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                if (dist == 0) break
            }
        }
        return bestIndex
    }

    /**
     * LZW compression for GIF. Returns the compressed byte stream
     * (without sub-block framing — caller handles that).
     */
    private fun lzwCompress(indices: ByteArray, minCodeSize: Int): ByteArray {
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1

        // Code table: for each code, store (prefix, suffix).
        // Codes 0..clearCode-1 are single-char codes.
        // clearCode and eoiCode are special.
        // New codes start at eoiCode + 1.
        val maxTableSize = 4096
        val tablePrefix = IntArray(maxTableSize)
        val tableSuffix = IntArray(maxTableSize)
        val tableHash = IntArray(maxTableSize) // hash -> code lookup (open addressing)
        val hashKeys = LongArray(maxTableSize) { -1L }

        var codeSize = minCodeSize + 1
        var nextCode = eoiCode + 1

        val bitStream = BitOutputStream()

        fun clearTable() {
            nextCode = eoiCode + 1
            codeSize = minCodeSize + 1
            hashKeys.fill(-1L)
        }

        fun findCode(prefix: Int, suffix: Int): Int {
            val key = (prefix.toLong() shl 16) or suffix.toLong()
            var hash = ((prefix shl 8) xor suffix) % maxTableSize
            if (hash < 0) hash += maxTableSize
            while (true) {
                if (hashKeys[hash] == -1L) return -1
                if (hashKeys[hash] == key) return tableHash[hash]
                hash = (hash + 1) % maxTableSize
            }
        }

        fun addCode(prefix: Int, suffix: Int) {
            if (nextCode >= maxTableSize) return
            val key = (prefix.toLong() shl 16) or suffix.toLong()
            var hash = ((prefix shl 8) xor suffix) % maxTableSize
            if (hash < 0) hash += maxTableSize
            while (hashKeys[hash] != -1L) {
                hash = (hash + 1) % maxTableSize
            }
            hashKeys[hash] = key
            tableHash[hash] = nextCode
            tablePrefix[nextCode] = prefix
            tableSuffix[nextCode] = suffix
            nextCode++
        }

        // Start with clear code
        clearTable()
        bitStream.writeBits(clearCode, codeSize)

        if (indices.isEmpty()) {
            bitStream.writeBits(eoiCode, codeSize)
            return bitStream.toByteArray()
        }

        var curCode = indices[0].toInt() and 0xFF

        for (i in 1 until indices.size) {
            val pixelValue = indices[i].toInt() and 0xFF
            val found = findCode(curCode, pixelValue)

            if (found >= 0) {
                curCode = found
            } else {
                bitStream.writeBits(curCode, codeSize)

                if (nextCode < maxTableSize) {
                    addCode(curCode, pixelValue)
                    // Increase code size when the next code to be assigned needs more bits
                    if (nextCode > (1 shl codeSize)) {
                        codeSize++
                    }
                }

                if (nextCode >= maxTableSize) {
                    bitStream.writeBits(clearCode, codeSize)
                    clearTable()
                }

                curCode = pixelValue
            }
        }

        bitStream.writeBits(curCode, codeSize)
        bitStream.writeBits(eoiCode, codeSize)

        return bitStream.toByteArray()
    }

    private fun writeShort(value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun colorTableBits(colorCount: Int): Int {
        var bits = 1
        while ((1 shl bits) < colorCount) bits++
        return bits.coerceIn(2, 8)
    }

    /** Accumulates bits LSB-first and emits bytes. */
    private class BitOutputStream {
        private val bytes = ByteArrayOutputStream()
        private var bitBuffer = 0
        private var bitsInBuffer = 0

        fun writeBits(code: Int, numBits: Int) {
            bitBuffer = bitBuffer or (code shl bitsInBuffer)
            bitsInBuffer += numBits
            while (bitsInBuffer >= 8) {
                bytes.write(bitBuffer and 0xFF)
                bitBuffer = bitBuffer ushr 8
                bitsInBuffer -= 8
            }
        }

        fun toByteArray(): ByteArray {
            if (bitsInBuffer > 0) {
                bytes.write(bitBuffer and 0xFF)
            }
            return bytes.toByteArray()
        }
    }

    companion object {
        fun remapBitmap(bitmap: Bitmap, targetPalette: List<Int>): Bitmap {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val srcColors = pixels.map { it or 0xFF000000.toInt() }.toSet().sortedByDescending { luminance(it) }

            val mapping = HashMap<Int, Int>()
            for (i in srcColors.indices) {
                mapping[srcColors[i]] = if (i < targetPalette.size) targetPalette[i] else targetPalette.last()
            }

            for (i in pixels.indices) {
                val opaque = pixels[i] or 0xFF000000.toInt()
                pixels[i] = mapping[opaque] ?: pixels[i]
            }

            val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            result.setPixels(pixels, 0, w, 0, 0, w, h)
            return result
        }

        private fun luminance(color: Int): Float {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        fun extractPalette(bitmap: Bitmap): IntArray {
            val colors = mutableSetOf<Int>()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (pixel in pixels) {
                colors.add(pixel or 0xFF000000.toInt())
                if (colors.size >= 256) break
            }
            return colors.toIntArray()
        }

        fun scaleNearest(bitmap: Bitmap, scale: Int): Bitmap {
            if (scale <= 1) return bitmap
            return Bitmap.createScaledBitmap(
                bitmap,
                bitmap.width * scale,
                bitmap.height * scale,
                false,
            )
        }
    }
}
