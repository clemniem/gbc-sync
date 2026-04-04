package com.gbcsync.app.gifmaker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SequenceDetectorTest {

    @Test
    fun diffRatio_identicalFrames_isZero() {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFF555555.toInt())
        val diff = SequenceDetector.computeDiffRatio(bitmap, bitmap)
        assertTrue("Expected 0, got $diff", diff == 0f)
    }

    @Test
    fun diffRatio_totallyDifferent_isOne() {
        val a = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        a.eraseColor(0xFFFFFFFF.toInt())
        val b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF000000.toInt())
        val diff = SequenceDetector.computeDiffRatio(a, b)
        assertTrue("Expected 1.0, got $diff", diff == 1f)
    }

    @Test
    fun checkRealFrameDiffs() {
        // This test checks if real synced frames exist and what their diff ratios are.
        // It will print diff ratios to help debug threshold issues.
        val dcim = File("/storage/emulated/0/Download/gbc-sync/2bitBridge/sync-006/DCIM/100")
        if (!dcim.isDirectory) {
            println("TEST SKIP: DCIM folder not found at ${dcim.absolutePath}")
            return
        }

        val pngs = dcim.listFiles { f -> f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.take(10)
            ?: return

        println("Found ${pngs.size} PNGs in ${dcim.absolutePath}")

        var prevBitmap: Bitmap? = null
        for (i in pngs.indices) {
            val bitmap = BitmapFactory.decodeFile(pngs[i].absolutePath)
            if (bitmap == null) {
                println("  Frame $i (${pngs[i].name}): FAILED TO DECODE")
                continue
            }
            println("  Frame $i (${pngs[i].name}): ${bitmap.width}x${bitmap.height}, config=${bitmap.config}")
            if (prevBitmap != null) {
                val diff = SequenceDetector.computeDiffRatio(prevBitmap, bitmap)
                println("    diff[${i-1}→$i] = $diff (threshold=0.35, ${if (diff < 0.35f) "SIMILAR" else "DIFFERENT"})")
            }
            prevBitmap?.recycle()
            prevBitmap = bitmap
        }
        prevBitmap?.recycle()
    }
}
