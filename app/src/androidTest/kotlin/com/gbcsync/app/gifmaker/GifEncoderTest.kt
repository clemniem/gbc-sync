package com.gbcsync.app.gifmaker

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class GifEncoderTest {

    private val palette4 = intArrayOf(
        0xFFFFFFFF.toInt(), // white
        0xFFAAAAAA.toInt(), // light gray
        0xFF555555.toInt(), // dark gray
        0xFF000000.toInt(), // black
    )

    @Test
    fun singleFrame_hasValidGifStructure() {
        val bitmap = createTestBitmap(4, 4, palette4)
        val bytes = encodeGif(bitmap, palette4, delayCs = 10)

        // Check GIF89a header
        val header = String(bytes, 0, 6)
        assertEquals("GIF89a", header)

        // Check trailer
        assertEquals(0x3B.toByte(), bytes.last())

        // Check logical screen descriptor (width=4, height=4)
        assertEquals(4, readShort(bytes, 6))
        assertEquals(4, readShort(bytes, 8))

        // Verify the file is not empty garbage
        assertTrue("GIF too small: ${bytes.size} bytes", bytes.size > 50)
    }

    @Test
    fun singleFrame_decodableByAndroid() {
        val w = 8
        val h = 8
        val bitmap = createTestBitmap(w, h, palette4)
        val bytes = encodeGif(bitmap, palette4, delayCs = 10)

        // Write to temp file and decode with Android
        val tmpFile = File.createTempFile("test", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)

            // Verify pixel colors survived round-trip
            val pixel = decoded.getPixel(0, 0)
            // First pixel should be white (from our test pattern)
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue("Expected white-ish pixel, got r=$r g=$g b=$b", r > 200 && g > 200 && b > 200)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun multipleFrames_decodableByAndroid() {
        val w = 4
        val h = 4
        val frame1 = createSolidBitmap(w, h, palette4[0]) // white
        val frame2 = createSolidBitmap(w, h, palette4[3]) // black

        val baos = ByteArrayOutputStream()
        val encoder = GifEncoder(baos)
        encoder.start(w, h, palette4)
        encoder.addFrame(frame1, 33)
        encoder.addFrame(frame2, 33)
        encoder.finish()
        val bytes = baos.toByteArray()

        val tmpFile = File.createTempFile("test_multi", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun realisticSize_128x112_decodable() {
        val w = 128
        val h = 112
        val bitmap = createTestBitmap(w, h, palette4)
        val bytes = encodeGif(bitmap, palette4, delayCs = 33)

        val tmpFile = File.createTempFile("test_realistic", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun scaled2x_256x224_decodable() {
        val w = 128
        val h = 112
        val bitmap = createTestBitmap(w, h, palette4)
        val scaled = GifEncoder.scaleNearest(bitmap, 2)
        assertEquals(256, scaled.width)
        assertEquals(224, scaled.height)

        val bytes = encodeGif(scaled, palette4, delayCs = 33)

        val tmpFile = File.createTempFile("test_scaled", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(256, decoded.width)
            assertEquals(224, decoded.height)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun pixelColors_roundTrip() {
        // Create a 4x1 bitmap with one pixel of each color
        val w = 4
        val h = 1
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, palette4[0])
        bitmap.setPixel(1, 0, palette4[1])
        bitmap.setPixel(2, 0, palette4[2])
        bitmap.setPixel(3, 0, palette4[3])

        val bytes = encodeGif(bitmap, palette4, delayCs = 10)

        val tmpFile = File.createTempFile("test_colors", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }

            for (x in 0 until w) {
                val expected = palette4[x] or 0xFF000000.toInt()
                val actual = decoded.getPixel(x, 0) or 0xFF000000.toInt()
                assertEquals(
                    "Pixel $x mismatch: expected ${Integer.toHexString(expected)}, got ${Integer.toHexString(actual)}",
                    expected,
                    actual,
                )
            }
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun tiny2x2_decodable() {
        // Minimal non-trivial case: 2x2 with different colors
        val w = 2
        val h = 2
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, palette4[0]) // white
        bitmap.setPixel(1, 0, palette4[1]) // light gray
        bitmap.setPixel(0, 1, palette4[2]) // dark gray
        bitmap.setPixel(1, 1, palette4[3]) // black

        val bytes = encodeGif(bitmap, palette4, delayCs = 10)

        val tmpFile = File.createTempFile("test_tiny", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun lzwCompression_allSamePixels() {
        // Worst case for bugs: all pixels are the same color
        val w = 16
        val h = 16
        val bitmap = createSolidBitmap(w, h, palette4[2])
        val bytes = encodeGif(bitmap, palette4, delayCs = 10)

        val tmpFile = File.createTempFile("test_solid", ".gif")
        try {
            tmpFile.writeBytes(bytes)
            val source = ImageDecoder.createSource(tmpFile)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            assertEquals(w, decoded.width)
            assertEquals(h, decoded.height)

            val expected = palette4[2] or 0xFF000000.toInt()
            val actual = decoded.getPixel(0, 0) or 0xFF000000.toInt()
            assertEquals(Integer.toHexString(expected), Integer.toHexString(actual))
        } finally {
            tmpFile.delete()
        }
    }

    // --- Helpers ---

    private fun encodeGif(bitmap: Bitmap, palette: IntArray, delayCs: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val encoder = GifEncoder(baos)
        encoder.start(bitmap.width, bitmap.height, palette)
        encoder.addFrame(bitmap, delayCs)
        encoder.finish()
        return baos.toByteArray()
    }

    private fun createTestBitmap(w: Int, h: Int, palette: IntArray): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                bitmap.setPixel(x, y, palette[(x + y) % palette.size])
            }
        }
        return bitmap
    }

    private fun createSolidBitmap(w: Int, h: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }
}
